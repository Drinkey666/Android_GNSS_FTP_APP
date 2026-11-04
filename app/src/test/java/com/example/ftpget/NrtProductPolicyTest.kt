package com.example.ftpget

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NrtProductPolicyTest {
    private val now = Instant.parse("2026-10-18T01:00:00Z").toEpochMilli()
    private fun names(stamp: String) = listOf("WUM0MGXNRT_${stamp}_02D_05M_ORB.SP3.gz",
        "WUM0MGXNRT_${stamp}_02D_05M_CLK.CLK.gz")

    @Test fun choosesLatestAvailableTodayOrYesterday() {
        val pairs = NrtProductPolicy.pairs(names("20262901800") + names("20262910000"), now)
        assertEquals(2, pairs.size)
        assertTrue(pairs.first().orbit.contains("20262910000"))
    }

    @Test fun missingTodayUsesLatestStillValidYesterdayIssue() {
        assertTrue(NrtProductPolicy.pairs(names("20262901800"), now).single().clock.contains("20262901800"))
    }

    @Test fun neverMixesDifferentIssueTimes() {
        val candidates = names("20262901800") + "WUM0MGXNRT_20262910000_02D_05M_ORB.SP3.gz"
        val pair = NrtProductPolicy.pairs(candidates, now).single()
        assertTrue(pair.orbit.contains("20262901800"))
        assertTrue(pair.clock.contains("20262901800"))
    }

    @Test fun rejectsExpiredAndFutureIssues() {
        assertTrue(NrtProductPolicy.pairs(names("20262880000") + names("20262910600"), now).isEmpty())
    }

    @Test fun rejectsExactEndOfCoverage() {
        assertTrue(NrtProductPolicy.pairs(names("20262890000"), Instant.parse("2026-10-18T00:00:00Z").toEpochMilli()).isEmpty())
    }

    @Test fun choosesThirtySecondClockOverFiveMinuteClock() {
        val pair = NrtProductPolicy.pairs(names("20262910000") +
            "WUM0MGXNRT_20262910000_02D_30S_CLK.CLK.gz", now).single()
        assertTrue(pair.clock.contains("_30S_"))
    }

    @Test fun sundayScansCurrentAndPreviousGpsWeek() {
        assertEquals(listOf("/pub/gnss/products/mgex/2400", "/pub/gnss/products/mgex/2399"),
            NrtProductPolicy.weekDirectories(Instant.parse("2026-01-04T01:00:00Z").toEpochMilli()))
    }

    @Test fun rejectsInvalidDoyAndZeroDuration() {
        assertNull(NrtProductPolicy.parse("WUM0MGXNRT_20263670000_02D_05M_ORB.SP3.gz"))
        assertNull(NrtProductPolicy.parse("WUM0MGXNRT_20262910000_00D_05M_CLK.CLK.gz"))
    }
}
