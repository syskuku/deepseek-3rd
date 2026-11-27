package com.dschat.app.data

import java.security.MessageDigest

/**
 * 上下文压缩的纯逻辑（无 Android / 网络依赖，便于单测）。
 *
 * 策略：
 * - 只压缩「用户提问 + 可用回答」的配对，保留最近 [KEEP_RECENT_PAIRS] **轮**原文；
 * - 摘要覆盖到被压缩部分的最后一条消息（cutoffId），并用前缀指纹校验；
 * - 分支切换 / 编辑 / 删除导致前缀变化时校验失败，摘要自动失效（按原样发送或重新压缩）。
 */
object ContextCompressor {

    /** 模型上下文上限（deepseek-flash）。超过 [TRIGGER_RATIO] 时触发压缩。 */
    const val CONTEXT_LIMIT_TOKENS = 128_000
    const val TRIGGER_RATIO = 0.95

    /** 压缩后保留的最近原文轮数（1 轮 = 用户提问 + 助手回答） */
    const val KEEP_RECENT_PAIRS = 50

    /** 摘要最多保留的字数（防止摘要本身过长） */
    const val MAX_SUMMARY_CHARS = 18_000

    /** 触发压缩所需的最小压缩轮数（太短不值得调用模型） */
    const val MIN_COVERED_PAIRS = 2

    data class Plan(
        val transcript: String,
        val cutoffId: String,
        val fingerprint: String,
        val coveredPairs: Int,
    )

    data class Applied(
        val history: List<ChatMessage>,
        val summary: String,
        val covered: Int,
    )

    /** 与 RequestBuilder.toRequestMessages 相同的配对规则 */
    fun pairs(history: List<ChatMessage>): List<Pair<ChatMessage, ChatMessage>> {
        val out = ArrayList<Pair<ChatMessage, ChatMessage>>()
        var i = 0
        while (i + 1 < history.size) {
            val user = history[i]
            val reply = history[i + 1]
            if (user.role == "user" && reply.isUsableReply) {
                val hasText = user.content.isNotBlank()
                val hasFiles = user.files.any { it.serverId != null } || user.files.isNotEmpty()
                if (hasText || hasFiles) { out.add(user to reply); i += 2; continue }
            }
            i += 1
        }
        return out
    }

    /** 估算上下文 token 数（偏保守：中文约 1.6 字符/token，并预留 system 开销） */
    fun estimateTokens(history: List<ChatMessage>): Int {
        var chars = 0
        history.forEach { m ->
            if (m.role == "user") {
                chars += m.content.length
                m.files.forEach { chars += it.name.length + 32 }
            } else {
                m.blocks.forEach { b ->
                    if (b is ReplyBlock.Text && !b.isTemplate) chars += b.text.length
                }
            }
        }
        return (chars / 1.6).toInt() + 1500
    }

    fun fingerprint(ids: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(ids.joinToString("\n").toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { sb.append("%02x".format(it)) }
        return sb.toString()
    }

    /** 摘要是否仍然匹配当前分支（cutoff 存在且前缀指纹一致） */
    fun apply(session: Session, history: List<ChatMessage>): Applied? {
        val summary = session.contextSummary ?: return null
        val idx = history.indexOfFirst { it.id == summary.cutoffId }
        if (idx < 0) return null
        val ids = history.take(idx + 1).map { it.id }
        if (fingerprint(ids) != summary.fingerprint) return null
        return Applied(history.drop(idx + 1), summary.text, summary.covered)
    }

    /** 是否需要压缩：已有有效摘要则不需要；否则按上次服务端回报与估算值取大者判断 */
    fun shouldCompress(session: Session, history: List<ChatMessage>): Boolean {
        if (apply(session, history) != null) return false
        val used = maxOf(session.lastInputTokens, estimateTokens(history))
        return used >= (CONTEXT_LIMIT_TOKENS * TRIGGER_RATIO).toInt()
    }

    /**
     * 生成压缩计划；返回 null 表示不需要/无法压缩。
     * [keepPairs] 为保留原文的最近轮数（默认 50 轮）。
     */
    fun plan(history: List<ChatMessage>, keepPairs: Int = KEEP_RECENT_PAIRS): Plan? {
        val all = pairs(history)
        val keep = keepPairs.coerceAtLeast(0)
        // 必须至少留下 MIN_COVERED_PAIRS 轮可压缩
        if (all.size - keep < MIN_COVERED_PAIRS) return null
        val covered = all.dropLast(keep)
        if (covered.size < MIN_COVERED_PAIRS) return null

        val coveredIds = ArrayList<String>()
        val sb = StringBuilder()
        covered.forEach { (user, reply) ->
            coveredIds.add(user.id)
            coveredIds.add(reply.id)
            val question = if (user.content.isNotBlank()) user.content else "（发送了附件）"
            val files = if (user.files.isNotEmpty()) "\n[附件：" + user.files.joinToString("、") { it.name } + "]" else ""
            sb.append("用户：").append(question).append(files).append('\n')
            sb.append("助手：").append(reply.replyText).append("\n\n")
        }
        val cutoffId = coveredIds.last()
        val cutoffIdx = history.indexOfFirst { it.id == cutoffId }
        if (cutoffIdx < 0) return null
        // 指纹按 history 前缀计算，保证 apply() 能一致复现（跳过残轮等）
        val prefixIds = history.take(cutoffIdx + 1).map { it.id }
        return Plan(
            transcript = sb.toString().trim(),
            cutoffId = cutoffId,
            fingerprint = fingerprint(prefixIds),
            coveredPairs = covered.size,
        )
    }

    fun clampSummary(text: String): String =
        if (text.length <= MAX_SUMMARY_CHARS) text else text.take(MAX_SUMMARY_CHARS) + "…"
}
