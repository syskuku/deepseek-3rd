package com.dschat.app.net

import com.dschat.app.data.ChatMessage
import com.dschat.app.data.FileMeta
import com.dschat.app.data.ReplyBlock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 请求构建，对照官方 src/services/chat/reply.ts 与 store.ts 中的请求体：
 * - 只保留「提问 + 可用回答」成对轮次进入上下文；
 * - 历史思考、搜索工具块不回传（官方仅回传 read_link 签名，本移植版不回传工具块）；
 * - 相邻文本合并，被思考/工具隔开的文本用空行连接；
 * - latest_reminder：日期,星期,位置,平台,语言。
 */
object RequestBuilder {

    /**
     * 服务端约定格式：<date>,<weekday>,<location>,<platform>,<language>
     * date 必须 YYYY-MM-DD；weekday 用提醒语言（中文）；location 可空；
     * platform 仅接受 App / Web；language 为受支持的语言本名。
     */
    fun latestReminder(now: Date = Date()): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val weekday = SimpleDateFormat("EEEE", Locale.CHINESE).format(now)
        return listOf(date, weekday, "", "App", "中文").joinToString(",")
    }

    /** 助手历史 → content 块：仅文本，相邻合并、隔开的用空行连接 */
    private fun assistantContent(message: ChatMessage): JsonArray = buildJsonArray {
        var pendingText: StringBuilder? = null
        var lastWasText = false
        for (block in message.blocks) {
            when (block) {
                is ReplyBlock.Text -> {
                    if (block.isTemplate) continue
                    if (pendingText == null) {
                        pendingText = StringBuilder()
                        if (lastWasText) pendingText.append("\n\n")
                    }
                    pendingText.append(block.text)
                    lastWasText = true
                }
                else -> {
                    if (pendingText != null) {
                        val t = pendingText.toString()
                        if (t.isNotEmpty()) addJsonObject { put("type", "text"); put("text", t) }
                        pendingText = null
                    }
                    lastWasText = false
                }
            }
        }
        if (pendingText != null) {
            val t = pendingText.toString()
            if (t.isNotEmpty()) addJsonObject { put("type", "text"); put("text", t) }
        }
    }

    /** 续写前缀：保留本轮非空白思考（开启思考时）与正文 */
    private fun prefixContent(message: ChatMessage, thinking: Boolean): JsonArray = buildJsonArray {
        for (block in message.blocks) {
            when {
                block is ReplyBlock.Thinking && thinking && block.thinking.isNotBlank() ->
                    addJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking)
                        put("signature", block.signature ?: "")
                    }
                block is ReplyBlock.Text && !block.isTemplate && block.text.isNotEmpty() ->
                    addJsonObject { put("type", "text"); put("text", block.text) }
            }
        }
    }

    /** 用户消息：有已上传文件时用内容块数组（先文件后文本），否则纯字符串 */
    private fun userContent(message: ChatMessage): kotlinx.serialization.json.JsonElement {
        val fileBlocks = message.files.filter { it.serverId != null }.map { f ->
            buildJsonObject {
                put("type", if (f.isImage) "image" else "document")
                putJsonObject("source") { put("type", "file"); put("file_id", f.serverId) }
            }
        }
        return if (fileBlocks.isEmpty()) {
            kotlinx.serialization.json.JsonPrimitive(message.content)
        } else {
            buildJsonArray {
                fileBlocks.forEach { add(it) }
                if (message.content.isNotEmpty()) addJsonObject { put("type", "text"); put("text", message.content) }
            }
        }
    }

    /** 请求中是否引用了 Files API 文件（决定 beta 头） */
    fun hasFileBlocks(history: List<ChatMessage>, question: ChatMessage): Boolean =
        (history + question).any { m -> m.role == "user" && m.files.any { it.serverId != null } }

    /**
     * @param history 当前消息之前的全部消息（当前分支）
     * @param question 本轮提问
     * @param prefix 续写时的末尾 assistant（可空）
     */
    fun toRequestMessages(
        history: List<ChatMessage>,
        question: ChatMessage,
        prefix: ChatMessage? = null,
        thinking: Boolean = false,
    ): JsonArray = buildJsonArray {
        var i = 0
        while (i + 1 < history.size) {
            val user = history[i]
            val reply = history[i + 1]
            if (user.role == "user" && reply.isUsableReply) {
                val hasText = user.content.isNotBlank()
                val hasFiles = user.files.any { it.serverId != null }
                if (hasText || hasFiles) {
                    addJsonObject { put("role", "user"); put("content", userContent(user)) }
                    addJsonObject { put("role", "assistant"); put("content", assistantContent(reply)) }
                }
                i += 2
            } else {
                i += 1
            }
        }
        addJsonObject { put("role", "latest_reminder"); put("content", latestReminder()) }
        addJsonObject { put("role", "user"); put("content", userContent(question)) }
        if (prefix != null) {
            val content = prefixContent(prefix, thinking)
            val hasContent = content.any { el ->
                val o = el as JsonObject
                when (o["type"]?.toString()) {
                    "\"text\"" -> (o["text"]?.toString() ?: "").trim('"', ' ').isNotEmpty()
                    "\"thinking\"" -> (o["thinking"]?.toString() ?: "").trim('"', ' ').isNotEmpty()
                    else -> false
                }
            }
            if (hasContent) addJsonObject { put("role", "assistant"); put("content", content) }
        }
    }

    /** 完整请求体，对照官方 store.ts：model/system/messages/thinking/safety_check/tools/title */
    fun buildBody(
        messages: JsonArray,
        thinking: Boolean,
        search: Boolean,
        requestTitle: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", DeepSeekClient.MODEL)
        put("system", DeepSeekClient.SYSTEM_PROMPT)
        put("messages", messages)
        putJsonObject("thinking") { put("type", if (thinking) "enabled" else "disabled") }
        putJsonObject("safety_check") { put("level", "high") }
        putJsonArray("tools") {
            addJsonObject { put("type", "deepseek_read_url"); put("name", "deepseek_read_url") }
            if (search) addJsonObject { put("type", "deepseek_web_search"); put("name", "deepseek_web_search") }
        }
        if (requestTitle) putJsonObject("title") { put("enabled", true) }
        put("stream", true)
    }
}