package com.dschat.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.BuildConfig
import com.dschat.app.DeepSeekApp
import com.dschat.app.data.ChatMessage
import com.dschat.app.data.HistoryArchive
import com.dschat.app.data.ReplyBlock
import com.dschat.app.data.SearchResult
import com.dschat.app.data.Session
import com.dschat.app.data.SessionMeta
import com.dschat.app.data.SessionStore
import com.dschat.app.net.ApiCondition
import com.dschat.app.net.ApiError
import com.dschat.app.net.ApiErrorKind
import com.dschat.app.net.DeepSeekClient
import com.dschat.app.net.RequestBuilder
import com.dschat.app.net.StreamEvent
import com.dschat.app.net.UpdateChecker
import com.dschat.app.net.classifyError
import com.dschat.app.net.isRetryable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = (app as DeepSeekApp).settings
    private val store = SessionStore(app)

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions

    private val _active = MutableStateFlow<Session?>(null)
    val active: StateFlow<Session?> = _active

    private val _streamingSessionId = MutableStateFlow<String?>(null)
    val streamingSessionId: StateFlow<String?> = _streamingSessionId

    private val _thinking = MutableStateFlow(settings.thinking)
    val thinking: StateFlow<Boolean> = _thinking

    private val _search = MutableStateFlow(settings.search)
    val search: StateFlow<Boolean> = _search

    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<String> = _theme

    private val _hasApiKey = MutableStateFlow(settings.apiKey != null)
    val hasApiKey: StateFlow<Boolean> = _hasApiKey

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private var streamJob: Job? = null
    private var lastSaveAt = 0L

    init {
        viewModelScope.launch { _sessions.value = store.listMetas() }
    }

    fun toast(message: String) { _toast.tryEmit(message) }

    // ------------------------------------------------------------ settings

    /** 校验并保存 API Key；失败抛出 ApiError 由 UI 展示 */
    suspend fun verifyAndSaveApiKey(key: String) {
        DeepSeekClient.verifyApiKey(key)
        settings.apiKey = key
        _hasApiKey.value = true
    }

    fun currentApiKey(): String = settings.apiKey ?: ""

    fun setThinking(value: Boolean) { _thinking.value = value; settings.thinking = value }
    fun setSearch(value: Boolean) { _search.value = value; settings.search = value }
    fun setTheme(value: String) { _theme.value = value; settings.theme = value }

    // ------------------------------------------------------------ sessions

    fun newChat() {
        if (_streamingSessionId.value != null) stop()
        _active.value = null
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val session = store.load(id)
            if (session == null) {
                toast("对话加载失败，请重试")
                _sessions.value = store.listMetas()
            } else {
                _active.value = session
            }
        }
    }

    fun renameSession(id: String, title: String) {
        viewModelScope.launch {
            val session = if (_active.value?.id == id) _active.value else store.load(id)
            if (session != null) {
                val updated = session.copy(title = title.trim(), updatedAt = System.currentTimeMillis())
                store.save(updated)
                if (_active.value?.id == id) _active.value = updated
                refreshMetas()
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            if (_streamingSessionId.value == id) stop()
            store.delete(id)
            if (_active.value?.id == id) _active.value = null
            refreshMetas()
        }
    }

    fun deleteAllSessions() {
        viewModelScope.launch {
            stop()
            store.deleteAll()
            _active.value = null
            refreshMetas()
            toast("删除成功")
        }
    }

    private suspend fun refreshMetas() { _sessions.value = store.listMetas() }

    private fun upsertMeta(session: Session) {
        val meta = session.meta()
        _sessions.value = (listOf(meta) + _sessions.value.filter { it.id != session.id })
            .sortedByDescending { it.updatedAt }
    }

    private fun updateActive(transform: (Session) -> Session) {
        val current = _active.value ?: return
        val next = transform(current)
        _active.value = next
        upsertMeta(next)
    }

    private suspend fun persistActive(force: Boolean = false) {
        val session = _active.value ?: return
        val now = System.currentTimeMillis()
        if (force || now - lastSaveAt > 2000) {
            lastSaveAt = now
            store.save(session)
        }
    }

    // ------------------------------------------------------------ chat

    fun send(text: String) {
        val content = text.trim()
        if (content.isEmpty() || _streamingSessionId.value != null) return
        val apiKey = settings.apiKey
        if (apiKey == null) { toast("请先设置 API Key"); return }

        val now = System.currentTimeMillis()
        var session = _active.value
        if (session == null) {
            session = Session(SessionStore.newId(), "", now, now, emptyList())
            _active.value = session
        }
        val userMsg = ChatMessage(SessionStore.newId(), "user", now, content = content)
        val assistant = ChatMessage(
            SessionStore.newId(), "assistant", now,
            status = "streaming", thinking = _thinking.value, search = _search.value,
        )
        val base = session.copy(
            messages = session.messages + userMsg + assistant,
            updatedAt = now,
        )
        _active.value = base
        upsertMeta(base)
        startStream(base, userMsg, assistant, prefix = null, apiKey = apiKey)
    }

    /** 重试最后一条失败回复 */
    fun retryLast() {
        val session = _active.value ?: return
        if (_streamingSessionId.value != null) return
        val apiKey = settings.apiKey ?: return
        val last = session.messages.lastOrNull() ?: return
        if (last.role != "assistant" || last.status != "error") return
        val question = session.messages.dropLast(1).lastOrNull { it.role == "user" } ?: return
        val assistant = last.copy(status = "streaming", errorText = null, blocks = emptyList(), stopReason = null)
        val base = session.copy(messages = session.messages.dropLast(1) + assistant, updatedAt = System.currentTimeMillis())
        _active.value = base
        startStream(base, question, assistant, prefix = null, apiKey = apiKey)
    }

    /** 继续生成（停止或达到长度上限的回复） */
    fun continueLast() {
        val session = _active.value ?: return
        if (_streamingSessionId.value != null) return
        val apiKey = settings.apiKey ?: return
        val last = session.messages.lastOrNull() ?: return
        val canContinue = last.role == "assistant" &&
            (last.status == "stopped" || (last.status == "done" && last.stopReason == "max_tokens")) &&
            last.stopReason != "refusal" &&
            last.blocks.none { it is ReplyBlock.Text && it.isTemplate }
        if (!canContinue) return
        val question = session.messages.dropLast(1).lastOrNull { it.role == "user" } ?: return
        val assistant = last.copy(status = "streaming")
        val base = session.copy(messages = session.messages.dropLast(1) + assistant, updatedAt = System.currentTimeMillis())
        _active.value = base
        startStream(base, question, assistant, prefix = last, apiKey = apiKey)
    }

    fun stop() {
        streamJob?.cancel()
    }

    private fun startStream(
        base: Session,
        question: ChatMessage,
        assistant: ChatMessage,
        prefix: ChatMessage?,
        apiKey: String,
    ) {
        val sessionId = base.id
        _streamingSessionId.value = sessionId
        streamJob = viewModelScope.launch {
            var reply = assistant
            val blockIndexes = HashMap<Int, Int>()
            val requestTitle = base.title.isBlank()
            var receivedContent = false
            var attempt = 0
            var finished = false

            fun pushReply() {
                updateActive { s ->
                    if (s.id != sessionId) s
                    else s.copy(
                        messages = s.messages.map { if (it.id == reply.id) reply else it },
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }

            try {
                while (!finished) {
                    try {
                        val history = base.messages.dropLast(2).let {
                            // base 末尾是 user + assistant；续写时末尾 assistant 即 prefix
                            if (prefix != null) base.messages.dropLast(2) else it
                        }
                        val messages = RequestBuilder.toRequestMessages(
                            history = history,
                            question = question,
                            prefix = prefix,
                            thinking = reply.thinking,
                        )
                        val body = RequestBuilder.buildBody(messages, reply.thinking, reply.search, requestTitle && base.title.isBlank())

                        DeepSeekClient.streamMessage(apiKey, body).collect { event ->
                            receivedContent = receivedContent || event is StreamEvent.BlockStart
                            reply = applyEvent(reply, event, blockIndexes)
                            if (event is StreamEvent.MessageDelta) {
                                val title = event.title?.trim()
                                if (!title.isNullOrBlank() && requestTitle) {
                                    updateActive { s -> if (s.id == sessionId && s.title.isBlank()) s.copy(title = title) else s }
                                }
                            }
                            pushReply()
                            persistActive()
                            if (event is StreamEvent.MessageStop) finished = true
                        }
                        if (!finished) throw ApiError(ApiErrorKind.INTERRUPTED, "")
                        // 正常结束
                        reply = settle(reply, "done")
                        finished = true
                    } catch (e: CancellationException) {
                        reply = settle(reply, "stopped")
                        pushReply()
                        withContext(NonCancellable) { persistActive(force = true) }
                        throw e
                    } catch (e: Throwable) {
                        if (isRetryable(e) && !receivedContent && attempt < 2) {
                            attempt += 1
                            delay(1000L * (1 shl attempt))
                            continue
                        }
                        reply = settle(reply, "error").copy(
                            errorText = errorMessage(e),
                            errorRetryable = isRetryable(e) || classifyError(e) == ApiCondition.UNAUTHORIZED,
                        )
                        finished = true
                    }
                }
                pushReply()
                persistActive(force = true)
            } catch (_: CancellationException) {
                // 用户停止：settle 已在上面完成
            } finally {
                _streamingSessionId.value = null
                withContext(NonCancellable) { persistActive(force = true) }
            }
        }
    }

    private fun errorMessage(e: Throwable): String = when (classifyError(e)) {
        ApiCondition.UNAUTHORIZED -> "API Key 无效或已被删除，请更换后重试"
        ApiCondition.INSUFFICIENT_BALANCE -> "账户余额不足，请充值后重试"
        ApiCondition.RATE_LIMITED -> "请求过于频繁，请稍后再试"
        ApiCondition.SERVER_BUSY -> "服务器繁忙，请稍后再试"
        ApiCondition.NETWORK -> "网络异常，请检查你的网络状况"
        ApiCondition.TOO_LARGE -> "内容过长，请缩短后重试"
        ApiCondition.OTHER -> (e.message?.takeIf { it.isNotBlank() } ?: "未知错误")
    }

    /** 对照官方 applyEvent：合并流式事件到回复 */
    private fun applyEvent(reply: ChatMessage, event: StreamEvent, blockIndexes: HashMap<Int, Int>): ChatMessage {
        fun updateBlock(index: Int, transform: (ReplyBlock) -> ReplyBlock): ChatMessage {
            val local = blockIndexes[index] ?: return reply
            val blocks = reply.blocks.toMutableList()
            if (local !in blocks.indices) return reply
            blocks[local] = transform(blocks[local])
            return reply.copy(blocks = blocks)
        }

        return when (event) {
            is StreamEvent.MessageStart -> reply
            is StreamEvent.BlockStart -> {
                blockIndexes.remove(event.index)
                val now = System.currentTimeMillis()
                val block = event.block
                val newBlock: ReplyBlock? = when (block.str("type")) {
                    "text" -> ReplyBlock.Text(block.str("text") ?: "")
                    "thinking" -> ReplyBlock.Thinking(
                        thinking = block.str("thinking") ?: "",
                        signature = block.str("signature"),
                        startedAt = now,
                    )
                    "server_tool_use" -> {
                        val kind = block.str("kind")
                        if (kind == "search" || kind == "deep_search") {
                            val queries = block["input"]?.jsonObject?.get("queries")?.jsonArray
                                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList()
                            ReplyBlock.Search(queries = queries, status = "WIP")
                        } else null
                    }
                    "web_search_tool_result" -> {
                        // 合并进最近的搜索块
                        val results = block["content"]?.jsonArray?.mapNotNull { it as? JsonObject }
                            ?.filter { it.str("type") == "web_search_result" }
                            ?.map { r ->
                                SearchResult(
                                    url = r.str("url") ?: "",
                                    title = r.str("title") ?: "",
                                    snippet = r.str("snippet") ?: "",
                                    siteName = r.str("site_name"),
                                )
                            } ?: emptyList()
                        val failed = block["content"]?.jsonArray?.any {
                            (it as? JsonObject)?.str("type") == "web_search_tool_result_error"
                        } ?: false
                        val blocks = reply.blocks.toMutableList()
                        val idx = blocks.indexOfLast { it is ReplyBlock.Search && it.status == "WIP" }
                        if (idx >= 0) {
                            blocks[idx] = (blocks[idx] as ReplyBlock.Search).copy(
                                results = results, status = if (failed) "FAILED" else "FINISHED",
                            )
                            return reply.copy(blocks = blocks)
                        }
                        ReplyBlock.Search(results = results, status = if (failed) "FAILED" else "FINISHED")
                    }
                    else -> null
                }
                if (newBlock == null) return reply
                blockIndexes[event.index] = reply.blocks.size
                reply.copy(blocks = reply.blocks + newBlock)
            }
            is StreamEvent.BlockDelta -> {
                val delta = event.delta
                when (delta.str("type")) {
                    "text_delta" -> updateBlock(event.index) { b ->
                        if (b is ReplyBlock.Text) b.copy(text = b.text + (delta.str("text") ?: "")) else b
                    }
                    "thinking_delta" -> updateBlock(event.index) { b ->
                        if (b is ReplyBlock.Thinking) b.copy(thinking = b.thinking + (delta.str("thinking") ?: "")) else b
                    }
                    "signature_delta" -> updateBlock(event.index) { b ->
                        if (b is ReplyBlock.Thinking) b.copy(signature = (b.signature ?: "") + (delta.str("signature") ?: "")) else b
                    }
                    else -> reply
                }
            }
            is StreamEvent.BlockStop -> updateBlock(event.index) { b ->
                if (b is ReplyBlock.Thinking && b.startedAt != null && b.endedAt == null) {
                    b.copy(endedAt = System.currentTimeMillis())
                } else b
            }
            is StreamEvent.MessageDelta -> reply.copy(stopReason = event.stopReason ?: reply.stopReason)
            is StreamEvent.MessageStop -> reply
        }
    }

    /** 对照官方 settleReply：收尾思考块；拒绝时丢弃思考与正文，保留模板提示 */
    private fun settle(reply: ChatMessage, status: String): ChatMessage {
        val now = System.currentTimeMillis()
        val refusal = reply.stopReason == "refusal"
        var blocks = reply.blocks.map { b ->
            if (b is ReplyBlock.Thinking && b.startedAt != null && b.endedAt == null) b.copy(endedAt = now) else b
        }
        if (refusal) {
            blocks = blocks.filter { it !is ReplyBlock.Thinking && it !is ReplyBlock.Text }
            blocks = blocks + ReplyBlock.Text("你好，这个问题我暂时无法回答，让我们换个话题再聊聊吧。", isTemplate = true)
        }
        return reply.copy(
            status = if (refusal) "refusal" else status,
            blocks = blocks,
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    // ------------------------------------------------------------ import / export

    /** 导入历史对话 ZIP（覆盖全部本地会话）；返回导入数量，失败抛异常 */
    suspend fun importArchive(input: InputStream): Int = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            val sessions = HistoryArchive.import(input)
            stop()
            store.replaceAll(sessions)
            _active.value = null
            refreshMetas()
            sessions.size
        } finally {
            _busy.value = false
        }
    }

    suspend fun exportArchive(output: OutputStream) = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            val metas = store.listMetas()
            val sessions = metas.mapNotNull { store.load(it.id) }
            if (sessions.isEmpty()) throw IllegalStateException("没有可导出的对话")
            HistoryArchive.export(sessions, output)
        } finally {
            _busy.value = false
        }
    }

    // ------------------------------------------------------------ in-app update

    private val _updateInfo = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateChecker.UpdateInfo?> = _updateInfo

    /** null = 未在下载；0..1 = 进度 */
    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress

    private val _downloadedApk = MutableStateFlow<File?>(null)
    val downloadedApk: StateFlow<File?> = _downloadedApk

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError

    private var updateJob: Job? = null

    /** 检查 GitHub 最新 Release；manual=true 时无更新也提示 */
    fun checkUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            try {
                val info = UpdateChecker.fetchLatest()
                if (info != null && UpdateChecker.isNewer(info.version, BuildConfig.VERSION_NAME)) {
                    _updateInfo.value = info
                    _updateError.value = null
                } else if (manual) {
                    toast("已是最新版本（v${BuildConfig.VERSION_NAME}）")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) toast("检查更新失败：${e.message ?: "网络异常"}")
            }
        }
    }

    fun dismissUpdate() {
        updateJob?.cancel()
        _updateInfo.value = null
        _updateProgress.value = null
        _updateError.value = null
    }

    fun downloadUpdate(context: Context) {
        val info = _updateInfo.value ?: return
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch {
            _updateProgress.value = 0f
            _updateError.value = null
            try {
                val file = UpdateChecker.download(context.applicationContext, info.apkUrl) { p ->
                    _updateProgress.value = p
                }
                _downloadedApk.value = file
                _updateProgress.value = null
                installUpdate(context)
            } catch (e: CancellationException) {
                _updateProgress.value = null
                throw e
            } catch (e: Exception) {
                _updateProgress.value = null
                _updateError.value = "下载失败：${e.message ?: "网络异常"}"
            }
        }
    }

    fun cancelDownload() {
        updateJob?.cancel()
        _updateProgress.value = null
    }

    /** 通过 FileProvider 调起系统安装器；未授权未知来源时先跳转设置 */
    fun installUpdate(context: Context) {
        val file = _downloadedApk.value ?: return
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            toast("请允许安装未知应用，返回后点击「安装」")
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { toast("无法启动安装器：${it.message}") }
    }
}