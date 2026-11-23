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

/** 自动上下文压缩的结果（覆盖 cutoffId 及其之前的对话） */
@Serializable
data class ContextSummary(
    val text: String,
    /** 被压缩部分的最后一条消息 id（该消息及其之前的内容已被摘要替代） */
    val cutoffId: String,
    /** 被压缩前缀的指纹，用于校验摘要是否仍与当前分支一致 */
    val fingerprint: String,
    /** 被压缩的对话轮数（问答对） */
    val covered: Int,
    val createdAt: Long = 0L,
)

@Serializable
data class Session(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: List<ChatMessage>,
    val rootMessageId: String? = null,
    /** 上下文压缩摘要；为空表示未压缩 */
    val contextSummary: ContextSummary? = null,
    /** 上一次请求服务端回报的上下文 token 数（input + cache read + cache write） */
    val lastInputTokens: Int = 0,
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

    /**
     * 修复损坏的消息树（如旧版本写入的孤儿回复/同角色父子节点）：
     * 检测到非法结构时按创建时间重建线性链；合法树（含导入的分支树）原样保留。
     */
    fun repaired(): Session {
        val byId = messages.associateBy { it.id }
        val broken = messages.any { m ->
            val p = m.parentId?.let { byId[it] }
            (m.parentId != null && p == null) ||
                (p != null && p.role == m.role) ||
                (m.role == "assistant" && m.parentId == null)
        } || (rootMessageId != null && byId[rootMessageId] == null)
        if (!broken) return this
        val ordered = messages.sortedBy { it.createdAt }
        val linked = ordered.mapIndexed { i, m ->
            m.copy(parentId = ordered.getOrNull(i - 1)?.id, selectedChildId = ordered.getOrNull(i + 1)?.id)
        }
        return copy(messages = linked, rootMessageId = linked.firstOrNull()?.id)
    }

    /**
     * 追加一轮「提问 + 回复」并正确链接分支（官方 appendBranch 语义）：
     * 提问挂在当前路径末尾节点下，回复挂在提问下且被提问选中。
     * 缺少任何一条链接都会导致回复不可见或后续提问错误分叉。
     */
    fun appendRound(user: ChatMessage, assistant: ChatMessage): Session {
        val parentId = visibleMessages().lastOrNull()?.id
        val u = user.copy(parentId = parentId, selectedChildId = assistant.id)
        val a = assistant.copy(parentId = u.id)
        return copy(
            messages = messages.map { if (it.id == parentId) it.copy(selectedChildId = u.id) else it } + u + a,
            rootMessageId = rootMessageId ?: u.id,
            updatedAt = System.currentTimeMillis(),
        )
    }
}