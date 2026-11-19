package com.dschat.app

import com.dschat.app.data.HistoryArchive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** 实测含超长图的官方导出包：导入不抛异常、长图原件完整提取。 */
class LongImageImportTest {
    @Test
    fun importLongImageArchive() {
        val f = File("D:\\dschat\\official-long.zip")
        assumeTrue("official long-image export not present", f.exists())
        val saved = LinkedHashMap<String, Int>()
        val sessions = HistoryArchive.import({ f.inputStream() }) { name, bytes ->
            saved[name] = bytes.size
            "attachments/x/$name"
        }
        assertTrue(sessions.isNotEmpty())
        assertTrue("should extract images", saved.isNotEmpty())
        saved.values.forEach { assertTrue("image bytes must be >0", it > 0) }
        // 超长图（523x14628）必须完整提取
        val tall = saved.entries.firstOrNull { it.key.contains("c43b0402") }
        assertTrue("tall image extracted", tall != null && tall.value > 100000)
        // 所有会话都有可见消息
        sessions.forEach { s -> assertTrue(s.visibleMessages().isNotEmpty()) }
    }
}