package com.example.ftpget

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.floor

/** Eq.2 of DOI:10.3390/s23020777. Mirrors gnss_clock_reference.h; no fitted correction. */
class ObservationClock {
    data class NanoTime(val ns: Long, val fractionNs: Double) {
        fun rinexTick(): Long {
            val rem=Math.floorMod(ns,100L)
            return Math.addExact(ns-rem,if(rem+fractionNs>=50.0)100L else 0L)
        }
    }
    data class Reference(val fullBias: Long,val bias: Double,val segment: Int,val reset: Boolean) {
        fun receive(time: Long,offset: Double=0.0): NanoTime? = split(time,fullBias,bias,offset)
    }
    private var reference: Reference?=null
    private var haveClock=false
    private var lastTime=0L
    private var lastHcdc=0
    private var pendingReset=false
    var segment=0
        private set
    fun observe(time: Long,fullBias: Long?,bias: Double,hcdc: Int,confirmedDiscontinuity: Boolean=false): Reference? {
        val reset=confirmedDiscontinuity || haveClock && (hcdc!=lastHcdc || time<lastTime)
        if(!haveClock)segment=1 else if(reset){segment++;reference=null}
        if(reset)pendingReset=true
        haveClock=true;lastTime=time;lastHcdc=hcdc
        if(reference==null && fullBias!=null && split(time,fullBias,bias,0.0)!=null)
            reference=Reference(fullBias,bias,segment,reset)
        val result=reference?.copy(reset=reset || pendingReset)
        if(result!=null)pendingReset=false
        return result
    }
    companion object {
        const val WEEK_NS=604800000000000L
        const val DAY_NS=86400000000000L
        fun split(time: Long,fullBias: Long,bias: Double,offset: Double): NanoTime? = try {
            if(!bias.isFinite() || !offset.isFinite()) null else {
                val bi=floor(bias);val oi=floor(offset)
                if(bi<=-9223372036854775808.0 || bi>=9223372036854775808.0 ||
                    oi<=-9223372036854775808.0 || oi>=9223372036854775808.0) null else {
                    var ns=Math.addExact(Math.subtractExact(Math.subtractExact(time,fullBias),bi.toLong()),oi.toLong())
                    var fraction=(offset-oi)-(bias-bi)
                    if(fraction<0){ns=Math.subtractExact(ns,1);fraction+=1}
                    if(fraction>=1){ns=Math.addExact(ns,1);fraction-=1}
                    if(ns<0)null else NanoTime(ns,fraction)
                }
            }
        } catch (_: ArithmeticException){null}
        // Effective UTC dates, GPS-UTC after transition. Convert table thresholds to GPST.
        private val leaps=listOf("1981-07-01","1982-07-01","1983-07-01","1985-07-01",
            "1988-01-01","1990-01-01","1991-01-01","1992-07-01","1993-07-01","1994-07-01",
            "1996-01-01","1997-07-01","1999-01-01","2006-01-01","2009-01-01","2012-07-01",
            "2015-07-01","2017-01-01").mapIndexed { i,date ->
            LocalDate.parse(date).atStartOfDay().toEpochSecond(ZoneOffset.UTC)-315964800L+(i+1) to (i+1)
        }
        fun gpsUtcLeapSeconds(t: NanoTime): Int = leaps.lastOrNull { t.ns/1000000000L>=it.first }?.second ?: 0
        /** Auxiliary raw-log metadata only. Observation epochs use the fixed segment reference. */
        fun utcMetadataMillis(t: NanoTime,reportedLeap: Int?): Long =
            t.ns/1000000L+315964800000L-(reportedLeap?.takeIf {it in 0..100}?:gpsUtcLeapSeconds(t))*1000L
        fun travelNs(t: NanoTime,system: Int,tx: Long,leap: Int?): Double {
            val period=if(system==3)DAY_NS else WEEK_NS
            var rx=Math.floorMod(t.ns,period)
            if(system==5)rx=Math.floorMod(rx-14000000000L,period)
            if(system==3)rx=Math.floorMod(rx+(10800L-(leap?.takeIf { it in 0..100 }?:gpsUtcLeapSeconds(t)))*1000000000L,period)
            var d=rx-tx
            if(d>period/2)d-=period
            if(d< -period/2)d+=period
            return d.toDouble()+t.fractionNs
        }
    }
}
