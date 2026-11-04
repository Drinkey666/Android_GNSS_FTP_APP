package com.example.ftpget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** 文件复制/同名保护是纯 Kotlin；手机目录授权和文档提供者另需真机验证。 */
class PublicGnssExporterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun recognizesDownloadedAndBundledProductTypes() {
        for (name in listOf("WUM_ORB.SP3", "WUM_ORB.SP3.gz", "WUM_CLK.CLK.GZ",
            "WUM_OSB.BIA.gz", "COD_GIM.INX", "igs20.atx", "orography_ell_5x5",
            "VMF3_20261005.H00", "VMF3_20261005.H18",
            "BRDC00IGS_R_20262780000_01D_MN.rnx.gz", "brdc2780.26n")) {
            assertTrue(name, isGnssProductName(name))
        }
    }

    @Test fun excludesMeasurementsCredentialsAndIncompleteDownloads() {
        for (name in listOf("RawData_20261005_120000.txt", "GEOL_MO.rnx",
            "vmf3.local.properties", "vmf3.local.properties.example", "PPP_Result.pos",
            "PPP_Result.trace", "ORB.SP3.part", "CLK.CLK.tmp", "VMF3_20261005.H03")) {
            assertFalse(name, isGnssProductName(name))
        }
    }

    @Test fun productInventorySkipsDirectoriesAndEmptyFiles() {
        val directory = temporary.newFolder()
        java.io.File(directory, "valid.SP3").writeText("product")
        java.io.File(directory, "empty.CLK").createNewFile()
        java.io.File(directory, "folder.BIA").mkdir()
        java.io.File(directory, "capture.txt").writeText("Raw")
        java.io.File(directory, "incomplete.SP3.part").writeText("partial")
        assertEquals(listOf("valid.SP3"), gnssProductFiles(directory).map { it.name })
    }

    @Test fun digestMatchesKnownSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            gnssExportDigest(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test fun sameLengthModifiedProductHasDifferentDigest() {
        assertFalse(gnssExportDigest(ByteArrayInputStream("abc".toByteArray())) ==
            gnssExportDigest(ByteArrayInputStream("abd".toByteArray())))
    }

    @Test fun digestIndependentOfReadChunkSize() {
        val bytes = ByteArray(25_000) { (it % 251).toByte() }
        val slowInput = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(7, length))
        }
        assertEquals(gnssExportDigest(ByteArrayInputStream(bytes)), gnssExportDigest(slowInput))
    }

    @Test fun keepsOriginalNameWhenUnused() {
        assertEquals("RawData_20261005_120000.txt",
            uniqueGnssExportName("RawData_20261005_120000.txt", emptySet()))
    }

    @Test fun duplicateTxtDoesNotOverwriteExistingFile() {
        assertEquals("RawData_1.txt",
            uniqueGnssExportName("RawData.txt", setOf("RawData.txt")))
    }

    @Test fun repeatedExportsKeepIncrementingSuffix() {
        assertEquals("file_3.rnx", uniqueGnssExportName("file.rnx",
            setOf("file.rnx", "file_1.rnx", "file_2.rnx")))
    }

    @Test fun preservesLastExtensionAndFilenameWithoutExtension() {
        assertEquals("data.obs_1.rnx", uniqueGnssExportName("data.obs.rnx", setOf("data.obs.rnx")))
        assertEquals("capture_1", uniqueGnssExportName("capture", setOf("capture")))
    }

    @Test fun copiesUtf8AndCrLfWithoutReencoding() {
        val bytes = "# 中文说明\r\nRaw,1,,3\r\n".toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), copyGnssExport(ByteArrayInputStream(bytes), output,
            bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun copiesMultiBufferFileExactly() {
        val bytes = ByteArray(100_000) { (it % 256).toByte() }
        val output = ByteArrayOutputStream()
        copyGnssExport(ByteArrayInputStream(bytes), output, bytes.size.toLong())
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test(expected = IOException::class) fun truncatedSourceIsNotReportedAsSuccess() {
        copyGnssExport(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3L)
    }

    @Test(expected = IOException::class) fun longerSourceIsNotReportedAsSuccess() {
        copyGnssExport(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 1L)
    }

    @Test(expected = IOException::class) fun destinationFailurePropagates() {
        val failingOutput = object : OutputStream() {
            override fun write(value: Int) { throw IOException("simulated write failure") }
        }
        copyGnssExport(ByteArrayInputStream(byteArrayOf(1)), failingOutput, 1L)
    }
}
