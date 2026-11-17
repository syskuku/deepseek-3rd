package com.dschat.app.net

import com.dschat.app.data.ChatMessage
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

    fun latestReminder(now: Date = Date()): String {
        val date = SimpleDateFormat("yyyy/M/d", Locale.getDefault()).format(now)
        val weekday = SimpleDateFormat("EEEE", Locale.CHINESE).format(now)
        return listOf(date, weekday, "", "Android", "中文").joinToString(",")
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

    /**
     * @param history 当前消息之前的全部消息
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
                if (user.content.isNotBlank()) {
                    addJsonObject { put("role", "user"); put("content", user.content) }
                    addJsonObject { put("role", "assistant"); put("content", assistantContent(reply)) }
                }
                i += 2
            } else {
                i += 1
            }
        }
        addJsonObject { put("role", "latest_reminder"); put("content", latestReminder()) }
        addJsonObject { put("role", "user"); put("content", question.content) }
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