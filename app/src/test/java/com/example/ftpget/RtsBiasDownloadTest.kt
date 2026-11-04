package com.example.ftpget

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.time.Instant
import java.util.zip.GZIPOutputStream

class RtsBiasDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = Instant.parse("2026-10-25T03:54:00Z").toEpochMilli()
    private val dir = "/pub/whu/phasebias/2026/bias"
    private val name = "WUM0MGXRTS_20262980000_01D_05M_OSB.BIA.gz"
    private val old = name.replace("2026298", "2026297")
    private val nrt = "WUM0MGXNRT_20262980400_01D_01D_OSB.BIA.gz"
    private fun gzip(text: String) = ByteArrayOutputStream().also { b ->
        GZIPOutputStream(b).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private inner class FakeFtp(
        val listing: Map<String, List<String>> = mapOf(dir to listOf(name)),
        val bytes: ByteArray = gzip("OSB transport fixture"),
        val transferOk: Boolean = true,
        val listTimeout: Boolean = false,
        val transferTimeout: Boolean = false,
        val directoryEntry: String? = null
    ) : FTPClient() {
        val visits = mutableListOf<String>()
        val transfers = mutableListOf<String>()
        var closed = false
        var cwd = ""
        override fun connect(hostname: String, port: Int) {}
        override fun login(username: String, password: String) = true
        override fun enterLocalPassiveMode() {}
        override fun setFileType(fileType: Int) = true
        override fun isConnected() = !closed
        override fun disconnect() { closed = true }
        override fun changeWorkingDirectory(pathname: String): Boolean {
            visits += pathname
            cwd = pathname
            return pathname in listing
        }
        override fun listFiles(): Array<FTPFile> {
            if (listTimeout) throw SocketTimeoutException("LIST timeout")
            return listing.getValue(cwd).map { n -> FTPFile().apply {
                name = n
                type = if (n == directoryEntry) FTPFile.DIRECTORY_TYPE else FTPFile.FILE_TYPE
                size = bytes.size.toLong()
            } }.toTypedArray()
        }
        override fun retrieveFile(remote: String, local: OutputStream): Boolean {
            transfers += remote
            if (transferTimeout) { local.write(bytes, 0, 5); throw SocketTimeoutException("RETR timeout") }
            if (transferOk) local.write(bytes)
            return transferOk
        }
    }

    private suspend fun download(ftp: FakeFtp) = FtpDownloader.downloadWhuRtsBias(
        "fake.invalid", tmp.root, now, clientFactory = { ftp }, onProgress = {})

    @Test fun choosesLatestRtsOnly() = runBlocking {
        val ftp = FakeFtp(mapOf(dir to listOf(old, name, nrt, name + ".part", "../$name")))
        assertTrue(download(ftp))
        assertEquals(listOf(dir), ftp.visits)
        assertEquals(listOf(name), ftp.transfers)
        assertTrue(ftp.closed)
    }

    @Test fun onlyNrtDoesNotFallbackToNrt() = runBlocking {
        val ftp = FakeFtp(mapOf(dir to listOf(nrt)))
        assertFalse(download(ftp))
        assertTrue(ftp.transfers.isEmpty())
    }

    @Test fun sameNameIsDownloadedAgainRatherThanCached() = runBlocking {
        val dest = tmp.root.resolve(name).apply { writeBytes(gzip("old daily partial content")) }
        val ftp = FakeFtp(bytes = gzip("updated daily partial content"))
        assertTrue(download(ftp))
        assertEquals(listOf(name), ftp.transfers)
        assertArrayEquals(ftp.bytes, dest.readBytes())
    }

    @Test fun failedTransferPreservesOldFile() = runBlocking {
        val dest = tmp.root.resolve(name).apply { writeText("keep existing") }
        assertFalse(download(FakeFtp(transferOk = false)))
        assertEquals("keep existing", dest.readText())
        assertFalse(tmp.root.resolve("$name.part").exists())
    }

    @Test fun invalidGzipPreservesOldFile() = runBlocking {
        val dest = tmp.root.resolve(name).apply { writeText("keep existing") }
        assertFalse(download(FakeFtp(bytes = "bad compressed file payload".toByteArray())))
        assertEquals("keep existing", dest.readText())
        assertFalse(tmp.root.resolve("$name.part").exists())
    }

    @Test fun plainBiaAndDirectoryEntries() = runBlocking {
        val plain = name.removeSuffix(".gz")
        val ftp = FakeFtp(mapOf(dir to listOf(name, plain)), directoryEntry = name)
        assertTrue(download(ftp))
        assertEquals(listOf(plain), ftp.transfers)
    }

    @Test fun validatesCalendarAndIgnoresFutureIssues() {
        assertEquals(name, RtsBiasPolicy.latest(listOf(old, name, name.removeSuffix(".gz")), now))
        assertNull(RtsBiasPolicy.latest(listOf(nrt, name + ".part", "../$name",
            name.replace("20262980000", "20263670000"), name.replace("20262980000", "20262982400"),
            name.replace("20262980000", "20262990000"), name.replace("_01D_", "_00D_")), now))
    }

    @Test fun gpsYearBoundaryAndPreviousYearFallback() = runBlocking {
        val utc = Instant.parse("2025-12-31T23:59:50Z").toEpochMilli()
        assertEquals(listOf(dir, "/pub/whu/phasebias/2025/bias"), RtsBiasPolicy.directories(utc))
        val prior = "WUM0MGXRTS_20253650000_01D_05M_OSB.BIA.gz"
        val ftp = FakeFtp(mapOf(dir to emptyList(), "/pub/whu/phasebias/2025/bias" to listOf(prior)))
        assertTrue(FtpDownloader.downloadWhuRtsBias("fake.invalid", tmp.root, utc,
            clientFactory = { ftp }, onProgress = {}))
        assertEquals(listOf(prior), ftp.transfers)
    }

    @Test fun timeoutReconnectsForRtsListingAndTransfer() = runBlocking {
        for (listingTimeout in listOf(true, false)) {
            val clients = mutableListOf<FakeFtp>()
            assertTrue(FtpDownloader.downloadWhuRtsBias("fake.invalid", tmp.root, now,
                clientFactory = { FakeFtp(listTimeout = clients.isEmpty() && listingTimeout,
                    transferTimeout = clients.isEmpty() && !listingTimeout).also { clients += it } },
                onProgress = {}))
            assertEquals(2, clients.size)
            assertTrue(clients.all { it.closed })
            assertFalse(tmp.root.resolve("$name.part").exists())
        }
    }

    @Test fun persistentTimeoutFailsAndPreservesOldFile() = runBlocking {
        val oldFile = tmp.root.resolve(name).apply { writeText("keep") }
        val clients = mutableListOf<FakeFtp>()
        assertFalse(FtpDownloader.downloadWhuRtsBias("fake.invalid", tmp.root, now,
            clientFactory = { FakeFtp(transferTimeout = true).also { clients += it } }, onProgress = {}))
        assertEquals(3, clients.size)
        assertTrue(clients.all { it.closed })
        assertEquals("keep", oldFile.readText())
        assertFalse(tmp.root.resolve("$name.part").exists())
    }

    @Test fun liveSelectionIgnoresNrtMtimeAndUsesNewestRtsIssue() {
        tmp.root.resolve(old.removeSuffix(".gz")).apply { writeText("old"); setLastModified(now + 2000) }
        tmp.root.resolve(nrt.removeSuffix(".gz")).apply { writeText("nrt"); setLastModified(now + 3000) }
        val expected = tmp.root.resolve(name.removeSuffix(".gz")).apply { writeText("newest"); setLastModified(now) }
        assertEquals(expected, LivePppProducts.resolveBiasFile(tmp.root, now))
    }

    @Test(expected = IllegalArgumentException::class) fun liveSelectionWithoutRtsHasExplicitFailure() {
        tmp.root.resolve(nrt.removeSuffix(".gz")).writeText("nrt")
        LivePppProducts.resolveBiasFile(tmp.root, now)
    }

    @Test fun liveSelectionRefreshesUnpackedDailyFile() {
        val plain = tmp.root.resolve(name.removeSuffix(".gz")).apply { writeText("old"); setLastModified(now - 10_000) }
        tmp.root.resolve(name).apply { writeBytes(gzip("updated")); setLastModified(now) }
        assertEquals(plain, LivePppProducts.resolveBiasFile(tmp.root, now))
        assertEquals("updated", plain.readText())
    }

    @Test fun publicLatestRtsDownload() = runBlocking {
        assumeTrue(System.getenv("GNSS_PUBLIC_RTS_BIA_SMOKE") == "1")
        assertTrue(FtpDownloader.downloadWhuRtsBias("igs.gnsswhu.cn", tmp.root, onProgress = { println(it) }))
        val file = tmp.root.listFiles().orEmpty().single { !it.name.endsWith(".part") }
        assertTrue(file.name.startsWith("WUM0MGXRTS_"))
        assertTrue(FtpDownloader.reusableLocalFile(file))
        println("PUBLIC_RTS_BIA_OK,name=${file.name},bytes=${file.length()}")
    }
}
