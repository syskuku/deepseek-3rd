package com.dschat.app.net

/** 附件规则，对照官方 attachments.ts / uploadExts.ts */
object AttachmentRules {
    const val MAX_SIZE_MB = 64
    const val MAX_USER_DATA_SIZE_MB = 32
    const val MAX_COUNT = 50

    private val IMAGE_EXTS = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "svgz", "ico", "xbm", "dib",
        "pjp", "tif", "tiff", "pjpeg", "avif", "apng", "jfif",
    )
    private val LAYOUT_EXTS = setOf("pdf", "doc", "docx", "ppt", "pptx")
    private val TEXT_EXTS = setOf(
        "txt", "md", "csv", "tsv", "html", "json", "log", "yaml", "yml", "ini", "conf", "xml", "sql",
        "go", "h", "c", "cpp", "cxx", "cc", "cs", "java", "js", "css", "jsp", "php", "py", "py3",
        "asp", "ts", "tsx", "sh", "bat", "rs", "rb", "kt", "swift", "scala", "r", "m", "pl", "lua",
        "dart", "vue", "svelte", "toml", "gradle", "properties", "wxml", "wxss", "wxs", "qml",
    )
    val ACCEPTED_EXTS: Set<String> = IMAGE_EXTS + LAYOUT_EXTS + TEXT_EXTS

    private val MIME_BY_EXT = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "svg" to "image/svg+xml", "ico" to "image/x-icon", "avif" to "image/avif",
        "tif" to "image/tiff", "tiff" to "image/tiff",
        "pdf" to "application/pdf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "html" to "text/html", "json" to "application/json", "xml" to "text/xml",
    )

    fun extOf(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(dot + 1).lowercase() else ""
    }

    fun isAccepted(name: String): Boolean = extOf(name) in ACCEPTED_EXTS

    fun isImageExt(name: String): Boolean = extOf(name) in IMAGE_EXTS

    fun mimeOf(name: String, provided: String?): String =
        provided?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MIME_BY_EXT[extOf(name)] ?: ""

    /** 图片不传 purpose；PDF/Word/PPT 版面解析；其余 user_data */
    fun purposeOf(name: String, isImage: Boolean): String? = when {
        isImage -> null
        extOf(name) in LAYOUT_EXTS -> "rich_layout_recognition"
        else -> "user_data"
    }

    fun maxSizeMB(name: String, isImage: Boolean): Int =
        if (purposeOf(name, isImage) == "user_data") MAX_USER_DATA_SIZE_MB else MAX_SIZE_MB

    /** 返回错误文案，null 表示通过 */
    fun validate(name: String, size: Long, isImage: Boolean, currentCount: Int): String? = when {
        !isAccepted(name) -> "上传的文件包含暂未支持的文件格式，请转换文件格式后重试"
        size > maxSizeMB(name, isImage) * 1024L * 1024L -> "单个文件大小超过限制（${maxSizeMB(name, isImage)}MB），请调整后重试"
        currentCount + 1 > MAX_COUNT -> "上传文件最多支持 $MAX_COUNT 个"
        else -> null
    }

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
        return (if (value < 10) String.format("%.1f", value) else value.toInt().toString()) + " " + units[unit]
    }

    /** 服务端判定附件 file_id 失效的 400 文案（官方 getFileErrorCode） */
    fun isFileIdError(status: Int?, message: String?): Boolean {
        if (status != 400 || message == null) return false
        return Regex("file_ids do not exist or are not created under your account", RegexOption.IGNORE_CASE).containsMatchIn(message) ||
            Regex("file data not found for file_id", RegexOption.IGNORE_CASE).containsMatchIn(message) ||
            Regex("\\.source\\.file_id: invalid file_id", RegexOption.IGNORE_CASE).containsMatchIn(message)
    }
}