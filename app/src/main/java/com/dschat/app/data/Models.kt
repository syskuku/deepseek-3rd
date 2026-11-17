package com.dschat.app.data

import kotlinx.serialization.Serializable

/** 会话列表项（不含消息体） */
@Serializable
data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 附件元数据（导入归档时保留文件名/大小；v1 不导入图片原件） */
@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val isImage: Boolean,
)

/** 搜索结果条目 */
@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String = "",
    val siteName: String? = null,
)

/** 助手回复内容块，与官方 demo 的 ReplyBlock 对应（简化：不含 read_link 签名回传） */
@Serializable
sealed class ReplyBlock {
    @Serializable
    data class Text(val text: String, val isTemplate: Boolean = false) : ReplyBlock()

    @Serializable
    data class Thinking(
        val thinking: String,
        val signature: String? = null,
        val startedAt: Long? = null,
        val endedAt: Long? = null,
    ) : ReplyBlock()

    @Serializable
    data class Search(
        val queries: List<String> = emptyList(),
        val results: List<SearchResult> = emptyList(),
        /** WIP / FINISHED / FAILED */
        val status: String = "WIP",
    ) : ReplyBlock()
}

/**
 * 一条消息。role: user / assistant。
 * status: pending / streaming / done / stopped / error / refusal
 */
@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val createdAt: Long,
    val content: String = "",
    val blocks: List<ReplyBlock> = emptyList(),
    val status: String = "done",
    val stopReason: String? = null,
    val thinking: Boolean = false,
    val search: Boolean = false,
    val errorText: String? = null,
    val errorRetryable: Boolean = false,
    val files: List<FileMeta> = emptyList(),
) {
    val replyText: String
        get() = blocks.joinToString("") { if (it is ReplyBlock.Text && !it.isTemplate) it.text else "" }

    val templateText: String?
        get() = blocks.filterIsInstance<ReplyBlock.Text>().firstOrNull { it.isTemplate }?.text

    /** 与官方 isUsableReply 一致：结束/停止、非拒绝、无模板、有正文 */
    val isUsableReply: Boolean
        get() = role == "assistant" &&
            (status == "done" || status == "stopped") &&
            status != "refusal" && stopReason != "refusal" &&
            blocks.none { it is ReplyBlock.Text && it.isTemplate } &&
            replyText.isNotEmpty()
}

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
) {
    fun meta() = SessionMeta(id, title, createdAt, updatedAt)
}