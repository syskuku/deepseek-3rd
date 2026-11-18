package com.dschat.app

import com.dschat.app.data.HistoryArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 回归：官方导出 ZIP 的图片为 STORED+EXT 写法，必须完整提取（非 0 字节），
 * 否则发送时服务端报 'file' is empty。依赖本机真实导出文件，缺失时跳过。
 */
class OfficialZipImportTest {
    @Test
    fun importOfficialZipExtractsFullImages() {
        val f = File("C:\\Users\\OMEN\\Downloads\\deepseek_data-2026-11-18.zip")
        assumeTrue("official export not present", f.exists())
        val saved = LinkedHashMap<String, Int>()
        val sessions = HistoryArchive.import({ f.inputStream() }) { name, bytes ->
            saved[name] = bytes.size
            "attachments/x/$name"
        }
        assertTrue(sessions.isNotEmpty())
        assertTrue("should extract images", saved.isNotEmpty())
        // 所有提取出的图片都必须非空
        saved.values.forEach { assertTrue("image must not be empty", it > 0) }
        // 音乐截图会话的图片应为 56298 字节
        val music = sessions.find { it.id == "10a1ff18-9e4f-4862-a106-449a2f8c5f77" }
        assertNotNull(music)
        val file = music!!.messages.flatMap { it.files }.first()
        assertNotNull(file.localPath)
        assertEquals(56298, file.size)
        val imgEntry = saved.entries.first { it.key.contains("10a1ff18") }
        assertEquals(56298, imgEntry.value)
    }
}