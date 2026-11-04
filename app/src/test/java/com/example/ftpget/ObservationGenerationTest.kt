package com.example.ftpget

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPOutputStream

class ObservationGenerationTest {
    private val week=2400L*ObservationClock.WEEK_NS
    private fun raw(t: Long=100000000000L,f: Long= -week,b: Double=0.0,h: Int=0,
                    sv: Int=1,adr: Int=25,p: String="99930000000",offset: Double=0.0,
                    rate: String="2.0",phase: Double= -1234.5): String {
        val c=Array(54){""};c[0]="Raw";c[2]="$t";c[3]="18";c[5]="$f";c[6]="$b";c[10]="$h"
        c[11]="$sv";c[12]="$offset";c[13]="9";c[14]=p;c[15]="1";c[16]="35"
        c[17]=rate;c[18]="0.1";c[19]="$adr";c[20]="$phase";c[21]="0.1";c[22]="1575420000"
        c[28]="1";c[35]="C";return c.joinToString(",")
    }
    private fun convert(vararg lines: String): Pair<List<RinexConverter.ObservationAudit>,List<String>> {
        val input=File.createTempFile("obsgen_",".txt");val output=File.createTempFile("obsgen_",".rnx")
        try {
            input.writeText(lines.joinToString("\n"));val audit=mutableListOf<RinexConverter.ObservationAudit>()
            assertTrue(RinexConverter.lastError,RinexConverter.convert(input,output,audit={audit.add(it)}))
            return audit to output.readLines()
        } finally {input.delete();output.delete()}
    }
    @Test fun frozenReferenceAndFractionalTime() {
        val c=ObservationClock();val a=c.observe(100000000000L,-week,.25,0)!!
        val b=c.observe(101000000000L,-week+256,900.0,0)!!
        assertEquals(a.fullBias,b.fullBias);assertEquals(.25,b.bias,0.0);assertFalse(b.reset)
        val t=b.receive(101000000000L,.75)!!
        assertEquals(week+101000000000L,t.ns);assertEquals(.5,t.fractionNs,0.0)
        assertEquals(b.fullBias,c.observe(102000000000L,null,Double.NaN,0)!!.fullBias)
    }
    @Test fun clockResetIsPendingUntilReferenceExists() {
        val c=ObservationClock();c.observe(100000000000L,-week,0.0,1)
        assertNull(c.observe(101000000000L,null,0.0,2))
        val r=c.observe(102000000000L,-week+1000,0.0,2)!!
        assertTrue(r.reset);assertEquals(2,r.segment)
        assertFalse(c.observe(103000000000L,null,0.0,2)!!.reset)
        assertEquals(3,c.observe(1,-week,0.0,2)!!.segment)
        assertEquals(4,c.observe(2,-week,0.0,2,true)!!.segment)
    }
    @Test fun boundariesLeapAndPrecision() {
        assertNull(ObservationClock.split(Long.MAX_VALUE,-1,0.0,0.0))
        assertNull(ObservationClock.split(1,-week,Double.NaN,0.0))
        val t=ObservationClock.NanoTime(week+20000000L,.5)
        assertEquals(70000000.5,ObservationClock.travelNs(t,1,ObservationClock.WEEK_NS-50000000L,null),0.0)
        assertEquals(18,ObservationClock.gpsUtcLeapSeconds(t))
        assertEquals(0,ObservationClock.gpsUtcLeapSeconds(ObservationClock.NanoTime(0,0.0)))
        val glo=ObservationClock.NanoTime(week+75618020000000L,0.0)
        assertEquals(70000000.0,ObservationClock.travelNs(glo,3,ObservationClock.DAY_NS-50000000L,18),0.0)
        val bds=ObservationClock.NanoTime(week+14070000000L,0.0)
        assertEquals(70000000.0,ObservationClock.travelNs(bds,5,0,18),0.0)
        assertEquals(60000000000L,ObservationClock.NanoTime(59999999960L,.1).rinexTick())
    }
    @Test fun rawUtcMetadataUsesUnixEpochAndDateDependentLeap() {
        assertEquals(315964800000L,ObservationClock.utcMetadataMillis(ObservationClock.NanoTime(0,0.0),null))
        val t=ObservationClock.NanoTime((1483228800L-315964800L+18)*1000000000L,0.0)
        assertEquals(1483228800000L,ObservationClock.utcMetadataMillis(t,null))
        assertEquals(17,ObservationClock.gpsUtcLeapSeconds(ObservationClock.NanoTime(t.ns-1000000000L,0.0)))
        assertEquals(18,ObservationClock.gpsUtcLeapSeconds(t))
    }
    @Test fun signedPhaseDopplerZeroAndMissingValues() {
        val (a,r)=convert(raw(rate="0.0"),raw(t=101000000000L,p="100930000000",rate=""))
        assertTrue(a[0].L!!<0);assertEquals(0.0,a[0].D!!,0.0);assertNull(a[1].D)
        assertEquals(0.0,r.first {it.startsWith("G01")}.substring(35,49).trim().toDouble(),0.0)
        assertTrue(r.any {it.contains("NO RESAMPLING")})
        val zero=convert(raw(phase=0.0)).first.single();assertEquals(0.0,zero.L!!,0.0)
    }
    @Test fun fineFullBiasUpdateDoesNotCreateCodeStep() {
        val a=convert(raw(),raw(t=101000000000L,f= -week+256,b=12.5,p="100930000000")).first
        assertEquals(a[0].P!!,a[1].P!!,1e-8);assertEquals(a[0].segment,a[1].segment)
        assertEquals(0,a[1].lli and 1)
    }
    @Test fun singleSignalResetDoesNotReanchorOtherSatellite() {
        val a=convert(raw(),raw(sv=2),raw(t=101000000000L,p="100930000000",adr=27),
            raw(t=101000000000L,p="100930000000",sv=2),raw(t=102000000000L,p="101930000000"),
            raw(t=102000000000L,p="101930000000",sv=2)).first
        assertNull(a[2].L);assertEquals(1,a[2].lli and 1);assertEquals(0,a[3].lli and 1)
        assertEquals(1,a[4].lli and 1);assertTrue(a.all {it.segment==1})
    }
    @Test fun halfCycleAndHardwareSegmentAreExplicit() {
        val a=convert(raw(adr=17),raw(t=101000000000L,p="100930000000"),
            raw(t=102000000000L,p="101930000000",h=1)).first
        assertEquals(2,a[0].lli);assertEquals(1,a[1].lli and 1);assertEquals(2,a[2].segment)
        assertEquals(1,a[2].lli and 1)
    }
    @Test fun timeOffsetsProduceSeparateActualEpochs() {
        val (a,r)=convert(raw(offset=.75,b=.25),raw(sv=2,offset=100000000.75,b=.25))
        assertEquals(100000000L,a[1].rxNs!!-a[0].rxNs!!)
        assertEquals(.5,a[0].rxFractionNs!!,0.0);assertEquals(2,r.count {it.startsWith(">")})
    }
    @Test fun moreThanFiveSignalsAreActuallyWritten() {
        val rows=listOf("C","S","L","X","P","W","Y").map { code ->
            val c=raw().split(",").toMutableList();c[35]=code;c.joinToString(",")
        }
        val (a,r)=convert(*rows.toTypedArray())
        assertEquals(7,a.size);assertEquals(3+7*64,r.first {it.startsWith("G01")}.length)
        assertEquals(3,r.count { it.substring(60.coerceAtMost(it.length)).trim()=="SYS / # / OBS TYPES" })
    }
    @Test fun missingClockAndNumericFieldsAreNotZeroFilled() {
        fun edit(line: String,i: Int,v: String): String = line.split(",").toMutableList().apply {set(i,v)}.joinToString(",")
        val a=convert(edit(raw(),5,""),raw(t=101000000000L,p="100930000000"),
            edit(raw(t=102000000000L,p="101930000000"),18,"bad")).first
        assertEquals("INVALID_CLOCK",a[0].status);assertNotNull(a[1].P);assertNull(a[2].D)
        val b=convert(edit(raw(),6,"bad"),raw(t=101000000000L,p="100930000000")).first
        assertEquals("INVALID_CLOCK",b[0].status)
    }
    @Test fun conflictingTransmitRetagIsReportedWithoutInventedAlignment() {
        val a=convert(raw(),raw(t=101000000000L,f= -week-100000000L,p="101030000000")).first
        assertNull(a[1].P);assertNotNull(a[1].L);assertEquals(1,a[1].segment)
        assertTrue(a[1].status.contains("CODE_TIME_OUT_OF_RANGE"))
    }
    @Test fun duplicateRowsSelectOneWholeValidObservation() {
        val a=convert(raw(phase=Double.NaN),raw()).first
        assertEquals("DUPLICATE_NOT_SELECTED",a[0].status);assertNotNull(a[1].L)
        val b=convert(raw(),raw(phase=Double.NaN)).first
        assertNotNull(b[0].L);assertEquals("DUPLICATE_NOT_SELECTED",b[1].status)
    }
    @Test fun fourRealCapturesWhenConfigured() {
        val root=System.getenv("OBSERVATION_GENERATION_OUTPUT")?.let(::File)
        assumeTrue(root!=null);val dir=requireNotNull(root);dir.mkdirs()
        val inputs=listOf("K70_early" to "E:/RTKLIB_Data/k70/RawData_20261018_082050.txt",
            "K70_late" to "E:/RTKLIB_Data/k70/RawData_20261018_084040.txt",
            "K70_oct21" to "E:/RTKLIB_Data/10.21/RawData_20261021_102241.txt",
            "K70_oct25" to "E:/GNSS/PPP_only/validation/android_live_20261025_v1/inputs/RawData_20261025_133353.txt")
        for((name,path) in inputs) {
            GZIPOutputStream(File(dir,"$name.kotlin.csv.gz").outputStream()).bufferedWriter().use { w ->
                w.appendLine("source_row,time_nanos,raw_full_bias,raw_bias,reference_full_bias,reference_bias,segment,rx_ns,rx_fraction_ns,system,svid,signal,frequency_hz,P,L,D,lli,status")
                assertTrue("$name: ${RinexConverter.lastError}",RinexConverter.convert(File(path),File(dir,"$name.rnx"),audit={a ->
                    w.appendLine(listOf(a.sourceRow,a.timeNanos,a.rawFullBias,a.rawBias,a.referenceFullBias,a.referenceBias,
                        a.segment,a.rxNs,a.rxFractionNs,a.system,a.svid,a.signal,a.frequencyHz,a.P,a.L,a.D,a.lli,a.status).joinToString(","){it?.toString()?:""})
                }))
            }
            assertTrue(RinexConverter.lastStats.codeAccepted>0);assertTrue(RinexConverter.lastStats.phaseAccepted>0)
        }
    }
}
