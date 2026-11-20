package com.dschat.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.dschat.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 问题反馈：收集应用日志 + 设备信息，一键邮件发送给维护者，或导出日志文件。
 */
object FeedbackHelper {
    const val FEEDBACK_EMAIL = "syskuku@imikufans.com"

    fun collectReport(context: Context): String {
        val sb = StringBuilder()
        sb.append("DeepSeek(3rd) 反馈报告\n")
        sb.append("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        sb.append("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n")
        sb.append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        sb.append("---- 应用日志 (logcat) ----\n")
        sb.append(readLogcat())
        return sb.toString()
    }

    private fun readLogcat(): String = try {
        val pid = android.os.Process.myPid()
        val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "1000", "--pid", pid.toString()))
        proc.inputStream.bufferedReader().readText()
    } catch (e: Exception) {
        "(无法读取 logcat: ${e.message})"
    }

    fun writeReport(context: Context): File {
        val f = File(context.cacheDir, "feedback-${System.currentTimeMillis()}.txt")
        f.writeText(collectReport(context))
        return f
    }

    /** 一键邮件反馈（附日志 + 可选勾选的问题会话 JSON） */
    fun emailFeedback(context: Context, sessionFiles: List<File> = emptyList()) {
        val report = writeReport(context)
        val files = listOf(report) + sessionFiles
        val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
        val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "DeepSeek(3rd) 反馈 v${BuildConfig.VERSION_NAME}")
            putExtra(
                Intent.EXTRA_TEXT,
                "遇到的问题：\n\n（请描述复现步骤）\n\n--- 自动附加信息 ---\n版本 ${BuildConfig.VERSION_NAME} / ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n日志见附件。" +
                    if (sessionFiles.isNotEmpty()) "\n附 ${sessionFiles.size} 个问题会话导出。" else "",
            )
            if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            else putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "发送反馈邮件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 导出日志到 SAF 选择的输出流 */
    fun exportLog(context: Context, uri: Uri) {
        context.contentResolver.openOutputStream(uri)?.use { it.write(collectReport(context).toByteArray()) }
    }
}