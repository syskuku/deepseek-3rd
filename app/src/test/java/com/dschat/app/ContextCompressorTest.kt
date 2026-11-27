package com.dschat.app

import com.dschat.app.data.ChatMessage
import com.dschat.app.data.ContextCompressor
import com.dschat.app.data.ContextSummary
import com.dschat.app.data.ReplyBlock
import com.dschat.app.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    private fun user(id: String, text: String = "问题 $id") =
        ChatMessage(id = id, role = "user", createdAt = 0L, content = text, status = "done")

    private fun reply(id: String, text: String = "回答 $id") =
        ChatMessage(
            id = id, role = "assistant", createdAt = 0L, status = "done",
            blocks = listOf(ReplyBlock.Text(text)),
        )

    private fun round(u: String, a: String, ut: String = "问题 $u", at: String = "回答 $a"): List<ChatMessage> =
        listOf(user(u, ut), reply(a, at))

    private fun sessionOf(messages: List<ChatMessage>) = Session(
        id = "s", title = "t", createdAt = 0L, updatedAt = 0L,
        messages = messages, rootMessageId = messages.firstOrNull()?.id,
    )

    @Test
    fun `pairs ignore orphans and unusable replies`() {
        val history = listOf(
            user("u1"), reply("a1"),
            user("u2"), // 没有可用回答，应跳过
            user("u3"), reply("a3"),
        )
        val pairs = ContextCompressor.pairs(history)
        assertEquals(2, pairs.size)
        assertEquals("u1", pairs[0].first.id)
        assertEquals("u3", pairs[1].first.id)
    }

    @Test
    fun `plan covers older pairs and apply trims history`() {
        val history = round("u1", "a1") + round("u2", "a2") + round("u3", "a3") + round("u4", "a4")
        val plan = ContextCompressor.plan(history, keepPairs = 1)
        assertNotNull(plan)
        plan!!
        assertEquals(3, plan.coveredPairs)
        assertEquals("a3", plan.cutoffId)
        assertTrue(plan.transcript.contains("用户：问题 u1"))
        assertTrue(plan.transcript.contains("助手：回答 a3"))
        assertFalse(plan.transcript.contains("回答 a4"))

        val session = sessionOf(history).copy(
            contextSummary = ContextSummary(plan.transcript, plan.cutoffId, plan.fingerprint, plan.coveredPairs),
        )
        val applied = ContextCompressor.apply(session, history)
        assertNotNull(applied)
        assertEquals(listOf("u4", "a4"), applied!!.history.map { it.id })
    }

    @Test
    fun `apply rejects stale summary`() {
        val history = round("u1", "a1") + round("u2", "a2") + round("u3", "a3") + round("u4", "a4")
        val plan = ContextCompressor.plan(history, keepPairs = 1)!!
        val session = sessionOf(history).copy(
            contextSummary = ContextSummary(plan.transcript, plan.cutoffId, plan.fingerprint, plan.coveredPairs),
        )

        // cutoff 被删除（分支切换）
        assertNull(ContextCompressor.apply(session, history.filterNot { it.id == "a3" }))

        // 前缀内容被替换（同数量但 id 不同 → 指纹不同）
        val edited = listOf(user("x1"), reply("x1r")) + history.drop(2)
        assertNull(ContextCompressor.apply(session, edited))
    }

    @Test
    fun `shouldCompress respects threshold and existing summary`() {
        val history = round("u1", "a1") + round("u2", "a2")
        val base = sessionOf(history)
        assertFalse(ContextCompressor.shouldCompress(base, history))

        val nearLimit = base.copy(lastInputTokens = (ContextCompressor.CONTEXT_LIMIT_TOKENS * 0.96).toInt())
        assertTrue(ContextCompressor.shouldCompress(nearLimit, history))

        val plan = ContextCompressor.plan(history, keepPairs = 0)!!
        val withSummary = nearLimit.copy(
            contextSummary = ContextSummary(plan.transcript, plan.cutoffId, plan.fingerprint, plan.coveredPairs),
        )
        assertFalse(ContextCompressor.shouldCompress(withSummary, history))
    }

    @Test
    fun `estimate counts only what is sent`() {
        val history = listOf(
            user("u", "a".repeat(1600)),
            ChatMessage(
                id = "a", role = "assistant", createdAt = 0L, status = "done",
                blocks = listOf(
                    ReplyBlock.Text("b".repeat(1600)),
                    ReplyBlock.Thinking("思考内容".repeat(1000)),
                ),
            ),
        )
        val tokens = ContextCompressor.estimateTokens(history)
        // 3200 字符 / 1.6 = 2000，加 system 余量
        assertEquals(3500, tokens)
    }

    @Test
    fun `plan needs enough pairs`() {
        val history = round("u1", "a1") + round("u2", "a2")
        assertNull(ContextCompressor.plan(history, keepPairs = 1))
        assertNull(ContextCompressor.plan(history, keepPairs = 5))
    }

    @Test
    fun `clampSummary caps length`() {
        val long = "x".repeat(ContextCompressor.MAX_SUMMARY_CHARS + 100)
        val clamped = ContextCompressor.clampSummary(long)
        assertTrue(clamped.length <= ContextCompressor.MAX_SUMMARY_CHARS + 1)
    }
}
