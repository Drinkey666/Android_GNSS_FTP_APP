package com.example.ftpget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

/** One user-requested historical product download. No GNSS, PPP or settings. */
@RunWith(AndroidJUnit4::class)
class HistoricalRtsDownloadTest {
    @Test(timeout = 60000L)
    fun downloadRequestedBias() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "diagnostic_downloads_20261026").apply { mkdirs() }
        val name = InstrumentationRegistry.getArguments().getString("bias_filename")
            ?: "WUM0MGXRTS_20260760000_01D_05M_OSB.BIA.gz"
        require(Regex("WUM0MGXRTS_2026[0-9]{3}0000_01D_05M_OSB\\.BIA\\.gz").matches(name))
        val result = File(dir, name)
        assertTrue("Retain existing product, do not overwrite", !result.exists())
        val part = File(dir, "$name.part")
        val ftp = FTPClient().apply {
            connectTimeout = 10000
            defaultTimeout = 10000
            setDataTimeout(Duration.ofSeconds(20))
            setUseEPSVwithIPv4(true)
        }
        try {
            ftp.connect("igs.gnsswhu.cn", 21)
            ftp.soTimeout = 10000
            assertTrue(ftp.login("anonymous", "anonymous@"))
            assertTrue(ftp.setFileType(FTP.BINARY_FILE_TYPE))
            ftp.enterLocalPassiveMode()
            val path = "/pub/whu/phasebias/2026/bias/$name"
            assertTrue("SIZE unavailable", ftp.sendCommand("SIZE", path) == 213)
            val expected = ftp.replyString.trim().substringAfter(' ').trim().toLong()
            part.outputStream().use { assertTrue("FTP download failed", ftp.retrieveFile(path, it)) }
            assertTrue("Incomplete file", part.length() == expected)
            assertTrue(part.renameTo(result))
            println("RTS_DOWNLOAD_COMPLETE bytes=$expected file=${result.absolutePath}")
        } finally {
            runCatching { if (ftp.isConnected) ftp.disconnect() }
        }
    }
}
