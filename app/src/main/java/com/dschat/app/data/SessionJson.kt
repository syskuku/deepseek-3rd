package com.dschat.app.data

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 单个对话导出/导入 JSON（含附件 base64， portable 单文件）。
 * 导出：{ format, version, session, attachments:{relPath:base64} }
 * 导入：合并为新会话（新 id），附件落盘、serverId 置空待首次发送补传。
 */
object SessionJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    const val FORMAT = "ds3rd-session"

    fun export(session: Session, readBytes: (String) -> ByteArray?): String {
        val att = buildJsonObject {
            session.messages.forEach { m ->
                m.files.forEach { f ->
                    val p = f.localPath ?: return@forEach
                    val bytes = readBytes(p) ?: return@forEach
                    put(p, Base64.encodeToString(bytes, Base64.NO_WRAP))
                }
            }
        }
        val root = buildJsonObject {
            put("format", FORMAT)
            put("version", 1)
            put("session", json.encodeToJsonElement(Session.serializer(), session))
            put("attachments", att)
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /** 解析并落盘附件，返回合并用的新会话（newId）。saveAttachment 返回新相对路径。 */
    fun import(text: String, newId: String, saveAttachment: (fileId: String, name: String, bytes: ByteArray) -> String): Session {
        val root = json.parseToJsonElement(text).jsonObject
        val fmt = root["format"]?.jsonPrimitive?.content
        if (fmt != FORMAT) throw IllegalArgumentException("不是有效的单个对话 JSON（format=$fmt）")
        val session = json.decodeFromJsonElement(Session.serializer(), root["session"]!!.jsonObject)
        val att = root["attachments"]?.jsonObject ?: JsonObject(emptyMap())
        val remap = HashMap<String, String>()
        val newMessages = session.messages.map { m ->
            // 导出时正在生成的消息可能停在 pending/streaming；导入后不可能继续流式，归一为「已中断」，
            // 否则界面会一直按流式渲染（不能重新生成/继续，且滚动高度反复变化）。
            val status = if (m.status == "pending" || m.status == "streaming") "stopped" else m.status
            m.copy(status = status, files = m.files.map { f ->
                val p = f.localPath
                if (p != null && att.containsKey(p)) {
                    val newPath = remap.getOrPut(p) {
                        val bytes = Base64.decode(att[p]!!.jsonPrimitive.content, Base64.NO_WRAP)
                        saveAttachment(f.id, f.name, bytes)
                    }
                    f.copy(localPath = newPath, serverId = null)
                } else f.copy(serverId = null)
            })
        }
        return session.copy(id = newId, messages = newMessages)
    }
}