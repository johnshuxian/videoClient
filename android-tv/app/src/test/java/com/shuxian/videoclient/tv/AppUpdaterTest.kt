package com.shuxian.videoclient.tv

import com.shuxian.videoclient.update.AppUpdater
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AppUpdaterTest {
    @Test
    fun everyCheckClearsOnlyUpdateDownloadsEvenWhenTheRequestFails() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val unrelated = File(context.cacheDir, "keep.txt").apply { writeText("keep") }

        for (television in listOf(false, true)) {
            val updater = AppUpdater(context, television)
            repeat(2) {
                File(directory, "old.apk").writeText("old installer")
                File(directory, "old.apk.part").writeText("unfinished download")
                // 无效线路在网络请求前失败，验证清理不会依赖服务器是否可用。
                val error = assertThrows(IOException::class.java) { updater.check("invalid") }
                assertEquals("无效的更新线路", error.message)
                assertTrue(directory.listFiles()!!.isEmpty())
                assertEquals("keep", unrelated.readText())
            }
        }
    }

    @Test
    fun checkingWithoutAnExistingUpdateDirectoryReachesTheRequest() {
        val context = RuntimeEnvironment.getApplication()
        assertTrue(!File(context.cacheDir, "updates").exists())
        val error = assertThrows(IOException::class.java) { AppUpdater(context, false).check("invalid") }
        assertEquals("无效的更新线路", error.message)
    }
}
