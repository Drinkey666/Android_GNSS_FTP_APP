package com.example.ftpget

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

data class DmsFields(val degrees: String, val minutes: String, val seconds: String)
enum class DmsReferenceError {
    LATITUDE_DEGREES, LATITUDE_MINUTES, LATITUDE_SECONDS,
    LONGITUDE_DEGREES, LONGITUDE_MINUTES, LONGITUDE_SECONDS, HEIGHT,
}
data class DmsReferenceResult(val reference: ReferencePosition?, val error: DmsReferenceError?)

/** WGS84 geodetic DMS + ellipsoidal height. Hemisphere sets the sign; no
 * normal-height assumption, map-coordinate conversion or filter involvement. */
object ReferenceDmsInput {
    private fun number(s: String) = s.trim().toDoubleOrNull()?.takeIf(Double::isFinite)

    fun parse(latitude: DmsFields, south: Boolean, longitude: DmsFields, west: Boolean,
              ellipsoidHeight: String): DmsReferenceResult {
        fun coordinate(value: DmsFields, max: Double): Pair<Double?, Int?> {
            val d = number(value.degrees)?.takeIf { it >= 0 && it <= max && floor(it) == it }
                ?: return null to 0
            val m = number(value.minutes)?.takeIf { it >= 0 && it < 60 && floor(it) == it }
                ?: return null to 1
            val s = number(value.seconds)?.takeIf { it >= 0 && it < 60 }
                ?: return null to 2
            if (d == max && m != 0.0) return null to 1
            if (d == max && s != 0.0) return null to 2
            return d + m / 60.0 + s / 3600.0 to null
        }
        val (lat, latError) = coordinate(latitude, 90.0)
        if (latError != null) return DmsReferenceResult(null, DmsReferenceError.entries[latError])
        val (lon, lonError) = coordinate(longitude, 180.0)
        if (lonError != null) return DmsReferenceResult(null, DmsReferenceError.entries[lonError + 3])
        val h = number(ellipsoidHeight) ?: return DmsReferenceResult(null, DmsReferenceError.HEIGHT)
        return DmsReferenceResult(ReferencePosition(if (south) -lat!! else lat!!,
            if (west) -lon!! else lon!!, h, ReferenceHeightType.ELLIPSOIDAL), null)
    }

    /** Integer microarcseconds handle 59.9999999 -> next minute correctly. */
    fun fromDecimal(degrees: Double): DmsFields {
        require(degrees.isFinite() && abs(degrees) <= 180)
        val total = (abs(degrees) * 3600 * 1_000_000).roundToLong()
        val d = total / 3_600_000_000L
        val m = (total % 3_600_000_000L) / 60_000_000L
        val s = (total % 60_000_000L) / 1_000_000.0
        return DmsFields(d.toString(), m.toString(), String.format(Locale.US, "%.6f", s))
    }
}
