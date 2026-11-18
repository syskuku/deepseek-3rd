package com.dschat.app

import com.dschat.app.net.UpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 验证检查更新优先命中国内云 manifest（而非 GitHub）。依赖网络，失败时跳过。 */
class UpdateCheckerCloudTest {
    @Test
    fun cloudManifestIsPreferred() = runBlocking {
        val info = try {
            UpdateChecker.fetchLatest()
        } catch (e: Exception) {
            null
        }
        assumeTrue("network unavailable", info != null)
        assertTrue("should prefer cloud (Worker/kodo) url", info!!.apkUrl.contains("imikufans.com"))
        assertTrue("version present", info.version.isNotEmpty())
    }
}