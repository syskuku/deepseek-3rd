package com.dschat.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dschat.app.BuildConfig
import com.dschat.app.DeepSeekApp
import com.dschat.app.data.ChatMessage
import com.dschat.app.data.FileMeta
import com.dschat.app.data.HistoryArchive
import com.dschat.app.data.ReplyBlock
import com.dschat.app.data.SearchResult
import com.dschat.app.data.Session
import com.dschat.app.data.SessionMeta
import com.dschat.app.data.SessionStore
import com.dschat.app.net.ApiCondition
import com.dschat.app.net.ApiError
import com.dschat.app.net.ApiErrorKind
import com.dschat.app.net.AttachmentRules
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** 输入框待发送附件 */
data class DraftAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val mimeType: String,
    val localPath: String,
)

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

    private val _drafts = MutableStateFlow<List<DraftAttachment>>(emptyList())
    val drafts: StateFlow<List<DraftAttachment>> = _drafts

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
        _drafts.value = emptyList()
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val session = store.load(id)?.normalized()?.repaired()
            if (session == null) {
                toast("对话加载失败，请重试")
                _sessions.value = store.listMetas()
            } else {
                _active.value = session
                _drafts.value = emptyList()
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
            _drafts.value = emptyList()
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

    /** 替换树中某条消息 */
    private fun Session.replaceMessage(m: ChatMessage): Session =
        copy(messages = messages.map { if (it.id == m.id) m else it }, updatedAt = System.currentTimeMillis())

    // ------------------------------------------------------------ drafts

    /** 从 SAF 选择结果添加草稿（校验格式/大小/数量，原件存入私有目录） */
    fun addDrafts(uris: List<Uri>) {
        val context = getApplication<DeepSeekApp>()
        viewModelScope.launch(Dispatchers.IO) {
            val added = ArrayList<DraftAttachment>()
            for (uri in uris) {
                var name = "file"
                var size = 0L
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            val si = c.getColumnIndex(OpenableColumns.SIZE)
                            if (ni >= 0) name = c.getString(ni) ?: name
                            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                }
                val mime = context.contentResolver.getType(uri) ?: ""
                val isImage = mime.startsWith("image/") || AttachmentRules.isImageExt(name)
                val error = AttachmentRules.validate(name, size, isImage, _drafts.value.size + added.size)
                if (error != null) {
                    withContext(Dispatchers.Main) { toast(error) }
                    continue
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
                val id = SessionStore.newId()
                val rel = store.saveAttachment("drafts", id, name, bytes)
                added += DraftAttachment(id, name, bytes.size.toLong(), isImage, AttachmentRules.mimeOf(name, mime), rel)
            }
            withContext(Dispatchers.Main) {
                if (added.isNotEmpty()) _drafts.value = _drafts.value + added
            }
        }
    }

    fun removeDraft(id: String) {
        val draft = _drafts.value.find { it.id == id } ?: return
        _drafts.value = _drafts.value.filter { it.id != id }
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.attachmentFile(draft.localPath).delete() } }
    }

    // ------------------------------------------------------------ chat

    fun send(text: String) {
        val content = text.trim()
        val drafts = _drafts.value
        if ((content.isEmpty() && drafts.isEmpty()) || _streamingSessionId.value != null) return
        val apiKey = settings.apiKey
        if (apiKey == null) { toast("请先设置 API Key"); return }

        val now = System.currentTimeMillis()
        var session = (_active.value ?: Session(SessionStore.newId(), "", now, now, emptyList())).normalized()

        val files = drafts.map { d ->
            FileMeta(d.id, d.name, d.size, d.isImage, d.mimeType, d.localPath, null)
        }
        val userMsg = ChatMessage(
            SessionStore.newId(), "user", now, content = content, files = files,
        )
        val assistant = ChatMessage(
            SessionStore.newId(), "assistant", now + 1,
            status = "streaming", thinking = _thinking.value, search = _search.value,
        )
        // 统一走 appendRound：正确链接 parentId / selectedChildId，避免回复不可见或错误分叉
        session = session.appendRound(userMsg, assistant)
        _active.value = session
        _drafts.value = emptyList()
        upsertMeta(session)
        // 取链接后的副本进入流式状态机，避免回写时覆盖树字段
        val linkedUser = session.messages.first { it.id == userMsg.id }
        val linkedAssistant = session.messages.first { it.id == assistant.id }
        startStream(session, linkedUser, linkedAssistant, prefix = null, apiKey = apiKey)
    }

    /** 重新生成：在同一提问下新建兄弟分支（官方 appendBranch 语义） */
    fun regenerate(assistantId: String) {
        val session = _active.value?.normalized() ?: return
        if (_streamingSessionId.value != null) return
        val apiKey = settings.apiKey ?: return
        val original = session.messages.find { it.id == assistantId } ?: return
        if (original.role != "assistant") return
        val question = session.messages.find { it.id == original.parentId } ?: return
        if (question.role != "user") return

        val now = System.currentTimeMillis()
        val assistant = ChatMessage(
            SessionStore.newId(), "assistant", now,
            status = "streaming", thinking = original.thinking, search = original.search,
            parentId = question.id,
        )
        val base = session.copy(
            messages = session.messages.map {
                if (it.id == question.id) it.copy(selectedChildId = assistant.id) else it
            } + assistant,
            updatedAt = now,
        )
        _active.value = base
        upsertMeta(base)
        startStream(base, question, assistant, prefix = null, apiKey = apiKey)
    }

    /** 切换消息版本（官方 selectVersion） */
    fun switchVersion(messageId: String, targetId: String) {
        val session = _active.value?.normalized() ?: return
        val target = session.messages.find { it.id == targetId } ?: return
        val parentId = target.parentId
        val base = if (parentId == null) {
            session.copy(rootMessageId = target.id, updatedAt = System.currentTimeMillis())
        } else {
            session.copy(
                messages = session.messages.map {
                    if (it.id == parentId) it.copy(selectedChildId = target.id) else it
                },
                updatedAt = System.currentTimeMillis(),
            )
        }
        _active.value = base
        upsertMeta(base)
        viewModelScope.launch { persistActive(force = true) }
    }

    fun retryLast() {
        val last = _active.value?.visibleMessages()?.lastOrNull() ?: return
        if (last.role == "assistant" && last.status == "error") regenerate(last.id)
    }

    /** 继续生成（停止或达到长度上限的回复） */
    fun continueLast() {
        val session = _active.value?.normalized() ?: return
        if (_streamingSessionId.value != null) return
        val apiKey = settings.apiKey ?: return
        val visible = session.visibleMessages()
        val last = visible.lastOrNull() ?: return
        val canContinue = last.role == "assistant" &&
            (last.status == "stopped" || (last.status == "done" && last.stopReason == "max_tokens")) &&
            last.stopReason != "refusal" &&
            last.blocks.none { it is ReplyBlock.Text && it.isTemplate }
        if (!canContinue) return
        val question = visible.dropLast(1).lastOrNull { it.role == "user" } ?: return
        val assistant = last.copy(status = "streaming")
        val base = session.replaceMessage(assistant)
        _active.value = base
        startStream(base, question, assistant, prefix = last, apiKey = apiKey)
    }

    fun stop() {
        streamJob?.cancel()
    }

    /** 发送前补传缺少 serverId 的本地原件（官方：导入附件首次用于请求时上传） */
    private suspend fun ensureUploaded(session: Session): Session {
        var result = session
        val targets = result.visibleMessages().filter { it.role == "user" && it.files.any { f -> f.serverId == null && f.localPath != null } }
        for (m in targets) {
            val updatedFiles = m.files.toMutableList()
            for ((i, f) in updatedFiles.withIndex()) {
                if (f.serverId != null || f.localPath == null) continue
                val file = store.attachmentFile(f.localPath)
                if (!file.exists()) continue
                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                val serverId = DeepSeekClient.uploadFile(
                    settings.apiKey ?: "", f.name, f.mimeType,
                    AttachmentRules.purposeOf(f.name, f.isImage), bytes,
                )
                updatedFiles[i] = f.copy(serverId = serverId)
            }
            result = result.replaceMessage(m.copy(files = updatedFiles))
        }
        return result
    }

    /** 附件 file_id 失效：清空全部 serverId 以便重传 */
    private fun Session.invalidateFileIds(): Session = copy(
        messages = messages.map { m ->
            if (m.role == "user" && m.files.any { it.serverId != null }) {
                m.copy(files = m.files.map { it.copy(serverId = null) })
            } else m
        },
    )

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
            var fileRetryUsed = false
            var finished = false
            var currentBase = base

            fun pushReply() {
                updateActive { s ->
                    if (s.id != sessionId) s
                    else s.copy(
                        // 保留树字段：流式回写只更新内容，绝不覆盖 parentId / selectedChildId
                        messages = s.messages.map {
                            if (it.id == reply.id) reply.copy(parentId = it.parentId, selectedChildId = it.selectedChildId) else it
                        },
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }

            try {
                while (!finished) {
                    try {
                        // 补传附件（含历史轮次缺失的 serverId）
                        currentBase = ensureUploaded(currentBase)
                        _active.value = currentBase
                        val visible = currentBase.visibleMessages()
                        val history = visible.dropLast(2)
                        val liveQuestion = visible.find { it.id == question.id } ?: question
                        val messages = RequestBuilder.toRequestMessages(history, liveQuestion, prefix, reply.thinking)
                        val hasFiles = RequestBuilder.hasFileBlocks(history, liveQuestion)
                        val body = RequestBuilder.buildBody(messages, reply.thinking, reply.search, requestTitle && currentBase.title.isBlank())

                        DeepSeekClient.streamMessage(apiKey, body, hasFiles).collect { event ->
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
                        reply = settle(reply, "done")
                        finished = true
                    } catch (e: CancellationException) {
                        reply = settle(reply, "stopped")
                        pushReply()
                        withContext(NonCancellable) { persistActive(force = true) }
                        throw e
                    } catch (e: Throwable) {
                        // 附件失效：重传一次
                        if (!fileRetryUsed && e is ApiError && AttachmentRules.isFileIdError(e.status, e.message)) {
                            fileRetryUsed = true
                            currentBase = currentBase.invalidateFileIds()
                            _active.value = currentBase
                            continue
                        }
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
                // 用户停止
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
                        val results = block["content"]?.jsonArray?.mapNotNull { it as? JsonObject }
                            ?.filter { it.str("type") == "web_search_result" }
                            ?.map { r ->
                                SearchResult(r.str("url") ?: "", r.str("title") ?: "", r.str("snippet") ?: "", r.str("site_name"))
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
        return reply.copy(status = if (refusal) "refusal" else status, blocks = blocks)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    // ------------------------------------------------------------ import / export

    /** 导入历史对话 ZIP（覆盖全部本地会话，含图片原件与消息分支） */
    suspend fun importArchive(openStream: () -> InputStream): Int = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            // 先清旧附件，再提取新原件（replaceAll 只替换会话，不动附件）
            store.clearAttachments()
            val sessions = HistoryArchive.import(openStream) { entryName, bytes ->
                // 条目名 chat_sessions/{sid}_files/{fid}_{name}
                val sid = entryName.removePrefix("chat_sessions/").substringBefore("_files/")
                val fid = entryName.substringAfterLast("/").substringBefore("_")
                store.saveAttachment(sid, fid, entryName.substringAfterLast("/"), bytes)
            }
            stop()
            store.replaceAll(sessions)
            withContext(Dispatchers.Main) {
                _active.value = null
                _drafts.value = emptyList()
            }
            refreshMetas()
            sessions.size
        } finally {
            _busy.value = false
        }
    }

    /** 从官方导出链接下载 ZIP 并导入（华为云 OBS 等签名直链） */
    suspend fun importFromUrl(url: String): Int = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw IllegalArgumentException("链接无效，请粘贴完整的导出链接")
        }
        val context = getApplication<DeepSeekApp>()
        val temp = File.createTempFile("ds_link", ".zip", context.cacheDir)
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder().url(trimmed).get().build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                temp.outputStream().use { out ->
                    resp.body?.byteStream()?.copyTo(out) ?: throw IOException("下载内容为空")
                }
            }
            importArchive { temp.inputStream() }
        } finally {
            temp.delete()
        }
    }

    suspend fun exportArchive(output: OutputStream) = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            val metas = store.listMetas()
            val sessions = metas.mapNotNull { store.load(it.id)?.normalized() }
            if (sessions.isEmpty()) throw IllegalStateException("没有可导出的对话")
            HistoryArchive.export(sessions, { f ->
                f.localPath?.let { p ->
                    val file = store.attachmentFile(p)
                    if (file.exists()) runCatching { file.readBytes() }.getOrNull() else null
                }
            }, output)
        } finally {
            _busy.value = false
        }
    }

    /** 附件原件文件（供缩略图加载） */
    fun attachmentFile(relPath: String): File = store.attachmentFile(relPath)

    // ------------------------------------------------------------ in-app update

    private val _updateInfo = MutableStateFlow<UpdateChecker.UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateChecker.UpdateInfo?> = _updateInfo

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress

    private val _downloadedApk = MutableStateFlow<File?>(null)
    val downloadedApk: StateFlow<File?> = _downloadedApk

    private val _updateError = MutableStateFlow<String?>(null)
    val updateError: StateFlow<String?> = _updateError

    private var updateJob: Job? = null

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