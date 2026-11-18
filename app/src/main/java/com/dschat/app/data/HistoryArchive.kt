package com.dschat.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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
 * DeepSeek 历史对话 ZIP（export_format_version: 3）。
 * 对照官方 history/README.md、toLocal.ts、toArchive.ts：
 * - 完整消息树导入/导出（parent_id 分支、current_message_id 当前分支）；
 * - 图片原件随导入提取、随导出附带（FILE.export_path 匹配）；
 * - 父 id 必须小于子 id、角色交替、根为提问；其余节点默认选中 id 最大的子消息。
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
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.int(key: String): Int? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toIntOrNull()

    private fun JsonObject.long(key: String): Long? =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toBoolean() ?: false

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    private fun String.nfc(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFC)

    // ---------------------------------------------------------------- import

    /**
     * @param openStream 可重复打开的 ZIP 流（SAF uri 可多次 openInputStream）
     * @param saveImage  (ZIP 条目名, 字节) -> 本地相对路径
     */
    fun import(openStream: () -> InputStream, saveImage: (String, ByteArray) -> String): List<Session> {
        val sessionJsons = LinkedHashMap<String, ByteArray>()
        var indexBytes: ByteArray? = null
        val referencedImages = HashSet<String>()

        // 第一遍：校验 manifest、收集会话 JSON 与索引、登记被引用的图片条目
        openStream().use { raw ->
            ZipInputStream(raw.buffered()).use { zis ->
                var entry = zis.nextEntry
                var first = true
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = entry.name.nfc()
                        if (first) {
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
                        } else when {
                            name == "chat_session_index.json" -> indexBytes = zis.readBytes()
                            name.matches(Regex("chat_sessions/[^/]+\\.json")) -> {
                                val bytes = zis.readBytes()
                                sessionJsons[name] = bytes
                                // 登记 FILE 片段引用的图片路径
                                runCatching {
                                    val obj = json.parseToJsonElement(bytes.decodeToString()).jsonObject
                                    obj.arr("messages")?.forEach { m ->
                                        (m as? JsonObject)?.arr("fragments")?.forEach { f ->
                                            val fo = f as? JsonObject ?: return@forEach
                                            if (fo.str("type") == "FILE") {
                                                fo.arr("files")?.forEach { file ->
                                                    val fo2 = file as? JsonObject ?: return@forEach
                                                    if (fo2.bool("is_image")) {
                                                        fo2.str("export_path")?.let { referencedImages += it.nfc() }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        if (sessionJsons.isEmpty()) throw ArchiveException("文件内容不完整，无法导入")
        val index = runCatching { json.parseToJsonElement((indexBytes ?: throw ArchiveException("文件内容不完整，无法导入")).decodeToString()).jsonArray }
            .getOrElse { throw ArchiveException("文件不是有效的 JSON") }

        // 第二遍：提取被引用的图片原件
        val imagePaths = HashMap<String, String>()
        if (referencedImages.isNotEmpty()) {
            openStream().use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.nfc()
                            if (name in referencedImages) {
                                imagePaths[name] = saveImage(name, zis.readBytes())
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
        }

        // 解析会话（索引与会话一一对应）
        val sessions = ArrayList<Session>()
        val seen = HashSet<String>()
        for (item in index) {
            val meta = item as? JsonObject ?: throw ArchiveException("文件内容不完整，无法导入")
            val id = meta.str("id") ?: throw ArchiveException("文件内容不完整，无法导入")
            if (!seen.add(id)) throw ArchiveException("文件内容不完整，无法导入")
            val bytes = sessionJsons["chat_sessions/$id.json"] ?: throw ArchiveException("文件内容不完整，无法导入")
            val obj = runCatching { json.parseToJsonElement(bytes.decodeToString()).jsonObject }
                .getOrElse { throw ArchiveException("文件不是有效的 JSON") }
            sessions += parseSession(id, meta.str("title"), obj, imagePaths)
        }
        if (sessions.isEmpty()) throw ArchiveException("文件内容不完整，无法导入")
        return sessions
    }

    /** 对照官方 toLocal.buildTree：还原完整消息树与当前分支 */
    private fun parseSession(id: String, indexTitle: String?, obj: JsonObject, imagePaths: Map<String, String>): Session {
        val raw = obj.arr("messages")?.mapNotNull { it as? JsonObject }
            ?: throw ArchiveException("文件内容不完整，无法导入")
        val sorted = raw.sortedBy { it.int("id") ?: Int.MAX_VALUE }

        data class Node(val local: ChatMessage, val archiveId: Int, val parentArchiveId: Int?)

        val nodes = ArrayList<Node>()
        val byArchive = HashMap<Int, Node>()
        for (m in sorted) {
            val aid = m.int("id") ?: throw ArchiveException("文件中的消息分支已损坏，无法导入")
            val pid = m.int("parent_id")
            val role = m.str("role") ?: throw ArchiveException("文件内容不完整，无法导入")
            val parent = pid?.let { byArchive[it] }
            if (byArchive.containsKey(aid) ||
                (pid == null && role != "USER") ||
                (pid != null && parent == null) ||
                (parent != null && parent.local.role == (if (role == "USER") "user" else "assistant")) ||
                (parent != null && parent.archiveId >= aid)
            ) {
                throw ArchiveException("文件中的消息分支已损坏，无法导入（id=$aid parent=$pid role=$role parentRole=${parent?.local?.role}）")
            }
            val localId = "$id-$aid"
            val insertedAt = parseIso(m.str("inserted_at"))
            val fragments = m.arr("fragments")?.mapNotNull { it as? JsonObject } ?: emptyList()

            val local = if (role == "USER") {
                val content = fragments.filter { it.str("type") == "REQUEST" }.joinToString("") { it.str("content") ?: "" }
                val files = fragments.filter { it.str("type") == "FILE" }
                    .flatMap { f -> f.arr("files")?.mapNotNull { it as? JsonObject } ?: emptyList() }
                    .map { f ->
                        val exportPath = f.str("export_path")?.nfc()
                        FileMeta(
                            id = f.str("id") ?: "",
                            name = f.str("file_name") ?: "",
                            size = f.long("file_size") ?: 0L,
                            isImage = f.bool("is_image"),
                            localPath = exportPath?.let { imagePaths[it] },
                        )
                    }
                ChatMessage(localId, "user", insertedAt, content = content, files = files, status = "done")
            } else {
                val blocks = ArrayList<ReplyBlock>()
                for (f in fragments) {
                    when (f.str("type")) {
                        "THINK" -> {
                            val secs = f["elapsed_secs"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toDoubleOrNull()
                            blocks += ReplyBlock.Thinking(
                                thinking = f.str("content") ?: "",
                                startedAt = if (secs != null) 0L else null,
                                endedAt = if (secs != null) (secs * 1000).toLong() else null,
                            )
                        }
                        "RESPONSE" -> blocks += ReplyBlock.Text(f.str("content") ?: "")
                        "TEMPLATE_RESPONSE" -> blocks += ReplyBlock.Text(f.str("content") ?: "", isTemplate = true)
                        "TOOL_SEARCH" -> {
                            val results = f.arr("results")?.mapNotNull { it as? JsonObject }?.map { r ->
                                SearchResult(r.str("url") ?: "", r.str("title") ?: "", r.str("snippet") ?: "", r.str("site_name"))
                            } ?: emptyList()
                            blocks += ReplyBlock.Search(
                                queries = f.arr("queries")?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: emptyList(),
                                results = results,
                                status = f.str("status") ?: "FINISHED",
                            )
                        }
                    }
                }
                val status = when (m.str("status")) {
                    "FINISHED" -> "done"
                    "INCOMPLETE", "WIP" -> "stopped"
                    "CONTENT_FILTER" -> "refusal"
                    else -> "error"
                }
                ChatMessage(
                    localId, "assistant", insertedAt, blocks = blocks, status = status,
                    stopReason = if (status == "refusal") "refusal" else null,
                    thinking = m.bool("thinking_enabled"), search = m.bool("search_enabled"),
                    errorText = if (status == "error") "历史回复生成失败" else null,
                )
            }
            val node = Node(local.copy(parentId = parent?.local?.id), aid, pid)
            nodes += node
            byArchive[aid] = node
        }
        if (nodes.isEmpty()) throw ArchiveException("文件内容不完整，无法导入")

        // 默认选中 id 最大的子消息（sorted 顺序遍历自然覆盖）
        val selected = HashMap<String, String?>()
        for (n in nodes) {
            if (n.parentArchiveId != null) {
                val p = byArchive[n.parentArchiveId]!!
                selected[p.local.id] = n.local.id
            }
        }
        // current_message_id 路径覆盖
        val currentId = obj.int("current_message_id")
        var current = currentId?.let { byArchive[it] } ?: throw ArchiveException("文件中的消息分支已损坏，无法导入")
        selected[current.local.id] = null
        while (current.parentArchiveId != null) {
            val p = byArchive[current.parentArchiveId] ?: throw ArchiveException("文件中的消息分支已损坏，无法导入")
            selected[p.local.id] = current.local.id
            current = p
        }
        val rootId = current.local.id

        val messages = nodes.map { it.local.copy(selectedChildId = selected[it.local.id]) }
        val title = (obj.str("title") ?: indexTitle)?.takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.role == "user" }?.content?.take(30)?.takeIf { it.isNotBlank() }
            ?: "新对话"
        return Session(id, title, parseIso(obj.str("inserted_at")), parseIso(obj.str("updated_at")), messages, rootId)
    }

    // ---------------------------------------------------------------- export

    /**
     * 导出完整消息树与当前分支；图片原件由 readImage 提供（无原件仅元数据）。
     * 不含 API Key 与设置。
     */
    fun export(sessions: List<Session>, readImage: (FileMeta) -> ByteArray?, output: OutputStream) {
        ZipOutputStream(output.buffered()).use { zos ->
            fun putEntry(name: String, bytes: ByteArray) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }

            putEntry("manifest.json", """{"export_format_version":$VERSION}""".toByteArray())

            val imageEntries = LinkedHashMap<String, ByteArray>()
            val index = buildJsonArray {
                for (s0 in sessions) {
                    val s = s0.normalized()
                    val ordered = s.messages.sortedWith(compareBy({ it.createdAt }, { it.id }))
                    val archiveId = HashMap<String, Int>()
                    ordered.forEachIndexed { i, m -> archiveId[m.id] = i }
                    val visible = s.visibleMessages()
                    val currentArchiveId = visible.lastOrNull()?.let { archiveId[it.id] } ?: 0

                    val obj = buildJsonObject {
                        put("id", s.id)
                        put("title", s.title.ifBlank { null as String? })
                        put("pinned", false)
                        put("current_message_id", currentArchiveId)
                        put("inserted_at", iso(s.createdAt))
                        put("updated_at", iso(s.updatedAt))
                        putJsonArray("messages") {
                            for (m in ordered) {
                                addJsonObject {
                                    put("id", archiveId[m.id])
                                    put("parent_id", m.parentId?.let { archiveId[it] })
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
                                                            val bytes = if (f.isImage) readImage(f) else null
                                                            val exportPath = if (bytes != null) {
                                                                val p = "chat_sessions/${s.id}_files/${f.id}_${f.name.replace(Regex("[/\\\\]"), "_")}"
                                                                imageEntries.putIfAbsent(p, bytes)
                                                                p
                                                            } else null
                                                            addJsonObject {
                                                                put("id", f.id)
                                                                put("file_name", f.name)
                                                                put("file_size", f.size)
                                                                put("token_usage", null as Int?)
                                                                put("inserted_at", iso(m.createdAt))
                                                                put("export_path", exportPath)
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
                                                        put("elapsed_secs", if (b.startedAt != null && b.endedAt != null) ((b.endedAt - b.startedAt) / 1000.0).coerceAtLeast(0.0) else null)
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
                                                                    put("url", r.url); put("title", r.title); put("snippet", r.snippet)
                                                                    put("published_at", null as String?); put("site_name", r.siteName)
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
                    putEntry("chat_sessions/${s.id}.json", json.encodeToString(JsonObject.serializer(), obj).toByteArray())
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
            for ((name, bytes) in imageEntries) putEntry(name, bytes)
        }
    }

    fun exportFileName(now: Date = Date()): String =
        "deepseek_data-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".zip"
}