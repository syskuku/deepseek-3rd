package com.dschat.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 与官方 ApiErrorKind 对应 */
enum class ApiErrorKind { HTTP, STREAM, NETWORK, INTERRUPTED, PROTOCOL }

class ApiError(
    val kind: ApiErrorKind,
    message: String,
    val status: Int? = null,
) : Exception(message)

/** 与官方 classifyStatus 一致的错误归类 */
enum class ApiCondition { UNAUTHORIZED, INSUFFICIENT_BALANCE, TOO_LARGE, RATE_LIMITED, SERVER_BUSY, NETWORK, OTHER }

fun classifyError(e: Throwable): ApiCondition = when {
    e !is ApiError -> ApiCondition.OTHER
    e.status == null -> if (e.kind == ApiErrorKind.NETWORK) ApiCondition.NETWORK else ApiCondition.OTHER
    e.status == 401 -> ApiCondition.UNAUTHORIZED
    e.status == 402 -> ApiCondition.INSUFFICIENT_BALANCE
    e.status == 413 -> ApiCondition.TOO_LARGE
    e.status == 429 -> ApiCondition.RATE_LIMITED
    e.status >= 500 -> ApiCondition.SERVER_BUSY
    else -> ApiCondition.OTHER
}

fun isRetryable(e: Throwable): Boolean {
    val c = classifyError(e)
    return e !is ApiError || c == ApiCondition.NETWORK || c == ApiCondition.RATE_LIMITED || c == ApiCondition.SERVER_BUSY
}

/** 流式事件（只保留客户端需要的字段） */
sealed class StreamEvent {
    data class MessageStart(val usage: JsonObject?) : StreamEvent()
    data class BlockStart(val index: Int, val block: JsonObject) : StreamEvent()
    data class BlockDelta(val index: Int, val delta: JsonObject) : StreamEvent()
    data class BlockStop(val index: Int) : StreamEvent()
    data class MessageDelta(val stopReason: String?, val title: String?, val usage: JsonObject?) : StreamEvent()
    data object MessageStop : StreamEvent()
}

/**
 * DeepSeek Anthropic 兼容接口客户端，逐行对照官方 src/services/api/client.ts。
 * - 校验 Key：GET {ORIGIN}/models（Bearer，不产生费用）
 * - 聊天：POST {ORIGIN}/anthropic/v1/messages（x-api-key，stream: true，SSE）
 */
object DeepSeekClient {
    const val ORIGIN = "https://api.deepseek.com"
    private const val ANTHROPIC_BASE = "$ORIGIN/anthropic/v1"
    const val MODEL = "deepseek-flash"
    const val SYSTEM_PROMPT = "该助手为DeepSeek，由深度求索公司创造。"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun errorFromBody(text: String, fallback: String, status: Int?, kind: ApiErrorKind): ApiError {
        var message = text.ifBlank { fallback }
        runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            val err = obj["error"]?.jsonObject
            val m = err?.get("message")?.jsonPrimitive?.content
            if (m != null) message = m
        }
        return ApiError(kind, message, status)
    }

    /** 用 GET /models 校验 API Key（不产生费用） */
    suspend fun verifyApiKey(apiKey: String) {
        val request = Request.Builder()
            .url("$ORIGIN/models")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        executeChecked(request)
    }

    private suspend fun executeChecked(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(ApiError(ApiErrorKind.NETWORK, e.message ?: "network error"))
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    if (!response.isSuccessful) {
                        val body = runCatching { response.body?.string() ?: "" }.getOrDefault("")
                        response.close()
                        cont.resumeWithException(
                            errorFromBody(body, response.message, response.code, ApiErrorKind.HTTP),
                        )
                        return
                    }
                    cont.resume(response) { _, value, _ -> value.close() }
                }
            })
        }

    /** POST /anthropic/v1/messages（stream: true），逐个产出流式事件，收到 message_stop 时结束 */
    fun streamMessage(apiKey: String, body: JsonObject): Flow<StreamEvent> = flow {
        val request = Request.Builder()
            .url("$ANTHROPIC_BASE/messages")
            .header("x-api-key", apiKey)
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val response = executeChecked(request)
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("text/event-stream")) {
            val text = runCatching { response.body?.string() ?: "" }.getOrDefault("")
            response.close()
            throw errorFromBody(text, "", response.code, ApiErrorKind.PROTOCOL)
        }

        response.use { resp ->
            val source = resp.body?.source() ?: throw ApiError(ApiErrorKind.PROTOCOL, "empty body")
            var eventName = ""
            val data = StringBuilder()
            var hasData = false
            var stopped = false

            fun dispatch(): StreamEvent? {
                if (!hasData) { eventName = ""; return null }
                val payload = data.toString()
                data.setLength(0)
                hasData = false
                val name = eventName
                eventName = ""

                var parsed: JsonObject? = null
                runCatching { parsed = json.parseToJsonElement(payload).jsonObject }
                val errMessage = parsed?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.content
                if (errMessage != null) throw ApiError(ApiErrorKind.STREAM, errMessage)
                if (name == "error") throw ApiError(ApiErrorKind.STREAM, payload)
                val obj = parsed ?: return null

                return when (obj["type"]?.jsonPrimitive?.content) {
                    "message_start" -> StreamEvent.MessageStart(obj["message"]?.jsonObject?.get("usage") as? JsonObject)
                    "content_block_start" -> {
                        val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
                        val block = obj["content_block"] as? JsonObject ?: return null
                        StreamEvent.BlockStart(index, block)
                    }
                    "content_block_delta" -> {
                        val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
                        val delta = obj["delta"] as? JsonObject ?: return null
                        StreamEvent.BlockDelta(index, delta)
                    }
                    "content_block_stop" -> {
                        val index = obj["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
                        StreamEvent.BlockStop(index)
                    }
                    "message_delta" -> {
                        val delta = obj["delta"]?.jsonObject
                        StreamEvent.MessageDelta(
                            stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content,
                            title = delta?.get("title")?.jsonPrimitive?.content,
                            usage = obj["usage"] as? JsonObject,
                        )
                    }
                    "message_stop" -> StreamEvent.MessageStop
                    else -> null
                }
            }

            while (!stopped) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.isEmpty() -> {
                        val event = dispatch()
                        if (event != null) {
                            emit(event)
                            if (event is StreamEvent.MessageStop) { stopped = true }
                        }
                    }
                    line.startsWith(":") -> Unit // 保活注释
                    else -> {
                        val colon = line.indexOf(':')
                        val field = if (colon == -1) line else line.substring(0, colon)
                        var value = if (colon == -1) "" else line.substring(colon + 1)
                        if (value.startsWith(" ")) value = value.substring(1)
                        when (field) {
                            "event" -> eventName = value
                            "data" -> {
                                if (hasData) data.append('\n')
                                data.append(value)
                                hasData = true
                            }
                        }
                    }
                }
            }
            if (!stopped) {
                // 处理末尾无空行的最后一帧
                val event = dispatch()
                if (event != null) emit(event)
                if (event !is StreamEvent.MessageStop) throw ApiError(ApiErrorKind.INTERRUPTED, "")
            }
        }
    }.flowOn(Dispatchers.IO)
}