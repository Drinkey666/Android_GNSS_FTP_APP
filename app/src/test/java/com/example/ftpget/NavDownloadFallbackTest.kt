package com.example.ftpget

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** 用模拟 FTP 验证实际下载流程，不依赖公网在测试时是否已经发布当天文件。 */
class NavDownloadFallbackTest {
    @get:Rule val tmp = TemporaryFolder()
    private val today = "/IGS/BRDC/2026/291/"
    private val yesterday = "/IGS/BRDC/2026/290/"
    private val todayName = "BRDC00IGS_R_20262910000_01D_MN.rnx.gz"
    private val oldName = "BRDC00IGS_R_20262900000_01D_MN.rnx.gz"
    private val now = Instant.parse("2026-10-18T01:00:00Z").toEpochMilli()

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { buffer ->
        GZIPOutputStream(buffer).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private class FakeFtp(val directories: Map<String, Map<String, ByteArray>>, val loginOk: Boolean = true) : FTPClient() {
        val visited = mutableListOf<String>()
        val downloaded = mutableListOf<String>()
        private var current = ""
        private var connected = false
        override fun connect(hostname: String, port: Int) { connected = true }
        override fun login(username: String, password: String) = loginOk
        override fun enterLocalPassiveMode() { }
        override fun setFileType(fileType: Int) = true
        override fun isConnected() = connected
        override fun disconnect() { connected = false }
        override fun changeWorkingDirectory(pathname: String): Boolean {
            visited += pathname
            if (!directories.containsKey(pathname)) return false
            current = pathname
            return true
        }
        override fun listFiles(): Array<FTPFile> = directories[current].orEmpty().map { (name, bytes) ->
            FTPFile().apply { this.name = name; type = FTPFile.FILE_TYPE; size = bytes.size.toLong() }
        }.toTypedArray()
        override fun retrieveFile(remote: String, local: OutputStream): Boolean {
            downloaded += remote
            val bytes = directories[current]?.get(remote) ?: return false
            local.write(bytes)
            return true
        }
    }

    private fun fetch(ftp: FakeFtp, log: MutableList<String> = mutableListOf()): Boolean = runBlocking {
        FtpDownloader.downloadNavProduct("fake.invalid", today, tmp.root,
            referenceUtcMillis = now, clientFactory = { ftp }, preferHttps = false, onProgress = { log += it })
    }

    @Test fun absentTodayDirectoryDownloadsYesterdayAndReportsFallback() {
        val log = mutableListOf<String>()
        val ftp = FakeFtp(mapOf(yesterday to mapOf(oldName to gzip(BroadcastNavPolicyTest.navFixture()))))
        assertTrue(fetch(ftp, log))
        assertEquals(listOf(today, yesterday), ftp.visited)
        assertEquals(listOf(oldName), ftp.downloaded)
        assertTrue(log.any { it.startsWith("⚠️ 使用回退广播星历") })
        assertTrue(tmp.root.resolve(oldName).isFile)
        assertFalse(ftp.isConnected)
    }

    @Test fun existingButEmptyTodayDirectoryAlsoFallsBack() {
        val ftp = FakeFtp(mapOf(today to emptyMap(),
            yesterday to mapOf(oldName to gzip(BroadcastNavPolicyTest.navFixture()))))
        assertTrue(fetch(ftp))
        assertEquals(listOf(oldName), ftp.downloaded)
    }

    @Test fun todayAvailableDoesNotDownloadOlderDirectory() {
        val ftp = FakeFtp(mapOf(today to mapOf(todayName to gzip(BroadcastNavPolicyTest.navFixture())),
            yesterday to mapOf(oldName to gzip(BroadcastNavPolicyTest.navFixture()))))
        assertTrue(fetch(ftp))
        assertEquals(listOf(today), ftp.visited)
    }

    @Test fun supportsUncompressedNav() {
        val name = todayName.removeSuffix(".gz")
        assertTrue(fetch(FakeFtp(mapOf(today to mapOf(name to BroadcastNavPolicyTest.navFixture().toByteArray())))))
        assertTrue(tmp.root.resolve(name).isFile)
    }

    @Test fun corruptLatestDoesNotOverwriteGoodLocalAndTriesOtherSource() {
        val oldContent = gzip(BroadcastNavPolicyTest.navFixture())
        tmp.root.resolve(todayName).writeBytes(oldContent)
        val alternative = "BRDM00DLR_S_20262910000_01D_MN.rnx.gz"
        val ftp = FakeFtp(mapOf(today to mapOf(todayName to gzip("<html>404</html>"), alternative to oldContent)))
        assertTrue(fetch(ftp))
        assertEquals(listOf(todayName, alternative), ftp.downloaded)
        assertArrayEquals(oldContent, tmp.root.resolve(todayName).readBytes())
        assertFalse(tmp.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun doesNotSkipDownloadBecauseLocalFileAlreadyExists() {
        tmp.root.resolve(todayName).writeText("old incomplete local file")
        val ftp = FakeFtp(mapOf(today to mapOf(todayName to gzip(BroadcastNavPolicyTest.navFixture()))))
        assertTrue(fetch(ftp))
        assertTrue(fetch(ftp))
        assertEquals(2, ftp.downloaded.size)
        assertTrue(FtpDownloader.reusableLocalFile(tmp.root.resolve(todayName)))
    }

    @Test fun loginFailureReportsRealErrorAndClosesConnection() {
        val ftp = FakeFtp(emptyMap(), loginOk = false)
        val log = mutableListOf<String>()
        assertFalse(fetch(ftp, log))
        assertTrue(log.any { it.contains("登录失败") })
        assertTrue(ftp.visited.isEmpty())
        assertFalse(ftp.isConnected)
    }

    @Test fun allDaysMissingReturnsFailureNotFakeSuccess() {
        val ftp = FakeFtp(emptyMap())
        assertFalse(fetch(ftp))
        assertEquals(4, ftp.visited.size)
        assertTrue(tmp.root.listFiles().orEmpty().isEmpty())
    }
}
