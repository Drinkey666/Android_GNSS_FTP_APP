package com.example.ftpget

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional desktop regression using a real Android GNSS Logger capture.
 * Set GNSS_RAW_TEST_FILE and GNSS_RINEX_TEST_OUTPUT to enable it.
 */
class RinexConverterRegressionTest {
    @Test
    fun convertsRealCaptureWhenConfigured() {
        val input = System.getenv("GNSS_RAW_TEST_FILE")?.let(::File)
        val output = System.getenv("GNSS_RINEX_TEST_OUTPUT")?.let(::File)
        assumeTrue(input != null && input.isFile && output != null)
        val inputFile = requireNotNull(input)
        val outputFile = requireNotNull(output)

        outputFile.parentFile?.mkdirs()
        assertTrue(RinexConverter.convert(inputFile, outputFile))
        assertTrue(outputFile.isFile && outputFile.length() > 0L)
        assertTrue(RinexConverter.lastStats.codeAccepted > 0)
        assertTrue(RinexConverter.lastStats.phaseAccepted > 0)
        assertTrue(outputFile.useLines { lines ->
            lines.take(80).any { it.contains("END OF HEADER") }
        })
        assertTrue(outputFile.useLines { lines ->
            lines.take(20).none { it.contains("null", ignoreCase = true) }
        })
    }
}
