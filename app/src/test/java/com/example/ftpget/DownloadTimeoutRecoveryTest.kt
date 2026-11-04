package com.example.ftpget

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream

/** 验证实际下载器路径，不仅验证重试工具本身。无公网或真实账号依赖。 */
class DownloadTimeoutRecoveryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val name = "WUM0MGXNRT_20262930000_01D_01D_OSB.BIA.gz"
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { b ->
        GZIPOutputStream(b).use { it.write(text.toByteArray()) }
    }.toByteArray()

    private class FakeFtp(private val name: String, private val bytes: ByteArray,
                          private val timeout: Boolean, private val timeoutListing: Boolean = false) : FTPClient() {
        var closed = false
        override fun connect(hostname: String, port: Int) {}
        override fun login(username: String, password: String) = true
        override fun enterLocalPassiveMode() {}
        override fun setFileType(fileType: Int) = true
        override fun isConnected() = !closed
        override fun disconnect() { closed = true }
        override fun changeWorkingDirectory(pathname: String) = true
        override fun listFiles(): Array<FTPFile> {
            if (timeoutListing) throw SocketTimeoutException("LIST read timed out")
            return arrayOf(FTPFile().apply { this.name = this@FakeFtp.name; type = FTPFile.FILE_TYPE; size = bytes.size.toLong() })
        }
        override fun retrieveFile(remote: String, local: OutputStream): Boolean {
            if (timeout) { local.write(bytes, 0, 5); throw SocketTimeoutException("RETR read timed out") }
            local.write(bytes)
            return true
        }
    }

    @Test fun biaTransferTimeoutReconnectsAndRetriesWithoutDamagingExistingFile() = runBlocking {
        val bytes = gzip("valid OSB transport fixture")
        val clients = mutableListOf<FakeFtp>()
        val old = tmp.root.resolve(name).apply { writeText("keep old until successful publication") }
        val log = mutableListOf<String>()
        val ok = FtpDownloader.downloadWhuNrtBias("fake.invalid", "/pub/gnss/products/mgex/2441", tmp.root,
            clientFactory = {
                FakeFtp(name, bytes, timeout = clients.isEmpty()).also { clients += it }
            }, onProgress = { log += it })
        assertTrue(ok)
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.closed })
        assertArrayEquals(bytes, old.readBytes())
        assertFalse(tmp.root.resolve("$name.part").exists())
        assertTrue(log.any { it.contains("重新连接") })
    }

    @Test fun biaListingTimeoutAlsoUsesFreshSession() = runBlocking {
        val clients = mutableListOf<FakeFtp>()
        assertTrue(FtpDownloader.downloadWhuNrtBias("fake.invalid", "/pub/gnss/products/mgex/2441", tmp.root,
            clientFactory = { FakeFtp(name, gzip("ok"), false, clients.isEmpty()).also { clients += it } }, onProgress = {}))
        assertEquals(2, clients.size)
        assertTrue(clients.all { it.closed })
    }

    @Test fun biaPersistentTimeoutStopsAfterThreeAttemptsAndPreservesOldFile() = runBlocking {
        val clients = mutableListOf<FakeFtp>()
        val old = tmp.root.resolve(name).apply { writeText("previous complete product") }
        val log = mutableListOf<String>()
        assertFalse(FtpDownloader.downloadWhuNrtBias("fake.invalid", "/pub/gnss/products/mgex/2441", tmp.root,
            clientFactory = { FakeFtp(name, gzip("partial"), true).also { clients += it } },
            onProgress = { log += it }))
        assertEquals(3, clients.size)
        assertTrue(clients.all { it.closed })
        assertEquals("previous complete product", old.readText())
        assertFalse(tmp.root.resolve("$name.part").exists())
        assertTrue(log.any { it.contains("重试耗尽") })
    }

    private class Response(url: URL, private val body: ByteArray, private val timeout: Boolean,
                           private val status: Int = 200) : HttpURLConnection(url) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream {
            if (timeout) throw SocketTimeoutException("read timed out opening input")
            return ByteArrayInputStream(body)
        }
    }

    @Test fun httpsTimeoutOpeningInputClosesPartialAndRetriesSameUrl() = runBlocking {
        val connections = mutableListOf<Response>()
        val target = tmp.root.resolve("test.H00")
        target.writeText("old")
        val urls = mutableListOf<String>()
        val status = FtpDownloader.downloadHttpFile("https://example.invalid/test.H00", target, "VMF3",
            connectionFactory = { url ->
                urls += url.toString()
                Response(url, "complete data".toByteArray(), connections.isEmpty()).also { connections += it }
            }, validate = { it.readText() == "complete data" }, onTransferProgress = { _, _, _ -> }, onProgress = {})
        assertEquals(200, status)
        assertEquals(2, connections.size)
        assertEquals(1, urls.distinct().size)
        assertTrue(connections.all { it.closed })
        assertEquals("complete data", target.readText())
        assertFalse(tmp.root.resolve("test.H00.part").exists())
    }

    @Test fun http404IsNotRetriedAndDoesNotReplaceLocalFile() = runBlocking {
        var calls = 0
        val target = tmp.root.resolve("missing.H06").apply { writeText("old") }
        assertEquals(404, FtpDownloader.downloadHttpFile("https://example.invalid/missing.H06", target, "VMF3",
            connectionFactory = { url -> calls++; Response(url, byteArrayOf(), false, 404) },
            validate = { true }, onTransferProgress = { _, _, _ -> }, onProgress = {}))
        assertEquals(1, calls)
        assertEquals("old", target.readText())
    }

    @Test fun http503RetriesAndPublishesOnlySuccessfulBody() = runBlocking {
        var calls = 0
        val target = tmp.root.resolve("test.INX")
        assertEquals(200, FtpDownloader.downloadHttpFile("https://example.invalid/test.INX", target, "IONEX",
            connectionFactory = { url -> calls++; Response(url, "ok".toByteArray(), false, if (calls == 1) 503 else 200) },
            validate = { it.readText() == "ok" }, onTransferProgress = { _, _, _ -> }, onProgress = {}))
        assertEquals(2, calls)
        assertEquals("ok", target.readText())
    }

    @Test fun httpsMidBodyTimeoutClosesStreamsAndPreservesOldFileAfterExhaustion() = runBlocking {
        var calls = 0
        var closedStreams = 0
        var closedConnections = 0
        val target = tmp.root.resolve("test.H12").apply { writeText("previous valid grid") }
        try {
            FtpDownloader.downloadHttpFile("https://example.invalid/test.H12", target, "VMF3",
                connectionFactory = { url ->
                    calls++
                    object : HttpURLConnection(url) {
                        override fun connect() {}
                        override fun disconnect() { closedConnections++ }
                        override fun usingProxy() = false
                        override fun getResponseCode() = 200
                        override fun getInputStream() = object : InputStream() {
                            var reads = 0
                            override fun read(): Int {
                                if (reads++ == 0) return 65
                                throw SocketTimeoutException("mid-body timeout")
                            }
                            override fun close() { closedStreams++ }
                        }
                    }
                }, validate = { true }, onTransferProgress = { _, _, _ -> }, onProgress = {})
            fail("exhausted timeout must be reported")
        } catch (_: SocketTimeoutException) {
            assertEquals(3, calls)
            assertEquals(3, closedStreams)
            assertEquals(3, closedConnections)
            assertEquals("previous valid grid", target.readText())
            assertFalse(tmp.root.resolve("test.H12.part").exists())
        }
    }
}
