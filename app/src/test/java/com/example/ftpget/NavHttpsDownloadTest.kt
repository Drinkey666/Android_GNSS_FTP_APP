package com.example.ftpget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.zip.GZIPOutputStream

class NavHttpsDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root = "https://igs.bkg.bund.de/root_ftp"
    private val today = "/IGS/BRDC/2026/291/"
    private val yesterday = "/IGS/BRDC/2026/290/"
    private val now = Instant.parse("2026-10-18T01:30:00Z").toEpochMilli()
    private val name = "BRDC00WRD_R_20262910000_01D_MN.rnx.gz"
    private val oldName = "BRDC00WRD_S_20262900000_01D_MN.rnx.gz"

    private class Response(url: URL, private val code: Int, private val body: ByteArray) : HttpURLConnection(url) {
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getContentLengthLong() = body.size.toLong()
    }
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { b ->
        GZIPOutputStream(b).use { it.write(text.toByteArray()) }
    }.toByteArray()
    private fun listing(vararg names: String) = names.joinToString("\n") { "<a href=\"$it\">$it</a>" }.toByteArray()

    private fun fetch(responses: Map<String, ByteArray>, visits: MutableList<String> = mutableListOf(),
                      log: MutableList<String> = mutableListOf()): Boolean = runBlocking {
        FtpDownloader.downloadNavOverHttps(listOf(today, yesterday), tmp.root, now, 3,
            connectionFactory = { url ->
                visits += url.toString()
                val bytes = responses[url.toString()]
                Response(url, if (bytes == null) 404 else 200, bytes ?: byteArrayOf())
            }, onProgress = { log += it })
    }

    @Test fun http404TodayFallsBackAndReportsCorrectDate() {
        val visits = mutableListOf<String>()
        val log = mutableListOf<String>()
        assertTrue(fetch(mapOf(root+yesterday to listing(oldName),
            root+yesterday+oldName to gzip(BroadcastNavPolicyTest.navFixture())), visits, log))
        assertEquals(listOf(root+today, root+yesterday, root+yesterday+oldName), visits)
        assertTrue(log.any { it.startsWith("⚠️ 使用回退广播星历") })
    }

    @Test fun actualWrdRinexThreeNameIsAccepted() {
        assertTrue(fetch(mapOf(root+today to listing("../", "?C=M;O=A", name),
            root+today+name to gzip(BroadcastNavPolicyTest.navFixture()))))
        assertTrue(tmp.root.resolve(name).exists())
    }

    @Test fun brokenFileDoesNotReplaceLocalAndNextSourceIsTried() {
        val igs = name.replace("WRD", "IGS")
        val existing = gzip(BroadcastNavPolicyTest.navFixture())
        tmp.root.resolve(igs).writeBytes(existing)
        assertTrue(fetch(mapOf(root+today to listing(igs, name),
            root+today+igs to existing.dropLast(4).toByteArray(), root+today+name to existing)))
        assertArrayEquals(existing, tmp.root.resolve(igs).readBytes())
        assertFalse(tmp.root.listFiles().orEmpty().any { it.name.endsWith(".part") })
    }

    @Test fun htmlInGzipResponseIsNotPublished() {
        assertFalse(fetch(mapOf(root+today to listing(name), root+today+name to "<html>Error</html>".toByteArray())))
        assertFalse(tmp.root.resolve(name).exists())
    }

    @Test fun repeatedRequestStillDownloadsInsteadOfCheckingLocalCache() {
        val visits = mutableListOf<String>()
        val responses = mapOf(root+today to listing(name), root+today+name to gzip(BroadcastNavPolicyTest.navFixture()))
        assertTrue(fetch(responses, visits))
        assertTrue(fetch(responses, visits))
        assertEquals(2, visits.count { it == root+today+name })
    }

    @Test fun listingRejectsExternalPathsAndUnsupportedRinex4Names() {
        assertEquals(listOf(name), BroadcastNavPolicy.listingNames(
            "<a href='/outside/$name'>bad</a><a href='https://elsewhere/$name'>bad</a>" +
                "<a href='BRD400DLR_S_20262910000_01D_MN.rnx.gz'>v4</a>" +
                "<a HREF='$name'>good</a>"))
    }
}
