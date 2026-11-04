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

class NrtBiasDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val dir = "/pub/gnss/products/mgex/2440"
    private val old = "WUM0MGXNRT_20262892200_01D_01D_OSB.BIA.gz"
    private val newest = "WUM0MGXNRT_20262900200_01D_01D_OSB.BIA.gz"
    private val rts = "WUM0MGXRTS_20262910000_01D_05M_OSB.BIA.gz"
    private val bytes = ByteArrayOutputStream().also { b ->
        GZIPOutputStream(b).use { it.write("OSB transport fixture".toByteArray()) }
    }.toByteArray()

    private inner class FakeFtp(val names: List<String>, val allowed: String = dir,
                               val transferOk: Boolean = true, val directoryEntry: String? = null) : FTPClient() {
        val visits = mutableListOf<String>()
        val transfers = mutableListOf<String>()
        var closed = false
        override fun connect(hostname: String, port: Int) {}
        override fun login(username: String, password: String) = true
        override fun enterLocalPassiveMode() {}
        override fun setFileType(fileType: Int) = true
        override fun isConnected() = !closed
        override fun disconnect() { closed = true }
        override fun changeWorkingDirectory(pathname: String): Boolean {
            visits += pathname
            return pathname == allowed
        }
        override fun listFiles() = names.map { name -> FTPFile().apply {
            this.name = name
            type = if (name == directoryEntry) FTPFile.DIRECTORY_TYPE else FTPFile.FILE_TYPE
            size = bytes.size.toLong()
        } }.toTypedArray()
        override fun retrieveFile(remote: String, local: OutputStream): Boolean {
            transfers += remote
            if (transferOk) local.write(bytes)
            return transferOk
        }
    }

    @Test fun mixedDirectoryChoosesLatestNrtOnly() = runBlocking {
        val ftp = FakeFtp(listOf(old, newest, rts, newest + ".part", newest.replace("NRT", "FIN")))
        assertTrue(FtpDownloader.downloadWhuNrtBias("fake.invalid", dir, tmp.root,
            clientFactory = { ftp }, onProgress = {}))
        assertEquals(listOf(dir), ftp.visits)
        assertEquals(listOf(newest), ftp.transfers)
        assertTrue(ftp.closed)
        assertEquals(listOf(newest), tmp.root.listFiles()!!.map { it.name })
    }

    @Test fun rtsOnlyDoesNotDownloadOrFallback() = runBlocking {
        val ftp = FakeFtp(listOf(rts))
        val log = mutableListOf<String>()
        assertFalse(FtpDownloader.downloadWhuNrtBias("fake.invalid", dir, tmp.root,
            clientFactory = { ftp }, onProgress = { log += it }))
        assertEquals(listOf(dir), ftp.visits)
        assertTrue(ftp.transfers.isEmpty())
        assertTrue(log.any { it.contains("未找到 NRT BIA") })
    }

    @Test fun transferFailurePreservesExistingFileAndDoesNotUseRts() = runBlocking {
        val file = tmp.root.resolve(newest).apply { writeText("existing file") }
        val ftp = FakeFtp(listOf(newest, rts), transferOk = false)
        assertFalse(FtpDownloader.downloadWhuNrtBias("fake.invalid", dir, tmp.root,
            clientFactory = { ftp }, onProgress = {}))
        assertEquals(listOf(newest), ftp.transfers)
        assertEquals("existing file", file.readText())
        assertFalse(tmp.root.resolve(newest + ".part").exists())
    }

    @Test fun supportsPlainBiaAndSkipsDirectoryEntries() = runBlocking {
        val plain = newest.removeSuffix(".gz")
        val ftp = FakeFtp(listOf(plain, newest), directoryEntry = newest)
        assertTrue(FtpDownloader.downloadWhuNrtBias("fake.invalid", dir, tmp.root,
            clientFactory = { ftp }, onProgress = {}))
        assertEquals(listOf(plain), ftp.transfers)
    }

    @Test fun validatesNrtNamesAndPrefersCompressedDuplicate() {
        assertEquals(newest, NrtBiasPolicy.latest(listOf(newest.removeSuffix(".gz"), newest, old)))
        assertNull(NrtBiasPolicy.latest(listOf(rts, newest + ".part",
            newest.replace("20262900200", "20263670200"), newest.replace("_01D_01D", "_00D_01D"),
            "../$newest", newest.replace("OSB.BIA", "ORB.SP3"))))
    }

    @Test fun pairCallbackKeepsPreviousWeekDirectoryForBia() = runBlocking {
        val orbit = "WUM0MGXNRT_20262892300_02D_05M_ORB.SP3.gz"
        val clock = "WUM0MGXNRT_20262892300_02D_30S_CLK.CLK.gz"
        val ftp = FakeFtp(listOf(orbit, clock, newest))
        var selected: String? = null
        assertTrue(FtpDownloader.downloadLatestWhuNrtPair("fake.invalid", tmp.root,
            referenceUtcMillis = Instant.parse("2026-10-18T01:00:00Z").toEpochMilli(),
            clientFactory = { ftp }, onPairDirectory = { selected = it }, onProgress = {}))
        assertEquals(dir, selected)
        val biasFtp = FakeFtp(listOf(newest, rts))
        assertTrue(FtpDownloader.downloadWhuNrtBias("fake.invalid", checkNotNull(selected), tmp.root,
            clientFactory = { biasFtp }, onProgress = {}))
        assertEquals(listOf(dir), biasFtp.visits)
    }
}
