package com.dschat.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 语音接口，对照官方 docs 与 demo speech.ts：
 * - ASR：POST /v1/tools/asr，multipart 上传 16-bit PCM WAV，一次返回全文
 * - TTS：POST /v1/tools/tts，format=pcm，返回 24kHz 单声道 16-bit 小端裸 PCM 流
 * 鉴权均用 Authorization: Bearer。
 */
object SpeechApi {
    private const val TOOLS_BASE = "${DeepSeekClient.ORIGIN}/v1/tools"
    const val TTS_SAMPLE_RATE = 24000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 语音识别：返回转写文本 */
    suspend fun transcribe(apiKey: String, wav: ByteArray): String {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "recording.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$TOOLS_BASE/asr")
            .header("Authorization", "Bearer $apiKey")
            .post(form)
            .build()
        val resp = execute(request)
        val text = resp.use { it.body?.string() ?: "" }
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw ApiError(ApiErrorKind.PROTOCOL, text)
        return obj["text"]?.jsonPrimitive?.content
            ?: throw ApiError(ApiErrorKind.PROTOCOL, text)
    }

    /** 语音合成：逐块产出裸 PCM 字节 */
    fun synthesize(apiKey: String, text: String, voice: String): Flow<ByteArray> = flow {
        val body = buildJsonObject {
            put("text", text)
            put("voice_id", voice)
            put("format", "pcm")
            put("filter_markdown", true)
        }
        val request = Request.Builder()
            .url("$TOOLS_BASE/tts")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()
        val resp = execute(request)
        resp.use { r ->
            val source = r.body?.source() ?: throw ApiError(ApiErrorKind.PROTOCOL, "empty body")
            val buffer = okio.Buffer()
            while (true) {
                val read = source.read(buffer, 8192)
                if (read == -1L) break
                emit(buffer.readByteArray())
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun execute(request: Request): okhttp3.Response =
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
                        val msg = runCatching {
                            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                        }.getOrNull() ?: body.ifBlank { "HTTP ${response.code}" }
                        cont.resumeWithException(ApiError(ApiErrorKind.HTTP, msg, response.code))
                        return
                    }
                    cont.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
}