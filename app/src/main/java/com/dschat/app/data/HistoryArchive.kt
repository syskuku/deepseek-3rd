package com.dschat.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ArchiveException(message: String) : Exception(message)

/**
 * DeepSeek 历史对话 ZIP（export_format_version: 3）导入/导出。
 * 归档结构对照官方 src/services/chat/history/README.md：
 *   manifest.json（首个条目）
 *   chat_sessions/{id}.json（消息树）
 *   chat_session_index.json（索引）
 * 消息树以 id/parent_id 还原，current_message_id 决定当前分支。
 * v1 限制：不导入/导出图片原件，附件仅保留文件名与大小元数据。
 */
object HistoryArchive {
    private const val VERSION = 3
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun iso(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private fun parseIso(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            val normalized = text.replace(Regex("(\\.\\d{1,3})\\d*Z$")) { m ->
                "." + m.groupValues[1].removePrefix(".").padEnd(3, '0') + "Z"
            }.let { if (it.endsWith("Z") && !it.contains('.')) it.replace("Z", ".000Z") else it }
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(normalized)?.time
                ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content?.toBoolean() ?: false

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    // ---------------------------------------------------------------- import

    /** 解析 ZIP 输入流为会话列表；任何容器/元数据错误抛出 ArchiveException */
    fun import(input: InputStream): List<Session> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            var first = true
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.normalize()
                    if (first) {
                        // 首个条目必须是 manifest.json
                        if (name != "manifest.json") throw ArchiveException("无法识别该文件，请选择 DeepSeek 导出的历史对话 ZIP 文件")
                        val manifest = runCatching { json.parseToJsonElement(zis.readBytes().decodeToString()).jsonObject }
                            .getOrElse { throw ArchiveException("文件不是有效的 JSON") }
                        val version = manifest.int("export_format_version")
                        when {
                            version == null -> throw ArchiveException("文件版本无效，无法导入")
                            version > VERSION -> throw ArchiveException("文件版本过新，请升级后再导入")
                            version != VERSION -> throw ArchiveException("文件版本无效，无法导入")
                        }
                        first = false
                    } else if (name == "chat_session_index.json" || name.matches(Regex("chat_sessions/[^/]+\\.json"))) {
                        entries[name] = zis.readBytes()
                    }
                    // user.json、图片原件等条目跳过
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (entries.isEmpty()) throw ArchiveException("文件内容不完整，无法导入")

        val indexBytes = entries["chat_session_index.json"]
            ?: throw ArchiveException("文件内容不完整，无法导入")
        val index = runCatching { json.parseToJsonElement(indexBytes.decodeToString()).jsonArray }
            .getOrElse { throw ArchiveException("文件不是有效的 JSON") }

        val sessions = ArrayList<Session>()
        val seenIds = HashSet<String>()
        for (item in index) {
            val meta = item as? JsonObject ?: throw ArchiveException("文件内容不完整，无法导入")
            val id = meta.str("id") ?: throw ArchiveException("文件内容不完整，无法导入")
            if (!seenIds.add(id)) throw ArchiveException("文件内容不完整，无法导入")
            val sessionBytes = entries["chat_sessions/$id.json"]
                ?: throw ArchiveException("文件内容不完整，无法导入")
            val sessionObj = runCatching { json.parseToJsonElement(sessionBytes.decodeToString()).jsonObject }
                .getOrElse { throw ArchiveException("文件不是有效的 JSON") }
            sessions += parseSession(id, meta.str("title"), sessionObj)
        }
        if (sessions.isEmpty()) throw ArchiveException("文件内容不完整，无法导入")
        return sessions
    }

    private fun String.normalize(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

    /** 线性化消息树：从 current_message_id 沿 parent_id 回溯到根，再反转 */
    private fun linearize(messages: List<JsonObject>, currentId: Int?): List<JsonObject> {
        if (messages.isEmpty()) return emptyList()
        val byId = messages.associateBy { it.int("id") ?: -1 }
        var cursor = currentId?.let { byId[it] }
        if (cursor == null) {
            // 无有效 current_message_id 时取 ID 最大的叶子
            cursor = messages.maxByOrNull { it.int("id") ?: -1 }
        }
        val chain = ArrayList<JsonObject>()
        val visited = HashSet<Int>()
        while (cursor != null) {
            val id = cursor.int("id") ?: throw ArchiveException("文件中的消息分支已损坏，无法导入")
            if (!visited.add(id)) throw ArchiveException("文件中的消息分支已损坏，无法导入")
            chain += cursor
            val parentId = cursor.int("parent_id")
            cursor = parentId?.let { byId[it] }
            if (parentId != null && cursor == null) throw ArchiveException("文件中的消息分支已损坏，无法导入")
        }
        return chain.reversed()
    }

    private fun parseSession(id: String, indexTitle: String?, obj: JsonObject): Session {
        val rawMessages = obj.arr("messages")?.mapNotNull { it as? JsonObject }
            ?: throw ArchiveException("文件内容不完整，无法导入")
        val chain = linearize(rawMessages, obj.int("current_message_id"))
        val createdAt = parseIso(obj.str("inserted_at"))
        val updatedAt = parseIso(obj.str("updated_at"))

        val messages = ArrayList<ChatMessage>()
        for ((i, m) in chain.withIndex()) {
            val role = m.str("role") ?: throw ArchiveException("文件内容不完整，无法导入")
            val insertedAt = parseIso(m.str("inserted_at"))
            val fragments = m.arr("fragments")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val msgId = "$id-$i"
            if (role == "USER") {
                val content = fragments.filter { it.str("type") == "REQUEST" }
                    .joinToString("") { it.str("content") ?: "" }
                val files = fragments.filter { it.str("type") == "FILE" }
                    .flatMap { f -> f.arr("files")?.mapNotNull { it as? JsonObject } ?: emptyList() }
                    .map { f ->
                        FileMeta(
                            id = f.str("id") ?: "",
                            name = f.str("file_name") ?: "",
                            size = f.long("file_size") ?: 0L,
                            isImage = f.bool("is_image"),
                        )
                    }
                messages += ChatMessage(
                    id = msgId, role = "user", createdAt = insertedAt,
                    content = content, files = files, status = "done",
                )
            } else {
                val blocks = ArrayList<ReplyBlock>()
                for (f in fragments) {
                    when (f.str("type")) {
                        "THINK" -> blocks += ReplyBlock.Thinking(
                            thinking = f.str("content") ?: "",
                            startedAt = null,
                            endedAt = null,
                        ).let { t ->
                            // 用 elapsed_secs 还原用时展示
                            val secs = f["elapsed_secs"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
                                ?.jsonPrimitive?.content?.toDoubleOrNull()
                            if (secs != null) t.copy(startedAt = 0L, endedAt = (secs * 1000).toLong()) else t
                        }
                        "RESPONSE" -> blocks += ReplyBlock.Text(f.str("content") ?: "")
                        "TEMPLATE_RESPONSE" -> blocks += ReplyBlock.Text(f.str("content") ?: "", isTemplate = true)
                        "TOOL_SEARCH" -> {
                            val results = f.arr("results")?.mapNotNull { it as? JsonObject }?.map { r ->
                                SearchResult(
                                    url = r.str("url") ?: "",
                                    title = r.str("title") ?: "",
                                    snippet = r.str("snippet") ?: "",
                                    siteName = r.str("site_name"),
                                )
                            } ?: emptyList()
                            blocks += ReplyBlock.Search(
                                queries = f.arr("queries")?.mapNotNull { it.jsonPrimitive.content } ?: emptyList(),
                                results = results,
                                status = f.str("status") ?: "FINISHED",
                            )
                        }
                        // TIP / CONTENT_RISK / PARTIAL_FILE_READ / TOOL_OPEN / TOOL_FIND / READ_LINK /
                        // TOOL_IMAGE_SEARCH：无对应展示或 v1 不回传，丢弃
                    }
                }
                val status = when (m.str("status")) {
                    "FINISHED" -> "done"
                    "INCOMPLETE", "WIP" -> "stopped"
                    "CONTENT_FILTER" -> "refusal"
                    else -> "error"
                }
                messages += ChatMessage(
                    id = msgId, role = "assistant", createdAt = insertedAt,
                    blocks = blocks, status = status,
                    stopReason = if (status == "refusal") "refusal" else null,
                    thinking = m.bool("thinking_enabled"),
                    search = m.bool("search_enabled"),
                    errorText = if (status == "error") "历史回复生成失败" else null,
                )
            }
        }

        val title = (obj.str("title") ?: indexTitle)?.takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.role == "user" }?.content?.take(30)?.takeIf { it.isNotBlank() }
            ?: "新对话"
        return Session(id = id, title = title, createdAt = createdAt, updatedAt = updatedAt, messages = messages)
    }

    // ---------------------------------------------------------------- export

    /** 导出为 ZIP v3；写入调用方提供的输出流（SAF），不包含 API Key 与设置 */
    fun export(sessions: List<Session>, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zos ->
            fun putEntry(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }

            putEntry("manifest.json", json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject { put("export_format_version", VERSION) },
            ).toByteArray())

            val index = buildJsonArray {
                for (s in sessions) {
                    putEntry("chat_sessions/${s.id}.json", sessionToJson(s).toByteArray())
                    addJsonObject {
                        put("id", s.id)
                        put("title", s.title.ifBlank { null as String? })
                        put("pinned", false)
                        put("message_count", s.messages.size)
                        put("inserted_at", iso(s.createdAt))
                        put("updated_at", iso(s.updatedAt))
                    }
                }
            }
            putEntry("chat_session_index.json", json.encodeToString(JsonArray.serializer(), index).toByteArray())
        }
    }

