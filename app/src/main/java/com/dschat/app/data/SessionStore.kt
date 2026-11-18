package com.dschat.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 会话持久化：每个会话一个 JSON 文件（filesDir/sessions/{id}.json），
 * 对应官方 demo 的 IndexedDB 存储。导入时整体替换。
 */
class SessionStore(private val context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    private fun fileOf(id: String) = File(dir, "$id.json")

    /** 保存附件原件，返回相对 filesDir 的路径 */
    fun saveAttachment(sessionId: String, fileId: String, name: String, bytes: ByteArray): String {
        val safeName = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "file" }
        val rel = "attachments/$sessionId/${fileId}_$safeName"
        val target = File(context.filesDir, rel)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return rel
    }

    fun attachmentFile(relPath: String): File = File(context.filesDir, relPath)

    /** 删除会话时清理其附件目录 */
    private fun deleteAttachments(sessionId: String) {
        File(attachmentsDir, sessionId).deleteRecursively()
    }

    /** 导入前清理旧附件（导入会写入新原件；replaceAll 不再触碰附件） */
    fun clearAttachments() {
        attachmentsDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    suspend fun listMetas(): List<SessionMeta> = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles { f -> f.extension == "json" }
                ?.mapNotNull { f ->
                    runCatching {
                        val s = json.decodeFromString<Session>(f.readText())
                        s.meta()
                    }.getOrNull()
                }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        }
    }

    suspend fun load(id: String): Session? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { json.decodeFromString<Session>(fileOf(id).readText()) }.getOrNull()
        }
    }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(dir, "${session.id}.json.tmp")
            tmp.writeText(json.encodeToString(Session.serializer(), session))
            tmp.renameTo(fileOf(session.id))
            Unit
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock { fileOf(id).delete(); deleteAttachments(id); Unit }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dir.listFiles()?.forEach { it.delete() }
            attachmentsDir.listFiles()?.forEach { it.deleteRecursively() }
            Unit
        }
    }

    /** 导入：校验完成后整体替换（调用方已解析好全部会话） */
    suspend fun replaceAll(sessions: List<Session>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val staging = File(dir.parentFile, "sessions_staging").apply { deleteRecursively(); mkdirs() }
            for (s in sessions) {
                File(staging, "${s.id}.json").writeText(json.encodeToString(Session.serializer(), s))
            }
            dir.listFiles()?.forEach { it.delete() }
            staging.listFiles()?.forEach { it.renameTo(File(dir, it.name)) }
            staging.deleteRecursively()
        }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}