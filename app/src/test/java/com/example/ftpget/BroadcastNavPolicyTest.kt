package com.example.ftpget

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.zip.GZIPOutputStream

class BroadcastNavPolicyTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun millis(value: String) = Instant.parse(value).toEpochMilli()

    @Test fun missingTodaySearchesPreviousUtcDaysAcrossNewYear() {
        assertEquals(listOf("/IGS/BRDC/2026/001/", "/IGS/BRDC/2025/365/",
            "/IGS/BRDC/2025/364/", "/IGS/BRDC/2025/363/"),
            BroadcastNavPolicy.directories("/IGS/BRDC/2026/001/", 3))
    }

    @Test fun leapYearFallbackIncludesDay366() {
        assertEquals("/IGS/BRDC/2024/366/", BroadcastNavPolicy.directories("/IGS/BRDC/2025/001/", 1)[1])
    }

    @Test fun newestProductEpochWinsRegardlessOfModificationTime() {
        val old = "BRDC00IGS_R_20262900000_01D_MN.rnx.gz"
        val fresh = "BRDC00IGS_R_20262910000_01D_MN.rnx.gz"
        assertEquals(listOf(fresh, old), BroadcastNavPolicy.candidates(listOf(old, fresh), millis("2026-10-18T01:00:00Z")))
    }

    @Test fun morningBeforeLocalEightStillUsesUtcYesterday() {
        // 北京 10 月 18 日 07:00 是 UTC 10 月 17 日 23:00。
        assertEquals(listOf("BRDC00IGS_R_20262900000_01D_MN.rnx.gz"),
            BroadcastNavPolicy.candidates(listOf("BRDC00IGS_R_20262900000_01D_MN.rnx.gz",
                "BRDC00IGS_R_20262910000_01D_MN.rnx.gz"), millis("2026-10-17T23:00:00Z")))
    }

    @Test fun excludesFutureAncientInvalidAndRinex4Names() {
        assertTrue(BroadcastNavPolicy.candidates(listOf("BRDC00IGS_R_20262920000_01D_MN.rnx.gz",
            "BRDC00IGS_R_20262800000_01D_MN.rnx.gz", "BRD400DLR_S_20262910000_01D_MN.rnx.gz",
            "BRDC00IGS_R_20263670000_01D_MN.rnx.gz", "result_MO.rnx"), millis("2026-10-18T01:00:00Z")).isEmpty())
    }

    @Test fun acceptsUncompressedAndDlrMergedRinex3Names() {
        assertEquals(2, BroadcastNavPolicy.candidates(listOf("BRDC00IGS_R_20262910000_01D_MN.rnx",
            "BRDM00DLR_S_20262910000_01D_MN.rnx.gz"), millis("2026-10-18T01:00:00Z")).size)
    }

    @Test fun sameEpochPrefersIgsBeforeDlr() {
        assertTrue(BroadcastNavPolicy.candidates(listOf("BRDM00DLR_S_20262910000_01D_MN.rnx.gz",
            "BRDC00IGS_R_20262910000_01D_MN.rnx.gz"), millis("2026-10-18T01:00:00Z")).first().startsWith("BRDC00IGS"))
    }

    @Test fun validatesPlainAndGzipNavBeforePublishing() {
        val plain = tmp.newFile("nav.rnx").apply { writeText(navFixture()) }
        val gzip = tmp.newFile("nav.gz.part")
        GZIPOutputStream(gzip.outputStream()).use { it.write(navFixture().toByteArray()) }
        assertTrue(BroadcastNavPolicy.validRinex3(plain, false))
        assertTrue(BroadcastNavPolicy.validRinex3(gzip, true))
    }

    @Test fun rejectsHtmlObservationHeaderOnlyAndTruncatedRecord() {
        val file = tmp.newFile()
        for (content in listOf("<html>404</html>", navFixture().replace("N: GNSS", "O: GNSS"),
            navFixture().substringBefore("G01"), navFixture().replace("     3.05", "     4.00"),
            navFixture().substringBefore("\n    "))) {
            file.writeText(content)
            assertFalse(BroadcastNavPolicy.validRinex3(file, false))
        }
    }

    companion object {
        fun navFixture(): String = "     3.05           N: GNSS NAV DATA    M".padEnd(60) +
            "RINEX VERSION / TYPE\n" + "".padEnd(60) + "END OF HEADER\n" +
            "G01 2026 10 18 00 00 00 0.0 0.0 0.0\n" +
            "    0.000000000000D+00 0.000000000000D+00 0.000000000000D+00 0.000000000000D+00\n".repeat(7)
    }
}