    private fun sessionToJson(s: Session): String {
        val obj = buildJsonObject {
            put("id", s.id)
            put("title", s.title.ifBlank { null as String? })
            put("pinned", false)
            put("current_message_id", (s.messages.size - 1).coerceAtLeast(0))
            put("inserted_at", iso(s.createdAt))
            put("updated_at", iso(s.updatedAt))
            putJsonArray("messages") {
                for ((i, m) in s.messages.withIndex()) {
                    addJsonObject {
                        put("id", i)
                        put("parent_id", if (i == 0) null as Int? else i - 1)
                        put("role", if (m.role == "user") "USER" else "ASSISTANT")
                        put("status", when {
                            m.role == "user" -> "FINISHED"
                            m.status == "refusal" || m.stopReason == "refusal" -> "CONTENT_FILTER"
                            m.status == "done" && m.stopReason == "max_tokens" -> "INCOMPLETE"
                            m.status == "done" -> "FINISHED"
                            m.status == "stopped" -> "INCOMPLETE"
                            m.status == "error" -> "FAILED"
                            else -> "WIP"
                        })
                        put("thinking_enabled", m.thinking)
                        put("search_enabled", m.search)
                        put("accumulated_token_usage", 0)
                        put("inserted_at", iso(m.createdAt))
                        putJsonArray("fragments") {
                            if (m.role == "user") {
                                if (m.files.isNotEmpty()) {
                                    addJsonObject {
                                        put("type", "FILE")
                                        putJsonArray("files") {
                                            for (f in m.files) {
                                                addJsonObject {
                                                    put("id", f.id)
                                                    put("file_name", f.name)
                                                    put("file_size", f.size)
                                                    put("token_usage", null as Int?)
                                                    put("inserted_at", iso(m.createdAt))
                                                    put("export_path", null as String?)
                                                    put("is_image", f.isImage)
                                                    put("width", null as Int?)
                                                    put("height", null as Int?)
                                                }
                                            }
                                        }
                                    }
                                }
                                addJsonObject { put("type", "REQUEST"); put("content", m.content) }
                            } else {
                                for (b in m.blocks) {
                                    when (b) {
                                        is ReplyBlock.Thinking -> addJsonObject {
                                            put("type", "THINK")
                                            put("content", b.thinking)
                                            val secs = if (b.startedAt != null && b.endedAt != null) {
                                                ((b.endedAt - b.startedAt) / 1000.0).coerceAtLeast(0.0)
                                            } else null
                                            put("elapsed_secs", secs)
                                        }
                                        is ReplyBlock.Text -> addJsonObject {
                                            put("type", if (b.isTemplate) "TEMPLATE_RESPONSE" else "RESPONSE")
                                            put("content", b.text)
                                        }
                                        is ReplyBlock.Search -> addJsonObject {
                                            put("type", "TOOL_SEARCH")
                                            putJsonArray("queries") { b.queries.forEach { add(it) } }
                                            put("status", b.status)
                                            putJsonArray("results") {
                                                for (r in b.results) {
                                                    addJsonObject {
                                                        put("url", r.url)
                                                        put("title", r.title)
                                                        put("snippet", r.snippet)
                                                        put("published_at", null as String?)
                                                        put("site_name", r.siteName)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    fun exportFileName(now: Date = Date()): String =
        "deepseek_data-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".zip"
}