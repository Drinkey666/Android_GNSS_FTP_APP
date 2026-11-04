package com.example.ftpget

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ReferencePositionTest {
    private fun fix(x: Double, y: Double, z: Double, q: Int = 6) = SolutionCoordinate(2441, 2458.974, q, x, y, z)
    private fun atReference(r: ReferencePosition): SolutionCoordinate {
        val p = PositionErrorCalculator.ecef(r.latitude, r.longitude, r.ellipsoidHeightMeters ?: 0.0)
        return fix(p[0], p[1], p[2])
    }

    @Test fun wgs84EquatorKnownAxes() {
        assertArrayEquals(doubleArrayOf(6378137.0, 0.0, 0.0), PositionErrorCalculator.ecef(0.0, 0.0, 0.0), 1e-9)
        assertArrayEquals(doubleArrayOf(0.0, 6378137.0, 0.0), PositionErrorCalculator.ecef(0.0, 90.0, 0.0), 1e-8)
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 6356752.314245179), PositionErrorCalculator.ecef(90.0, 0.0, 0.0), 1e-8)
    }

    @Test fun samePhysicalPositionHasZeroError() {
        val r = ReferencePosition(30.6982054167, 104.0521164722, 500.0)
        val e = PositionErrorCalculator.compare(r, atReference(r))!!
        assertEquals(0.0, e.horizontalMeters, 0.0)
        assertEquals(0.0, e.upMeters!!, 0.0)
        assertEquals(0.0, e.spatialMeters!!, 0.0)
    }

    @Test fun signedENUAndThreeDimensionalDistance() {
        val r = ReferencePosition(0.0, 0.0, 0.0)
        val e = PositionErrorCalculator.compare(r, fix(6378141.0, 3.0, -2.0))!!
        assertEquals(3.0, e.eastMeters, 0.0)
        assertEquals(-2.0, e.northMeters, 0.0)
        assertEquals(4.0, e.upMeters!!, 0.0)
        assertEquals(kotlin.math.sqrt(13.0), e.horizontalMeters, 1e-12)
        assertEquals(kotlin.math.sqrt(29.0), e.spatialMeters!!, 1e-12)
    }

    @Test fun manualReferenceIsOriginAndNotDeviceLocation() {
        val solution = fix(6378137.0, 2.0, 3.0)
        val e = PositionErrorCalculator.compare(ReferencePosition(0.0, 0.0, 0.0), solution)!!
        val changed = PositionErrorCalculator.compare(ReferencePosition(0.0, 0.0, -10.0), solution)!!
        assertEquals(e.eastMeters, changed.eastMeters, 0.0)
        assertEquals(e.northMeters, changed.northMeters, 0.0)
        assertEquals(10.0, changed.upMeters!!, 0.0)
        assertEquals(solution, fix(6378137.0, 2.0, 3.0)) // no estimator input writes
    }

    @Test fun normalHeightWithoutZetaHasOnlyHorizontalError() {
        val normal = ReferencePosition(30.6982054167, 104.0521164722, 459.786, ReferenceHeightType.NORMAL)
        val ellipsoid = normal.copy(heightType = ReferenceHeightType.ELLIPSOIDAL, heightMeters = 505.286)
        val s = atReference(ellipsoid)
        val a = PositionErrorCalculator.compare(normal, s)!!
        val b = PositionErrorCalculator.compare(ellipsoid, s)!!
        assertNull(a.upMeters); assertNull(a.spatialMeters)
        assertEquals(b.eastMeters, a.eastMeters, 2e-9)
        assertEquals(b.northMeters, a.northMeters, 2e-9)
    }

    @Test fun reliableZetaConvertsNormalHeightWithCorrectSign() {
        val r = ReferencePosition(30.0, 104.0, 459.786, ReferenceHeightType.NORMAL, 45.5)
        assertEquals(505.286, r.ellipsoidHeightMeters!!, 1e-12)
        assertEquals(0.0, PositionErrorCalculator.compare(r, atReference(r))!!.upMeters!!, 0.0)
        val negative = r.copy(heightAnomalyMeters = -25.0)
        assertEquals(434.786, negative.ellipsoidHeightMeters!!, 1e-12)
    }

    @Test fun savedPcK70LateIndependentGoldenError() {
        val r = ReferencePosition(30.6982054167, 104.0521164722, 459.786, ReferenceHeightType.NORMAL)
        val e = PositionErrorCalculator.compare(r, fix(-1332885.180124070, 5325269.018546620, 3237402.326442518))!!
        assertEquals(0.30934742761696316, e.eastMeters, 1e-7)
        assertEquals(0.13606950295725415, e.northMeters, 1e-7)
        assertEquals(0.33795079613497947, e.horizontalMeters, 1e-7)
    }

    @Test fun dateLineDoesNotCauseLongitudeSubtractionJump() {
        val r = ReferencePosition(0.0, 179.999999, 0.0)
        val xyz = PositionErrorCalculator.ecef(0.0, -179.999999, 0.0)
        val e = PositionErrorCalculator.compare(r, fix(xyz[0], xyz[1], xyz[2]))!!
        assertEquals(0.22263898, e.horizontalMeters, 1e-6)
        assertTrue(e.eastMeters > 0)
    }

    @Test fun polesAndNegativeCoordinatesRemainFinite() {
        for (lat in listOf(-90.0, -30.0, 90.0)) {
            val r = ReferencePosition(lat, -140.0, -50.0)
            assertEquals(0.0, PositionErrorCalculator.compare(r, atReference(r))!!.spatialMeters!!, 0.0)
        }
    }

    @Test fun noReferenceAndNoSolutionAreNotZeroError() {
        assertNull(PositionErrorCalculator.compare(null, fix(6378137.0, 0.0, 0.0)))
        assertNull(PositionErrorCalculator.compare(ReferencePosition(0.0, 0.0, 0.0), null))
    }

    @Test fun invalidQualityDoesNotCompareCachedCoordinates() {
        val r = ReferencePosition(0.0, 0.0, 0.0)
        for (q in listOf(0, -1, 8, 99)) assertNull(PositionErrorCalculator.compare(r, fix(6378137.0, 1.0, 0.0, q)))
        assertNotNull(PositionErrorCalculator.compare(r, fix(6378137.0, 1.0, 0.0, 5))) // SPP explicitly labelled in UI
    }

    @Test fun nanInfinityAndGeocentreAreInvalid() {
        val r = ReferencePosition(0.0, 0.0, 0.0)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(PositionErrorCalculator.compare(r, fix(bad, 0.0, 0.0)))
            assertNull(PositionErrorCalculator.compare(r, fix(1.0, bad, 0.0)))
            assertNull(PositionErrorCalculator.compare(r, fix(1.0, 0.0, bad)))
        }
        assertNull(PositionErrorCalculator.compare(r, fix(0.0, 0.0, 0.0)))
    }

    @Test fun inputAcceptsSignedDecimalAndTrimmedValues() {
        val r = ReferencePositionInput.parse(" -30.5 ", " -104.2 ", "-12.5", ReferenceHeightType.ELLIPSOIDAL)
        assertNull(r.error); assertEquals(-30.5, r.reference!!.latitude, 0.0)
        assertEquals(-104.2, r.reference.longitude, 0.0)
    }

    @Test fun invalidInputsAreRejectedWithoutSubstitutingZero() {
        for (bad in listOf("", "NaN", "Infinity", "91", "-91", "30°"))
            assertEquals(ReferenceInputError.LATITUDE,
                ReferencePositionInput.parse(bad, "104", "500", ReferenceHeightType.ELLIPSOIDAL).error)
        for (bad in listOf("", "NaN", "Infinity", "181", "-181"))
            assertEquals(ReferenceInputError.LONGITUDE,
                ReferencePositionInput.parse("30", bad, "500", ReferenceHeightType.ELLIPSOIDAL).error)
        assertEquals(ReferenceInputError.HEIGHT,
            ReferencePositionInput.parse("30", "104", "NaN", ReferenceHeightType.NORMAL).error)
        assertEquals(ReferenceInputError.HEIGHT_ANOMALY,
            ReferencePositionInput.parse("30", "104", "500", ReferenceHeightType.NORMAL, "Infinity").error)
    }

    @Test fun normalHeightAnomalyIsOptionalAndNotReusedForEllipsoidalInput() {
        assertNull(ReferencePositionInput.parse("30", "104", "500", ReferenceHeightType.NORMAL).reference!!.ellipsoidHeightMeters)
        val r = ReferencePositionInput.parse("30", "104", "500", ReferenceHeightType.ELLIPSOIDAL, "bad old draft")
        assertEquals(500.0, r.reference!!.ellipsoidHeightMeters!!, 0.0)
    }

    @Test fun referencePersistenceRoundTripIsExactAndBadSchemaIsNotLoaded() {
        for (r in listOf(ReferencePosition(30.6982054167, 104.0521164722, 500.123456789),
            ReferencePosition(-30.6982054167, -104.0521164722, 459.786, ReferenceHeightType.NORMAL),
            ReferencePosition(30.0, 104.0, 459.786, ReferenceHeightType.NORMAL, -25.5))) {
            assertEquals(r, ReferencePosition.decode(r.encode()))
        }
        for (bad in listOf(null, "", "v0|30|104|500|ELLIPSOIDAL|", "v1|NaN|104|500|NORMAL|", "v1|30|104|500|UNKNOWN|"))
            assertNull(ReferencePosition.decode(bad))
    }

    @Test fun csvKeepsReferencePerEpochAndInvalidIsBlankNotZero() {
        val r = ReferencePosition(0.0, 0.0, 0.0)
        val a = PositionErrorCsv.row(r, fix(6378137.0, 1.0, 0.0), "LIVE").trimEnd().split(',')
        val b = PositionErrorCsv.row(r.copy(heightMeters = 10.0), fix(6378137.0, 1.0, 0.0), "LIVE").trimEnd().split(',')
        assertEquals("0.0", a[7]); assertEquals("10.0", b[7]); assertEquals("1", a[11])
        val invalid = PositionErrorCsv.row(r, fix(6378137.0, 1.0, 0.0, 0), "LIVE").trimEnd().split(',')
        assertEquals("0", invalid[11]); assertEquals("", invalid[13]); assertEquals("", invalid[16])
        assertEquals(PositionErrorCsv.header.trim().split(',').size, a.size)
    }

    @Test fun csvNormalHeightDoesNotExportFalseVerticalError() {
        val r = ReferencePosition(0.0, 0.0, 459.786, ReferenceHeightType.NORMAL)
        val a = PositionErrorCsv.row(r, fix(6378637.0, 1.0, 0.0), "LIVE").trimEnd().split(',')
        assertEquals("1", a[11]); assertEquals("0", a[12]); assertEquals("", a[15]); assertEquals("", a[17])
        val unset = PositionErrorCsv.row(null, fix(6378137.0, 1.0, 0.0), "LIVE").trimEnd().split(',')
        assertEquals("0", unset[4]); assertEquals("0", unset[11]); assertEquals("", unset[16])
    }

    @Test fun postGpstEpochAndFinitePositionParsing() {
        val s = PostPositionComparison.parseLine("2026/10/18 00:40:58.974 30.6982054167 104.0521164722 500.0 6 14")!!
        assertEquals(2441, s.week); assertEquals(2458.974, s.tow, 1e-10); assertEquals(6, s.q)
        val r = ReferencePosition(30.6982054167, 104.0521164722, 500.0)
        assertEquals(0.0, PositionErrorCalculator.compare(r, s)!!.spatialMeters!!, 0.0)
        assertNull(PostPositionComparison.parseLine("% comment"))
        assertNull(PostPositionComparison.parseLine("2026/10/18 00:40:58.974 NaN 104 500 6"))
    }

    @Test fun postExportIsAtomicAndPreservesOriginalPos() {
        val dir = Files.createTempDirectory("ppp_reference_").toFile()
        try {
            val pos = File(dir, "in.pos")
            val text = "% WGS84/ellipsoidal\n% GPST latitude(deg) longitude(deg) height(m) Q\n" +
                "2026/10/18 00:40:58.974 0 0 0 6\n2026/10/18 00:40:59.974 0 0 1 6\n"
            pos.writeText(text)
            val out = File(dir, "errors.csv")
            val summary = PostPositionComparison.export(pos, out, ReferencePosition(0.0, 0.0, 0.0))
            assertEquals(2, summary.epochs); assertEquals(3, out.readLines().size)
            assertEquals(text, pos.readText()); assertFalse(File(out.path + ".incomplete").exists())
            val before = out.readText()
            assertTrue(runCatching { PostPositionComparison.export(pos, out, null) }.isFailure)
            assertEquals(before, out.readText())
        } finally { dir.listFiles()?.forEach(File::delete); dir.delete() }
    }

    @Test fun postExportRefusesUnknownCoordinateOrTimeConvention() {
        val dir = Files.createTempDirectory("ppp_reference_").toFile()
        try {
            val pos = File(dir, "in.pos"); val out = File(dir, "errors.csv")
            pos.writeText("% UTC x(m) y(m) z(m)\n2026/10/18 00:40:58.974 1 2 3 6\n")
            assertTrue(runCatching { PostPositionComparison.export(pos, out, null) }.isFailure)
            assertFalse(out.exists())
        } finally { dir.listFiles()?.forEach(File::delete); dir.delete() }
    }
}
