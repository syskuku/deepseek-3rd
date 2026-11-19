package com.dschat.app

import com.dschat.app.data.ArchiveException
import com.dschat.app.data.ChatMessage
import com.dschat.app.data.FileMeta
import com.dschat.app.data.HistoryArchive
import com.dschat.app.data.ReplyBlock
import com.dschat.app.data.Session
import com.dschat.app.net.AttachmentRules
import com.dschat.app.net.RequestBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class HistoryArchiveTest {

    private fun sampleSession(): Session {
        val t = 1_700_000_000_000L
        val u1 = ChatMessage("u1", "user", t, content = "第一个问题", files = listOf(FileMeta("f1", "photo.png", 4, true, "image/png", null, null)), parentId = null, selectedChildId = "a2")
        val a1 = ChatMessage("a1", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("旧回答")), parentId = "u1")
        val a2 = ChatMessage("a2", "assistant", t + 2, blocks = listOf(ReplyBlock.Thinking("思考过程", startedAt = 0, endedAt = 3000), ReplyBlock.Text("新回答 E=mc^2")), parentId = "u1", selectedChildId = "u2", thinking = true)
        val u2 = ChatMessage("u2", "user", t + 3, content = "追问", parentId = "a2", selectedChildId = "a3")
        val a3 = ChatMessage("a3", "assistant", t + 4, blocks = listOf(ReplyBlock.Text("最终回答")), parentId = "u2")
        return Session("s1", "测试对话", t, t + 4, listOf(u1, a1, a2, u2, a3), rootMessageId = "u1")
    }

    private fun exportBytes(sessions: List<Session>, images: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        HistoryArchive.export(sessions, { f -> images[f.id] }, out)
        return out.toByteArray()
    }

    private fun importBytes(bytes: ByteArray): Pair<List<Session>, Map<String, ByteArray>> {
        val saved = LinkedHashMap<String, ByteArray>()
        val sessions = HistoryArchive.import({ ByteArrayInputStream(bytes) }) { name, data ->
            saved[name] = data
            "attachments/test/$name"
        }
        return sessions to saved
    }

    @Test
    fun roundTripPreservesTreeAndBranch() {
        val original = sampleSession()
        val bytes = exportBytes(listOf(original), mapOf("f1" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        val (sessions, images) = importBytes(bytes)

        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals("测试对话", s.title)
        assertEquals(5, s.messages.size)

        // 当前分支：u1 -> a2 -> u2 -> a3
        val visible = s.visibleMessages().map { it.content.ifEmpty { it.replyText } }
        assertEquals(listOf("第一个问题", "新回答 E=mc^2", "追问", "最终回答"), visible)

        // 兄弟版本保留
        val importedA2 = s.visibleMessages()[1]
        assertEquals(2, s.siblingsOf(importedA2).size)

        // 思考块与用时保留
        val think = importedA2.blocks.filterIsInstance<ReplyBlock.Thinking>().first()
        assertEquals("思考过程", think.thinking)
        assertEquals(3L, (think.endedAt!! - think.startedAt!!) / 1000)

        // 图片原件提取并回填 localPath
        assertEquals(1, images.size)
        val file = s.messages.first { it.id.endsWith("-0") }.files.first()
        assertNotNull(file.localPath)
        assertTrue(file.isImage)
    }

    @Test
    fun roundTripCurrentBranchSwitch() {
        // 导出时当前分支停在 a1（u1.selectedChildId = a1）
        val original = sampleSession().let { s ->
            s.copy(messages = s.messages.map { if (it.id == "u1") it.copy(selectedChildId = "a1") else it })
        }
        val bytes = exportBytes(listOf(original))
        val (sessions, _) = importBytes(bytes)
        val visible = sessions[0].visibleMessages()
        assertEquals(2, visible.size)
        assertEquals("旧回答", visible[1].replyText)
    }

    @Test
    fun zipStructureMatchesOfficial() {
        val bytes = exportBytes(listOf(sampleSession()), mapOf("f1" to byteArrayOf(1, 2, 3)))
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names += e.name; zis.closeEntry(); e = zis.nextEntry }
        }
        assertEquals("manifest.json", names.first())
        assertTrue(names.contains("chat_session_index.json"))
        assertTrue(names.any { it.startsWith("chat_sessions/") && it.endsWith(".json") })
        assertTrue(names.any { it.contains("_files/") })
        // 图片在索引之后
        assertTrue(names.indexOf("chat_session_index.json") < names.indexOfFirst { it.contains("_files/") })
    }

    @Test
    fun rejectsWrongVersion() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"export_format_version":2}""".toByteArray())
            zos.closeEntry()
        }
        try {
            importBytes(out.toByteArray())
            fail("should reject version 2")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("版本"))
        }
    }

    @Test
    fun rejectsNonDeepSeekZip() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("hi".toByteArray())
            zos.closeEntry()
        }
        try {
            importBytes(out.toByteArray())
            fail("should reject")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("无法识别"))
        }
    }

    @Test
    fun rejectsBrokenBranch() {
        // parent_id 大于子 id
        val sessionJson = """
        {"id":"s1","title":null,"pinned":false,"current_message_id":1,
         "inserted_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z",
         "messages":[
           {"id":0,"parent_id":1,"role":"USER","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-01-01T00:00:00.000Z","fragments":[{"type":"REQUEST","content":"hi"}]},
           {"id":1,"parent_id":null,"role":"ASSISTANT","status":"FINISHED","thinking_enabled":false,"search_enabled":false,"accumulated_token_usage":0,"inserted_at":"2026-01-01T00:00:00.000Z","fragments":[{"type":"RESPONSE","content":"yo"}]}
         ]}
        """.trimIndent()
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            fun entry(n: String, c: String) { zos.putNextEntry(ZipEntry(n)); zos.write(c.toByteArray()); zos.closeEntry() }
            entry("manifest.json", """{"export_format_version":3}""")
            entry("chat_sessions/s1.json", sessionJson)
            entry("chat_session_index.json", """[{"id":"s1","title":null,"pinned":false,"message_count":2,"inserted_at":"2026-01-01T00:00:00.000Z","updated_at":"2026-01-01T00:00:00.000Z"}]""")
        }
        try {
            importBytes(out.toByteArray())
            fail("should reject broken branch")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("分支"))
        }
    }

    @Test
    fun emptySessionFails() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zos ->
            fun entry(n: String, c: String) { zos.putNextEntry(ZipEntry(n)); zos.write(c.toByteArray()); zos.closeEntry() }
            entry("manifest.json", """{"export_format_version":3}""")
            entry("chat_session_index.json", "[]")
        }
        try {
            importBytes(out.toByteArray())
            fail("should reject empty")
        } catch (e: ArchiveException) {
            assertTrue(e.message!!.contains("不完整"))
        }
    }
}

class SessionTreeTest {

    @Test
    fun appendRoundKeepsLinearAndVisible() {
        // 回归：send 路径必须让提问与回复都落在可见路径上，且第二轮不产生分叉
        val t = 1000L
        var s = Session("s", "", t, t, emptyList())
        val u1 = ChatMessage("u1", "user", t, content = "第一问")
        val a1 = ChatMessage("a1", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("第一答")))
        s = s.appendRound(u1, a1)
        assertEquals(listOf("u1", "a1"), s.visibleMessages().map { it.id })
        assertEquals("a1", s.messages.first { it.id == "u1" }.selectedChildId)

        val u2 = ChatMessage("u2", "user", t + 2, content = "第二问")
        val a2 = ChatMessage("a2", "assistant", t + 3, blocks = listOf(ReplyBlock.Text("第二答")))
        s = s.appendRound(u2, a2)
        assertEquals(listOf("u1", "a1", "u2", "a2"), s.visibleMessages().map { it.id })
        // 第二轮提问挂在第一答之下，不是第一问的兄弟
        assertEquals("a1", s.messages.first { it.id == "u2" }.parentId)
        assertEquals(1, s.siblingsOf(s.messages.first { it.id == "u2" }).size)
        assertEquals(1, s.siblingsOf(s.messages.first { it.id == "a1" }).size)
    }

    @Test
    fun appendRoundAfterErrorStillLinear() {
        val t = 1000L
        var s = Session("s", "", t, t, emptyList())
        s = s.appendRound(
            ChatMessage("u1", "user", t, content = "q"),
            ChatMessage("a1", "assistant", t + 1, status = "error", errorText = "x"),
        )
        s = s.appendRound(
            ChatMessage("u2", "user", t + 2, content = "q2"),
            ChatMessage("a2", "assistant", t + 3, blocks = listOf(ReplyBlock.Text("ok"))),
        )
        assertEquals(4, s.visibleMessages().size)
        assertEquals("a1", s.messages.first { it.id == "u2" }.parentId)
    }

    @Test
    fun repairsCorruptedLegacyTree() {
        // 模拟 v0.2.0 损坏数据：回复孤儿（parent=null）、第二轮提问错挂到第一问下
        val t = 1000L
        val corrupted = Session(
            "s", "", t, t + 3,
            listOf(
                ChatMessage("u1", "user", t, content = "q1", selectedChildId = null),
                ChatMessage("a1", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("r1")), parentId = null),
                ChatMessage("u2", "user", t + 2, content = "q2", parentId = "u1"),
                ChatMessage("a2", "assistant", t + 3, blocks = listOf(ReplyBlock.Text("r2")), parentId = null),
            ),
            rootMessageId = "u1",
        )
        val fixed = corrupted.repaired()
        assertEquals(listOf("u1", "a1", "u2", "a2"), fixed.visibleMessages().map { it.id })
        assertEquals("u1", fixed.messages.first { it.id == "a1" }.parentId)
        assertEquals("a1", fixed.messages.first { it.id == "u2" }.parentId)
        // 合法分支树不被改动
        val valid = Session(
            "s", "", t, t,
            listOf(
                ChatMessage("u", "user", t, content = "q", selectedChildId = "a2"),
                ChatMessage("a1", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("v1")), parentId = "u"),
                ChatMessage("a2", "assistant", t + 2, blocks = listOf(ReplyBlock.Text("v2")), parentId = "u"),
            ),
            rootMessageId = "u",
        )
        assertEquals(valid, valid.repaired())
    }

    @Test
    fun normalizeLinearSession() {
        val t = 1000L
        val s = Session(
            "s", "", t, t,
            listOf(
                ChatMessage("m1", "user", t, content = "a"),
                ChatMessage("m2", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("b"))),
            ),
        )
        val n = s.normalized()
        assertEquals("m1", n.rootMessageId)
        assertEquals(listOf("m1", "m2"), n.visibleMessages().map { it.id })
        assertEquals("m2", n.messages[0].selectedChildId)
    }

    @Test
    fun visiblePathFollowsSelection() {
        val t = 1000L
        val session = Session(
            "s", "", t, t,
            listOf(
                ChatMessage("u", "user", t, content = "q", selectedChildId = "a2"),
                ChatMessage("a1", "assistant", t + 1, blocks = listOf(ReplyBlock.Text("v1")), parentId = "u"),
                ChatMessage("a2", "assistant", t + 2, blocks = listOf(ReplyBlock.Text("v2")), parentId = "u"),
            ),
            rootMessageId = "u",
        )
        assertEquals(listOf("u", "a2"), session.visibleMessages().map { it.id })
        assertEquals(2, session.siblingsOf(session.messages[1]).size)
    }
}

class RequestBuilderTest {

    @Test
    fun reminderFormatMatchesServerContract() {
        val reminder = RequestBuilder.latestReminder()
        val parts = reminder.split(",")
        assertEquals(5, parts.size)
        assertTrue("date must be YYYY-MM-DD: $reminder", Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(parts[0]))
        assertTrue("weekday must be Chinese: $reminder", Regex("^星期[一二三四五六日]$").matches(parts[1]))
        assertEquals("", parts[2])
        assertEquals("App", parts[3])
        assertEquals("中文", parts[4])
    }

    @Test
    fun userFileBlocks() {
        val t = 1000L
        val question = ChatMessage(
            "q", "user", t, content = "看图",
            files = listOf(FileMeta("f", "a.png", 10, true, "image/png", "attachments/a.png", "file-123")),
        )
        val messages = RequestBuilder.toRequestMessages(emptyList(), question)
        val last = messages.last().jsonObject
        assertEquals("user", last["role"]!!.jsonPrimitive.content)
        val content = last["content"]!!.jsonArray
        assertEquals(2, content.size)
        val block = content[0].jsonObject
        assertEquals("image", block["type"]!!.jsonPrimitive.content)
        assertEquals("file-123", block["source"]!!.jsonObject["file_id"]!!.jsonPrimitive.content)
        assertTrue(RequestBuilder.hasFileBlocks(emptyList(), question))
    }

    @Test
    fun unusableRoundsExcluded() {
        val t = 1000L
        val history = listOf(
            ChatMessage("u1", "user", t, content = "q1"),
            ChatMessage("a1", "assistant", t + 1, status = "error", errorText = "x"),
            ChatMessage("u2", "user", t + 2, content = "q2"),
            ChatMessage("a2", "assistant", t + 3, blocks = listOf(ReplyBlock.Text("ok"))),
        )
        val question = ChatMessage("q", "user", t + 4, content = "q3")
        val messages = RequestBuilder.toRequestMessages(history, question)
        // u2 + a2 + reminder + q3（失败轮次不进上下文；reminder 位于历史之后、本轮提问之前）
        assertEquals(4, messages.size)
        assertEquals("q2", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("latest_reminder", messages[2].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun bodyShape() {
        val body = RequestBuilder.buildBody(JsonArray(emptyList()), thinking = true, search = true, requestTitle = true)
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["safety_check"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertEquals(2, body["tools"]!!.jsonArray.size)
        assertNotNull(body["title"])
        assertEquals(true, body["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun trimHistoryKeepsRecentRoundsWithinBudget() {
        val t = 1000L
        val history = ArrayList<ChatMessage>()
        for (r in 0 until 200) {
            history.add(ChatMessage("u$r", "user", t + r * 2, content = "问".repeat(1000)))
            history.add(ChatMessage("a$r", "assistant", t + r * 2 + 1, blocks = listOf(ReplyBlock.Text("答".repeat(1000)))))
        }
        val trimmed = RequestBuilder.trimHistory(history, budgetChars = 20_000)
        // 每轮约 2016 字符，20000 预算约保留 9 轮=18 条
        assertTrue(trimmed.size < history.size)
        assertTrue(trimmed.size <= 20)
        // 保留的是最近的轮次
        assertEquals("a199", trimmed.last().id)
        // 完整轮次（成对）
        assertEquals(0, trimmed.size % 2)
        assertEquals("u191", trimmed.first().id)
    }
}

class AttachmentRulesTest {

    @Test
    fun purposeMapping() {
        assertNull(AttachmentRules.purposeOf("a.png", true))
        assertEquals("rich_layout_recognition", AttachmentRules.purposeOf("a.pdf", false))
        assertEquals("rich_layout_recognition", AttachmentRules.purposeOf("a.docx", false))
        assertEquals("user_data", AttachmentRules.purposeOf("a.txt", false))
        assertEquals("user_data", AttachmentRules.purposeOf("a.py", false))
    }

    @Test
    fun validation() {
        assertNull(AttachmentRules.validate("a.png", 1024, true, 0))
        assertNotNull(AttachmentRules.validate("a.exe", 1024, false, 0))
        assertNotNull(AttachmentRules.validate("a.txt", 33L * 1024 * 1024, false, 0))
        assertNull(AttachmentRules.validate("a.pdf", 60L * 1024 * 1024, false, 0))
        assertNotNull(AttachmentRules.validate("a.png", 1024, true, 50))
    }

    @Test
    fun fileIdErrorDetection() {
        assertTrue(AttachmentRules.isFileIdError(400, "file_ids do not exist or are not created under your account"))
        assertTrue(AttachmentRules.isFileIdError(400, "file data not found for file_id: file-abc"))
        assertTrue(AttachmentRules.isFileIdError(400, "messages.0.content.0.source.file_id: invalid file_id"))
        assertTrue(!AttachmentRules.isFileIdError(401, "file_ids do not exist"))
        assertTrue(!AttachmentRules.isFileIdError(400, "some other error"))
    }
}