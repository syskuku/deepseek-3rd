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

/** 附件：本地原件 + Files API serverId（发送前补传） */
@Serializable
data class FileMeta(
    val id: String,
    val name: String,
    val size: Long,
    val isImage: Boolean,
    val mimeType: String = "",
    /** 相对 filesDir 的原件路径，可空（仅元数据的历史附件） */
    val localPath: String? = null,
    /** Files API 返回的 file_id */
    val serverId: String? = null,
)

/** 搜索结果条目 */
@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String = "",
    val siteName: String? = null,
)

/** 助手回复内容块 */
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
 * 一条消息（树节点）。role: user / assistant。
 * status: pending / streaming / done / stopped / error / refusal
 * parentId/selectedChildId 对照官方 branches.ts。
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
    val parentId: String? = null,
    val selectedChildId: String? = null,
) {
    val replyText: String
        get() = blocks.joinToString("") { if (it is ReplyBlock.Text && !it.isTemplate) it.text else "" }

    val templateText: String?
        get() = blocks.filterIsInstance<ReplyBlock.Text>().firstOrNull { it.isTemplate }?.text

    /** 与官方 isUsableReply 一致 */
    val isUsableReply: Boolean
        get() = role == "assistant" &&
            (status == "done" || status == "stopped") &&
            stopReason != "refusal" &&
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
    val rootMessageId: String? = null,
) {
    fun meta() = SessionMeta(id, title, createdAt, updatedAt)

    private fun byId(): Map<String, ChatMessage> = messages.associateBy { it.id }

    /** 当前选中分支路径（官方 getVisibleMessages） */
    fun visibleMessages(): List<ChatMessage> {
        val map = byId()
        val path = ArrayList<ChatMessage>()
        var id = rootMessageId ?: messages.firstOrNull()?.id
        val seen = HashSet<String>()
        while (id != null && seen.add(id)) {
            val m = map[id] ?: break
            path += m
            id = m.selectedChildId
        }
        return path
    }

    /** 某节点的兄弟版本（含自身），按创建时间排序 */
    fun siblingsOf(message: ChatMessage): List<ChatMessage> =
        messages.filter { it.parentId == message.parentId }.sortedBy { it.createdAt }

    /** 规范化：旧线性数据补全树字段（官方 normalizeSession） */
    fun normalized(): Session {
        if (rootMessageId != null || messages.isEmpty()) return this
        val linked = messages.mapIndexed { i, m ->
            m.copy(
                parentId = messages.getOrNull(i - 1)?.id,
                selectedChildId = messages.getOrNull(i + 1)?.id,
            )
        }
        return copy(messages = linked, rootMessageId = linked.first().id)
    }
}