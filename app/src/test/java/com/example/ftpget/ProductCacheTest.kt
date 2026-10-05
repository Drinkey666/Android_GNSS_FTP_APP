package com.example.ftpget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class ProductCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ftpCacheRequiresExactRemoteSize() {
        val file = temporary.newFile("orbit.sp3")
        file.writeText("product")
        assertTrue(FtpDownloader.reusableLocalFile(file, 7L))
        assertFalse(FtpDownloader.reusableLocalFile(file, 8L))
        assertFalse(FtpDownloader.reusableLocalFile(file, 0L))
    }

    @Test fun verifiedGzipIsReusableWithUnknownRemoteSize() {
        val file = temporary.newFile("orbit.sp3.gz")
        GZIPOutputStream(FileOutputStream(file)).use { it.write(ByteArray(1024) { 42 }) }
        assertTrue(FtpDownloader.reusableLocalFile(file, 0L))
        assertTrue(FtpDownloader.reusableLocalFile(file, -1L))
        assertFalse(FtpDownloader.reusableLocalFile(file, file.length() + 1L))
    }

    @Test fun verifiedPublishReplacesOlderIncompleteFile() {
        val target = temporary.newFile("orbit.sp3")
        target.writeText("incomplete")
        val part = temporary.newFile("orbit.sp3.part")
        part.writeText("complete product")
        FtpDownloader.publishDownload(part, target)
        assertTrue(target.readText() == "complete product")
        assertFalse(part.exists())
    }

    @Test fun forecastCacheRejectsTruncatedGzip() {
        val file = temporary.newFile("forecast.inx.gz")
        GZIPOutputStream(FileOutputStream(file)).use { it.write(ByteArray(1024) { 42 }) }
        assertTrue(FtpDownloader.reusableLocalFile(file))
        file.writeBytes(file.readBytes().dropLast(4).toByteArray())
        assertFalse(FtpDownloader.reusableLocalFile(file))
    }

    @Test fun vmf3CacheRequiresMatchingEpochAndFullGrid() {
        val file = temporary.newFile("VMF3_20260927.H00")
        file.writeText("! Epoch: 2026 09 27 00 00  0.0\n" +
            "-87.5 352.5 0.00116695 0.00052151 1.5767 0.0051\n".repeat(2_592))
        assertTrue(FtpDownloader.reusableVmf3File(file, 2026, 9, 27, 0))
        assertFalse(FtpDownloader.reusableVmf3File(file, 2026, 9, 27, 6))
        file.writeText("! Epoch: 2026 09 27 00 00  0.0\n" +
            "-87.5 352.5 0.00116695 0.00052151 1.5767 0.0051\n".repeat(2_591))
        assertFalse(FtpDownloader.reusableVmf3File(file, 2026, 9, 27, 0))
    }
}
