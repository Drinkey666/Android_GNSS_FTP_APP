package com.example.ftpget

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CaptureCodePhaseRepairTest {
    private fun o(k: Int, bias: Double = 0.0, lBias: Double = 0.0, state: Int = 1,
                  svid: Int = 1, frequency: Double = 1575420000.0, hcdc: Int = 0,
                  offset: Long = 0) = CaptureObservation(k * 1000000000L + offset, hcdc, 1, svid,
        frequency, "C", 24000000.0 + 100 * k + bias, 1.0,
        1000.0 + 100 * k + lBias, 0.01, state, 40.0, 100.0)
    private fun primed(config: CaptureRepairConfig = CaptureRepairConfig()): CaptureCodePhaseRepair = CaptureCodePhaseRepair(config).also { p ->
        for (k in 0..3) p.process(listOf(o(k)))
    }
    private fun p(rows: List<CaptureRepairRow>) = rows.single { it.type == "P" }
    private fun l(rows: List<CaptureRepairRow>) = rows.single { it.type == "L" }

    @Test fun continuousDataUnchanged() {
        val f = CaptureCodePhaseRepair()
        for (k in 0..100) for (r in f.process(listOf(o(k)))) assertEquals(r.raw, r.candidate)
    }
    @Test fun noCorrectionDuringWarmup() {
        val f = CaptureCodePhaseRepair()
        for (k in 0..3) assertTrue(f.process(listOf(o(k))).all { it.status == "WARMUP" })
    }
    @Test fun injectedCodeSpikeRepairedWithHealthyPhase() {
        val r = p(primed().process(listOf(o(4, bias = 100.0))))
        assertEquals("REPAIR_CANDIDATE", r.status)
        assertEquals(24000400.0, r.candidate!!, 1e-6)
        assertTrue(r.synthetic); assertEquals(1, r.peers)
    }
    @Test fun stepDoesNotReturnThroughNextIncrement() {
        val f = primed()
        for (k in 4..10) {
            val r = p(f.process(listOf(o(k, bias = 100.0))))
            assertEquals("REPAIR_CANDIDATE", r.status)
            assertEquals(24000000.0 + 100 * k, r.candidate!!, 1e-5)
        }
    }
    @Test fun oneEpochSpikeRecoveryReturnsToRaw() {
        val f = primed(); f.process(listOf(o(4, bias = 100.0)))
        assertEquals("ORIGINAL_ACCEPTED", p(f.process(listOf(o(5)))).status)
    }
    @Test fun invalidPhaseNeverFabricated() {
        val r = l(primed().process(listOf(o(4, state = 0))))
        assertNull(r.raw); assertNull(r.candidate)
    }
    @Test fun resetPhaseNeverBridged() { assertFalse(o(1, state = 3).phaseContinuous) }
    @Test fun slipPhaseNeverBridged() { assertFalse(o(1, state = 5).phaseContinuous) }
    @Test fun unresolvedHalfCycleNotValid() { assertFalse(o(1, state = 17).phaseContinuous) }
    @Test fun resolvedHalfCycleValid() { assertTrue(o(1, state = 25).phaseContinuous) }
    @Test fun resetClearsRepairOffset() {
        val f = primed(); f.process(listOf(o(4, bias = 100.0)))
        val r = p(f.process(listOf(o(5, bias = 100.0, hcdc = 1))))
        assertEquals("CLOCK_RESET", r.status); assertEquals(r.raw, r.candidate)
    }
    @Test fun gapClearsOffset() {
        val r = p(primed().process(listOf(o(10, bias = 100.0))))
        assertEquals("GAP_RESET", r.status); assertEquals(r.raw, r.candidate)
    }
    @Test fun backwardTimeClearsOffset() {
        assertEquals("TIME_RESET", p(primed().process(listOf(o(1)))).status)
    }
    @Test fun duplicateTimeClearsOffset() {
        assertEquals("TIME_RESET", p(primed().process(listOf(o(3)))).status)
    }
    @Test fun duplicateSignalNotArbitrarilySelected() {
        assertTrue(primed().process(listOf(o(4), o(4))).all { it.status == "DUPLICATE_SIGNAL" })
    }
    @Test fun missingSignalCannotBorrowOtherHistory() {
        val r = p(primed().process(listOf(o(4, frequency = 1176450000.0))))
        assertEquals("WARMUP", r.status)
    }
    @Test fun otherSatelliteNotMixed() {
        val r = p(primed().process(listOf(o(4, svid = 2, bias = 100.0))))
        assertEquals("WARMUP", r.status)
    }
    @Test fun noHealthyRowsNoSyntheticMeasurements() {
        val r = primed().process(listOf(o(4, bias = 100.0, lBias = 100.0)))
        assertTrue(r.all { !it.synthetic && it.raw == it.candidate })
        assertEquals("NO_HEALTHY_PEER",p(r).status)
    }
    @Test fun predictionLimitEndsModel() {
        val f = primed(); var r = emptyList<CaptureRepairRow>()
        for (k in 4..7) r = f.process(listOf(o(k, bias = 100.0 * (k - 3), lBias = 100.0 * (k - 3))))
        assertTrue(r.all { it.status == "PREDICTION_LIMIT_RESET" })
    }
    @Test fun phaseCandidateOnlyUnderExplicitResearchSwitch() {
        val r = l(primed(CaptureRepairConfig(enablePhaseCandidates=true)).process(listOf(o(4, lBias = 100.0))))
        assertEquals("REPAIR_CANDIDATE", r.status); assertTrue(r.synthetic)
    }
    @Test fun defaultNeverReconstructsCarrierFromNoisyCode() {
        val r=l(primed().process(listOf(o(4,lBias=100.0))))
        assertEquals("PHASE_ANOMALY_NOT_REPAIRED",r.status);assertEquals(r.raw,r.candidate)
    }
    @Test fun uncertaintyIsNotClaimedIndependent() {
        val r = p(primed().process(listOf(o(4, bias = 100.0))))
        assertTrue(r.candidateSigmaUpper!! >= 1)
        assertEquals("false", r.csv().split(',')[17])
    }
    @Test fun inputObjectsNeverMutated() {
        val observation = o(4, bias = 100.0); val snapshot = observation.copy()
        primed().process(listOf(observation)); assertEquals(snapshot, observation)
    }
    @Test fun pastOutputsDoNotUseFutureSamples() {
        val a = primed(); val b = primed()
        val firstA = a.process(listOf(o(4, bias = 100.0)))
        val firstB = b.process(listOf(o(4, bias = 100.0)))
        b.process(listOf(o(5, bias = 50000.0)))
        assertEquals(firstA, firstB)
    }
    @Test fun quadraticTrajectoryIsCausallyReproduced() {
        val f = CaptureCodePhaseRepair()
        for (k in 0..8) {
            val q = 3.0 * k * k
            val obs = o(k, bias = q, lBias = q)
            assertTrue(f.process(listOf(obs)).all { it.raw == it.candidate })
        }
    }
    @Test fun actualFrequencyChannelsAreDistinct() { assertNotEquals(o(0).key, o(0, frequency = 1575420000.0 + 562500.0).key) }
    @Test(expected = IllegalArgumentException::class) fun nanInputNotZeroFilled() { o(0).copy(codeMeters = Double.NaN) }
    @Test(expected = IllegalArgumentException::class) fun zeroSigmaNotAccepted() { o(0).copy(codeSigma = 0.0) }
    @Test(expected = IllegalArgumentException::class) fun invalidConfigFailsExplicitly() { CaptureRepairConfig(drivingStd = Double.NaN) }
    @Test(expected = IllegalArgumentException::class) fun mixedEpochRejected() { primed().process(listOf(o(4), o(5, svid = 2))) }
    @Test fun malformedRawSkipped() { assertNull(CaptureObservation.fromRaw("Raw,broken")) }
    @Test fun missingPhaseUncertaintyNotGuessed() { assertFalse(o(1).copy(adrSigma = null).phaseContinuous) }
    @Test fun missingCodeUncertaintyNotRepaired() {
        val r = p(primed().process(listOf(o(4, bias = 100.0).copy(codeSigma = null))))
        assertEquals("UNAVAILABLE_OR_NEW_ARC", r.status); assertEquals(r.raw, r.candidate)
    }
    @Test fun scalarJosephKnownAnswer() {
        val (x, p) = CaptureCodePhaseRepair.scalarUpdate(doubleArrayOf(1.0, 2.0, 3.0),
            doubleArrayOf(2.0,0.0,0.0,0.0,3.0,0.0,0.0,0.0,4.0), 4.0, 1.0)
        assertEquals(3.0,x[0],1e-14); assertEquals(2.0/3,p[0],1e-14)
        assertEquals(3.0,p[4],0.0); assertEquals(4.0,p[8],0.0)
    }
    @Test fun propagationKnownAnswer() {
        val (x, p) = CaptureCodePhaseRepair.propagate(doubleArrayOf(1.0,2.0,3.0), DoubleArray(9), 2.0, 1.0)
        assertEquals(11.0,x[0],0.0); assertEquals(8.0,x[1],0.0); assertEquals(3.0,x[2],0.0)
        assertEquals(16.0/9,p[0],1e-14); assertEquals(p[1],p[3],0.0)
    }
    @Test fun epochJitterDoesNotInventCodeCorrection() {
        val f = CaptureCodePhaseRepair()
        for (k in 0..20) {
            val offset = if (k % 2 == 0) 20000000L else 0L
            val observation = o(k, offset=offset, bias=offset*1e-7, lBias=offset*1e-7)
            assertTrue(f.process(listOf(observation)).all { it.raw == it.candidate })
        }
    }
    @Test fun slowCommonDriftIsNotIdentifiable() {
        val f = CaptureCodePhaseRepair()
        for (k in 0..40) {
            val obs = o(k, bias = k * 2.0, lBias = k * 2.0)
            assertTrue(f.process(listOf(obs)).all { it.raw == it.candidate })
        }
    }
    private fun raw(system: Int = 1, state: Int = 9, adr: Int = 1, badSigma: Boolean = false): String {
        val c = Array(54) { "" }
        c[0]="Raw"; c[2]="12000000000"; c[3]="18"; c[5]="-1476319250961999872"
        c[6]="0"; c[10]="0"; c[11]="32"; c[12]="0"; c[13]=state.toString()
        val period = if (system == 3) 86400000000000L else 604800000000000L
        var rx = Math.floorMod(c[2].toLong()-c[5].toLong(),period)
        if (system == 5) rx -= 14000000000L
        if (system == 3) rx += 10782000000000L
        c[14]=Math.floorMod(rx-70000000L,period).toString()
        c[15]=if(badSigma) "0" else "10";c[16]="40";c[17]="100";c[19]=adr.toString()
        c[20]="1000";c[21]="0.01";c[22]="1575420000";c[28]=system.toString();c[35]="C"
        return c.joinToString(",")
    }
    @Test fun gpsNanosecondConversionPreservesMillimeters() {
        assertEquals(0.07*CaptureObservation.C,CaptureObservation.fromRaw(raw())!!.codeMeters!!,1e-7)
    }
    @Test fun galileoSharesGpsWeekTime() {
        assertEquals(0.07*CaptureObservation.C,CaptureObservation.fromRaw(raw(system=6))!!.codeMeters!!,1e-7)
    }
    @Test fun beidouFourteenSecondConversion() {
        assertEquals(0.07*CaptureObservation.C,CaptureObservation.fromRaw(raw(system=5))!!.codeMeters!!,1e-7)
    }
    @Test fun glonassUtcThreeHourAndLeapConversion() {
        assertEquals(0.07*CaptureObservation.C,CaptureObservation.fromRaw(raw(system=3,state=129))!!.codeMeters!!,1e-7)
    }
    @Test fun timeUnknownCodeAbsentButPhaseNotDestroyed() {
        val o = CaptureObservation.fromRaw(raw(state=1))!!;assertNull(o.codeMeters);assertTrue(o.phaseContinuous)
    }
    @Test fun codeLockRequiredSeparatelyFromAdr() {
        val o=CaptureObservation.fromRaw(raw(state=8))!!;assertNull(o.codeMeters);assertTrue(o.phaseContinuous)
    }
    @Test fun zeroReportedCodeUncertaintyStaysUnknown() {
        assertNull(CaptureObservation.fromRaw(raw(badSigma=true))!!.codeSigma)
    }
}
