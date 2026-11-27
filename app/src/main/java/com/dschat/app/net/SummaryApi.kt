package com.dschat.app.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 用同一个模型把较早的对话压缩成摘要（一次性、流式收集正文）。
 * 失败由调用方兜底：压缩失败时按原样发送，不修改会话。
 */
object SummaryApi {

    const val PROMPT = """你是对话上下文压缩助手。请把下面这段「用户与助手的历史对话」压缩成一段简洁、客观的中文摘要。

要求：
1. 保留：对话目标与背景、关键事实与数据、已确认的结论或决定、重要约束与偏好、尚未完成的事项。
2. 删除：寒暄、重复内容、思考过程、搜索结果列表、无关细节。
3. 不要编造对话中没有的信息，不要添加评论或建议。
4. 直接输出摘要正文（可以用短标题和项目符号），不要写「以下是摘要」之类的前言。
5. 摘要会替代原文继续参与后续对话，信息要尽量完整：篇幅上限 18000 字，请充分展开，不要为了简短丢信息。"""

    suspend fun summarize(apiKey: String, transcript: String): String {
        val body: JsonObject = buildJsonObject {
            put("model", DeepSeekClient.MODEL)
            putJsonArray("system") {
                addJsonObject {
                    put("type", "text")
                    put("text", PROMPT)
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", "以下是需要压缩的历史对话：\n\n$transcript")
                }
            }
            putJsonObject("thinking") { put("type", "disabled") }
            put("stream", true)
        }
        val sb = StringBuilder()
        DeepSeekClient.streamMessage(apiKey, body).collect { event ->
            if (event is StreamEvent.BlockDelta) {
                val delta = event.delta
                if (delta["type"]?.jsonPrimitive?.contentOrNull == "text_delta") {
                    sb.append(delta["text"]?.jsonPrimitive?.contentOrNull ?: "")
                }
            }
        }
        return sb.toString().trim()
    }
}
