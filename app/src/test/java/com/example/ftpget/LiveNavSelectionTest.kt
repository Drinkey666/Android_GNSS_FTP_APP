package com.example.ftpget

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LiveNavSelectionTest {
    @get:Rule val tmp = TemporaryFolder()
    private val now = Instant.parse("2026-10-18T01:30:00Z").toEpochMilli()

    @Test fun corruptTodayDoesNotHideSuccessfullyDownloadedYesterdayNav() {
        tmp.newFile("BRDC00WRD_R_20262910000_01D_MN.rnx").writeText("broken old download")
        val previous = tmp.newFile("BRDC00WRD_R_20262900000_01D_MN.rnx").apply {
            writeText(BroadcastNavPolicyTest.navFixture())
        }
        assertEquals(previous.name, LivePppProducts.resolveNavFile(tmp.root, now).name)
    }

    @Test fun downloadMtimeNeverMakesOlderNavOverrideNewerProductDate() {
        val fresh = tmp.newFile("BRDC00WRD_R_20262910000_01D_MN.rnx").apply {
            writeText(BroadcastNavPolicyTest.navFixture()); setLastModified(1L)
        }
        tmp.newFile("BRDC00WRD_R_20262900000_01D_MN.rnx").writeText(BroadcastNavPolicyTest.navFixture())
        assertEquals(fresh.name, LivePppProducts.resolveNavFile(tmp.root, now).name)
    }

    @Test fun damagedGzipDoesNotClobberValidPlainNav() {
        val fresh = tmp.newFile("BRDC00WRD_R_20262910000_01D_MN.rnx").apply {
            writeText(BroadcastNavPolicyTest.navFixture())
        }
        tmp.newFile(fresh.name + ".gz").writeText("invalid gzip")
        assertEquals(fresh.name, LivePppProducts.resolveNavFile(tmp.root, now).name)
        assertTrue(BroadcastNavPolicy.validRinex3(fresh, false))
    }
}
