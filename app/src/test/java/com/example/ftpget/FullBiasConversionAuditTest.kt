package com.example.ftpget

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Raw-field arithmetic plus regression of the fixed segment reference; old characterization backed up. */
class FullBiasConversionAuditTest {
    private fun travel(o: RinexConverter.GnssSat): Double {
        val method=RinexConverter.javaClass.getDeclaredMethod("calculatePrecisePseudorangeSeconds",RinexConverter.GnssSat::class.java)
        method.isAccessible=true
        return method.invoke(RinexConverter,o) as Double
    }
    private fun parse(line: String): RinexConverter.GnssSat {
        val method=RinexConverter.javaClass.getDeclaredMethod("parseLine",String::class.java)
        method.isAccessible=true
        return method.invoke(RinexConverter,line) as RinexConverter.GnssSat
    }
    private fun fixture() = RinexConverter.GnssSat().apply {
        timeNanos=100000000000L;fullBiasNanos=-2400L*604800000000000L
        receivedSvTimeNanos=99930000000L;sys=1
    }
    @Test fun subNanosecondBiasIsUsedInCodeCalculation() {
        val o=fixture();o.biasNanos=.25;val a=travel(o)
        o.biasNanos=.75;val b=travel(o)
        assertEquals(-.5e-9,b-a,1e-17)
    }
    @Test fun fullBiasInteger256nsChangeProducesExpectedCodeOnlyOffset() {
        val o=fixture();o.adrMeters=12345.0
        val a=travel(o);o.fullBiasNanos+=256
        assertEquals(-256e-9,travel(o)-a,1e-17)
        assertEquals(12345.0,o.adrMeters,0.0)
    }
    @Test fun parserDoesNotConvertFullBiasThroughDouble() {
        val c=Array(54){""};c[0]="Raw";c[2]="10012000000";c[5]="-1476319251061999872"
        assertEquals(-1476319251061999872L,parse(c.joinToString(",")).fullBiasNanos)
    }
    @Test fun actualRealPrefixesReproduceCodeLossAndFineReferenceDifference() {
        val dir=System.getenv("FULLBIAS_AUDIT_DIR")?.let(::File)
        assumeTrue(dir?.isDirectory==true)
        val fixtureDir=requireNotNull(dir)
        val outputDir=System.getenv("OBSERVATION_GENERATION_OUTPUT")?.let(::File)?:File(fixtureDir,"fixed_clock_2023")
        outputDir.mkdirs()
        val output=File(outputDir,"actual_kotlin_probe.csv")
        output.bufferedWriter().use { out ->
            out.appendLine("fixture,row,full_bias_nanos,p_written_m,l_written_cycles,p_fields_m,p_held_counterfactual_m")
            for(name in listOf("K70_late_G32_prefix","fine_K70_early","fine_K70_oct21")) {
                val input=File(fixtureDir,"$name.txt");val rnx=File(outputDir,"$name.kotlin.rnx")
                assertTrue(RinexConverter.convert(input,rnx))
                val raw=input.readLines().filter{it.startsWith("Raw,")}.map(::parse)
                val rows=rnx.readLines().filter{it.matches(Regex("^G[0-9]{2}.*"))}
                assertEquals(raw.size,rows.size)
                val held=raw.first().fullBiasNanos
                for((i,o) in raw.withIndex()) {
                    o.sys=1
                    val p=travel(o)*299792458.0
                    val original=o.fullBiasNanos;o.fullBiasNanos=held
                    val ph=travel(o)*299792458.0;o.fullBiasNanos=original
                    val pw=rows[i].substring(3,17).trim().toDoubleOrNull()?:0.0
                    val lw=rows[i].substring(19,33).trim().toDoubleOrNull()?:0.0
                    out.appendLine("$name,${i+1},$original,$pw,$lw,$p,$ph")
                    if(name=="K70_late_G32_prefix" && i==2) {
                        assertEquals(0.0,pw,0.0)
                        assertEquals(23297705.33421324,p,1e-7)
                        assertTrue(lw!=0.0)
                    }
                    if(name.startsWith("fine_") && i==1) {
                        assertEquals(ph,pw,.00051)
                        assertEquals(256e-9*299792458.0,ph-p,1e-7)
                    }
                }
            }
        }
    }
}
