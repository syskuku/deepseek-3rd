package com.dschat.app.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 应用内更新：查询 GitHub 最新 Release 并下载 APK。
 * 仓库：syskuku/deepseek-3rd。api.github.com 通常可达；
 * 下载若直连失败自动尝试镜像代理。
 */
object UpdateChecker {
    private const val API = "https://api.github.com/repos/syskuku/deepseek-3rd/releases/latest"
    /** 国内对象存储更新清单（七牛 CDN），检查更新时优先于 GitHub */
    private const val CLOUD_MANIFEST = "https://kodo.imikufans.com/ds.imikufans.com/ds3rd/update.json"

    data class UpdateInfo(
        val tag: String,
        val version: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        /** 云直链被 CDN 人机验证拦截时的 GitHub 兜底下载地址 */
        val fallbackUrl: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 拉取最新版本：优先国内对象存储 manifest，失败再回退 GitHub Releases */
    suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        fetchCloud() ?: fetchGitHub()
    }

    /** 国内云 manifest（七牛 CDN），对境内用户更友好 */
    private fun fetchCloud(): UpdateInfo? = try {
        val request = Request.Builder().url(CLOUD_MANIFEST).get().build()
        val quick = client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        quick.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val obj = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
            val version = obj["version"]?.jsonPrimitive?.content ?: return null
            val url = obj["url"]?.jsonPrimitive?.content ?: return null
            UpdateInfo(
                tag = "v$version",
                version = version,
                notes = obj["notes"]?.jsonPrimitive?.content ?: "",
                apkUrl = url,
                apkSize = obj["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                fallbackUrl = "https://github.com/syskuku/deepseek-3rd/releases/download/v$version/DeepSeek-3rd-v$version.apk",
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchGitHub(): UpdateInfo? {
        val request = Request.Builder().url(API).header("Accept", "application/vnd.github+json").get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val obj = json.parseToJsonElement(resp.body?.string() ?: "").jsonObject
            val tag = obj["tag_name"]?.jsonPrimitive?.content ?: return null
            val notes = obj["body"]?.jsonPrimitive?.content ?: ""
            val asset = obj["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true }
                ?: return null
            return UpdateInfo(
                tag = tag,
                version = tag.removePrefix("v"),
                notes = notes,
                apkUrl = asset["browser_download_url"]?.jsonPrimitive?.content ?: return null,
                apkSize = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** 语义化版本比较 x.y.z */
    fun isNewer(remote: String, current: String): Boolean {
        fun parse(v: String): List<Int> = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val r = parse(remote)
        val c = parse(current)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** 下载 APK 到 cacheDir/update.apk，直连失败尝试镜像；onProgress 0..1 */
    /** 下载 APK：先试主地址（云），失败降级 GitHub 及其镜像；onProgress 0..1 */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val candidates = ArrayList<String>()
            candidates += info.apkUrl
            if (info.apkUrl.contains("github.com")) {
                candidates += "https://gh-proxy.com/${info.apkUrl}"
                candidates += "https://ghfast.top/${info.apkUrl}"
            } else if (info.fallbackUrl != null) {
                // 云直链可能被 CDN 人机验证拦截，降级到 GitHub 直链与镜像
                candidates += info.fallbackUrl
                candidates += "https://gh-proxy.com/${info.fallbackUrl}"
                candidates += "https://ghfast.top/${info.fallbackUrl}"
            }
            var lastError: Exception? = null
            for (candidate in candidates) {
                try {
                    return@withContext downloadFrom(context, candidate, onProgress)
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("下载失败")
        }

    private fun downloadFrom(context: Context, url: String, onProgress: (Float) -> Unit): File {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .get().build()
        val dest = File(context.cacheDir, "update.apk")
        val tmp = File(context.cacheDir, "update.apk.tmp")
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            tmp.outputStream().use { out ->
                val buffer = ByteArray(8192)
                var readTotal = 0L
                while (true) {
                    val n = body.byteStream().read(buffer)
                    if (n == -1) break
                    out.write(buffer, 0, n)
                    readTotal += n
                    if (total > 0) onProgress((readTotal.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            if (total > 0 && tmp.length() != total) throw IOException("文件不完整")
            // 简单校验 ZIP 魔数（APK 是 ZIP）
            tmp.inputStream().use { input ->
                val magic = ByteArray(2)
                if (input.read(magic) != 2 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                    throw IOException("下载内容不是有效的 APK")
                }
            }
            tmp.renameTo(dest)
            onProgress(1f)
            return dest
        }
    }
}