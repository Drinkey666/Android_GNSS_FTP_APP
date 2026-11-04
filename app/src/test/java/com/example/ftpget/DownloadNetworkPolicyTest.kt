package com.example.ftpget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class DownloadNetworkPolicyTest {
    @Test fun timeoutRetriesSameActionThreeTimesWithBoundedBackoff() = runBlocking {
        var calls = 0
        val waits = mutableListOf<Long>()
        val logs = mutableListOf<String>()
        val result = DownloadNetworkPolicy.retry("BIA test.BIA.gz", { logs += it }, { waits += it }) {
            calls++
            if (calls < 3) throw SocketTimeoutException("read timed out")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(1000L, 3000L), waits)
        assertTrue(logs.all { it.contains("BIA test.BIA.gz") })
    }

    @Test fun persistentTimeoutDoesNotRetryForever() = runBlocking {
        var calls = 0
        try {
            DownloadNetworkPolicy.retry("NAV", {}, {}) { calls++; throw SocketTimeoutException() }
            fail("expected timeout")
        } catch (_: SocketTimeoutException) { assertEquals(3, calls) }
    }

    @Test fun localIoFailureIsNotRetried() = runBlocking {
        var calls = 0
        try {
            DownloadNetworkPolicy.retry("local storage", {}, {}) { calls++; throw IOException("disk unavailable") }
            fail("expected IOException")
        } catch (_: IOException) { assertEquals(1, calls) }
    }

    @Test fun cancellationIsNeverConvertedIntoDownloadFailureOrRetried() = runBlocking {
        var calls = 0
        try {
            DownloadNetworkPolicy.download("cancel", {}) { calls++; throw CancellationException("stop") }
            fail("expected cancellation")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }

    @Test fun wrappedReadTimeoutAndTlsEofAreTransient() {
        assertTrue(DownloadNetworkPolicy.isTransient(IOException("copy", SocketTimeoutException())))
        assertTrue(DownloadNetworkPolicy.isTransient(IOException("TLS peer closed", EOFException())))
        assertFalse(DownloadNetworkPolicy.isTransient(IllegalArgumentException()))
    }

    @Test fun status404And401AreNotTreatedAsTimeout() {
        DownloadNetworkPolicy.checkHttpStatus(401)
        DownloadNetworkPolicy.checkHttpStatus(404)
        try {
            DownloadNetworkPolicy.checkHttpStatus(503)
            fail("503 must be retryable")
        } catch (e: DownloadNetworkPolicy.TemporaryHttpFailure) {
            assertTrue(DownloadNetworkPolicy.isTransient(e))
        }
    }

    @Test fun allFtpAndHttpTimeoutsAreExplicit() {
        val ftp = FTPClient()
        DownloadNetworkPolicy.configure(ftp)
        assertEquals(20_000, ftp.connectTimeout)
        assertEquals(45_000, ftp.defaultTimeout)
        assertEquals(90_000L, ftp.dataTimeout.toMillis())
        val http = object : HttpURLConnection(URL("https://example.invalid")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
        }
        DownloadNetworkPolicy.configure(http)
        assertEquals(20_000, http.connectTimeout)
        assertEquals(90_000, http.readTimeout)
    }
}
