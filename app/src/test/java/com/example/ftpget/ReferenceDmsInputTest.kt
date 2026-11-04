package com.example.ftpget

import org.junit.Assert.*
import org.junit.Test

class ReferenceDmsInputTest {
    private val lat = DmsFields("30", "41", "53.53950012")
    private val lon = DmsFields("104", "3", "7.61929992")

    @Test fun chengduDmsConvertsToExpectedGeodeticDegrees() {
        val r = ReferenceDmsInput.parse(lat, false, lon, false, "459.786").reference!!
        assertEquals(30.6982054167, r.latitude, 1e-12)
        assertEquals(104.0521164722, r.longitude, 1e-12)
        assertEquals(459.786, r.ellipsoidHeightMeters!!, 0.0)
        assertEquals(ReferenceHeightType.ELLIPSOIDAL, r.heightType)
        assertNull(r.heightAnomalyMeters)
    }

    @Test fun southAndWestSetSignsOnce() {
        val r = ReferenceDmsInput.parse(lat, true, lon, true, "-12.5").reference!!
        assertEquals(-30.6982054167, r.latitude, 1e-12)
        assertEquals(-104.0521164722, r.longitude, 1e-12)
        assertEquals(-12.5, r.ellipsoidHeightMeters!!, 0.0)
    }

    @Test fun invalidDegreesAreNotTreatedAsPackedDmsOrDecimalDegrees() {
        for (bad in listOf("", "-30", "30.698205", "91", "NaN", "Infinity", "30°"))
            assertEquals(DmsReferenceError.LATITUDE_DEGREES,
                ReferenceDmsInput.parse(lat.copy(degrees = bad), false, lon, false, "500").error)
        assertEquals(DmsReferenceError.LONGITUDE_DEGREES,
            ReferenceDmsInput.parse(lat, false, lon.copy(degrees = "181"), false, "500").error)
    }

    @Test fun minutesMustBeIntegerAndBelowSixty() {
        for (bad in listOf("", "-1", "60", "1.5", "NaN", "Infinity"))
            assertEquals(DmsReferenceError.LATITUDE_MINUTES,
                ReferenceDmsInput.parse(lat.copy(minutes = bad), false, lon, false, "500").error)
    }

    @Test fun secondsMustBeFiniteAndBelowSixty() {
        for (bad in listOf("", "-0.01", "60", "NaN", "Infinity"))
            assertEquals(DmsReferenceError.LONGITUDE_SECONDS,
                ReferenceDmsInput.parse(lat, false, lon.copy(seconds = bad), false, "500").error)
        assertNotNull(ReferenceDmsInput.parse(lat.copy(seconds = "59.999999"), false, lon, false, "500").reference)
    }

    @Test fun polesAndDateLineOnlyAllowZeroMinutesAndSeconds() {
        val a = DmsFields("90", "0", "0")
        val b = DmsFields("180", "0", "0")
        assertNotNull(ReferenceDmsInput.parse(a, false, b, true, "0").reference)
        assertEquals(DmsReferenceError.LATITUDE_MINUTES,
            ReferenceDmsInput.parse(a.copy(minutes = "1"), false, b, false, "0").error)
        assertEquals(DmsReferenceError.LONGITUDE_SECONDS,
            ReferenceDmsInput.parse(a, false, b.copy(seconds = "0.000001"), false, "0").error)
    }

    @Test fun heightMustBeFiniteAndIsAlwaysEllipsoidal() {
        for (bad in listOf("", "NaN", "Infinity"))
            assertEquals(DmsReferenceError.HEIGHT, ReferenceDmsInput.parse(lat, false, lon, false, bad).error)
        assertNotNull(ReferenceDmsInput.parse(lat, false, lon, false, " -30.25 ").reference)
    }

    @Test fun displayRoundingCarriesSecondsAndMinutes() {
        val rounded = ReferenceDmsInput.fromDecimal(12 + 59 / 60.0 + 59.9999999 / 3600.0)
        assertEquals(DmsFields("13", "0", "0.000000"), rounded)
        val tiny = ReferenceDmsInput.fromDecimal(-0.5 / 3600.0)
        assertEquals(DmsFields("0", "0", "0.500000"), tiny)
    }

    @Test fun displayDmsRoundTripIsWithinHalfMicroarcsecond() {
        for (value in listOf(0.0, 30.6982054167, -45.123456789, 89.999999999999, 90.0)) {
            val dms = ReferenceDmsInput.fromDecimal(value)
            val r = ReferenceDmsInput.parse(dms, value < 0, lon, false, "500").reference!!
            assertEquals(value, r.latitude, 0.5e-6 / 3600.0 + 1e-13)
        }
    }

    @Test fun csvDocumentsWgs84AndRealUsedDecimalValues() {
        val r = ReferenceDmsInput.parse(lat, false, lon, false, "500").reference!!
        val xyz = PositionErrorCalculator.ecef(r.latitude, r.longitude, r.heightMeters)
        val row = PositionErrorCsv.row(r, SolutionCoordinate(2441, 2459.0, 6, xyz[0], xyz[1], xyz[2]), "LIVE")
        assertTrue(row.endsWith(",WGS84\n"))
        assertTrue(row.contains(",ELLIPSOIDAL,"))
        assertEquals(0.0, PositionErrorCalculator.compare(r,
            SolutionCoordinate(2441, 2459.0, 6, xyz[0], xyz[1], xyz[2]))!!.spatialMeters!!, 0.0)
    }
}
