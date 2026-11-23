package com.dschat.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 全量应用日志（除 API Key 外一切可记）：精确到毫秒的动作、token 用量、工具调用、缓存命中。
 * 单文件滚动，总量 ≤2MB（超出裁掉最旧）；导出仅取近两天；设置可清除。
 * 行格式: epochMs\t人类时间\t[tag]\t消息
 */
object AppLog {
    private const val MAX_BYTES = 2L * 1024 * 1024
    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun init(context: Context) {
        file = File(context.filesDir, "logs/app.log")
        file.parentFile?.mkdirs()
        if (!file.exists()) runCatching { file.createNewFile() }
        log("log", "AppLog init, version=${com.dschat.app.BuildConfig.VERSION_NAME}")
    }

    fun log(tag: String, msg: String) {
        if (!::file.isInitialized) return
        val now = System.currentTimeMillis()
        val line = "$now\t${fmt.format(Date(now))}\t[$tag]\t${msg.replace('\n', ' ')}\n"
        io.execute {
            synchronized(lock) {
                runCatching {
                    file.appendText(line)
                    trim()
                }
            }
        }
    }

    private fun trim() {
        val len = file.length()
        if (len <= MAX_BYTES) return
        val bytes = file.readBytes()
        var start = (bytes.size - (MAX_BYTES * 3 / 4)).toInt()
        // 对齐到行边界
        while (start < bytes.size && bytes[start - 1].toInt().toChar() != '\n') start++
        file.writeBytes(bytes.copyOfRange(start, bytes.size))
    }

    /** 读取近 days 天日志（人类可读） */
    fun readRecent(days: Int = 2): String {
        if (!::file.isInitialized) return ""
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        synchronized(lock) {
            return runCatching {
                file.readLines().mapNotNull { line ->
                    val ep = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
                    if (ep < cutoff) null else line.substringAfter('\t')
                }.joinToString("\n")
            }.getOrDefault("")
        }
    }

    fun sizeBytes(): Long = if (::file.isInitialized) file.length() else 0

    fun clear() {
        if (!::file.isInitialized) return
        io.execute { synchronized(lock) { runCatching { file.writeText("") } } }
        log("log", "logs cleared by user")
    }
}