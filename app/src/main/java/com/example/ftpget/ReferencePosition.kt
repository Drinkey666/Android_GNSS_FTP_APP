package com.example.ftpget

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class ReferenceHeightType { ELLIPSOIDAL, NORMAL }

/** Display/evaluation data only. Never passed to JNI, rtk_t or product selection. */
data class ReferencePosition(
    val latitude: Double,
    val longitude: Double,
    val heightMeters: Double,
    val heightType: ReferenceHeightType = ReferenceHeightType.ELLIPSOIDAL,
    /** Height anomaly zeta = ellipsoidal height - normal height, in metres. */
    val heightAnomalyMeters: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(heightMeters.isFinite())
        require(heightAnomalyMeters == null || heightAnomalyMeters.isFinite())
        require(ellipsoidHeightMeters == null || ellipsoidHeightMeters!!.isFinite())
    }

    val ellipsoidHeightMeters: Double?
        get() = when (heightType) {
            ReferenceHeightType.ELLIPSOIDAL -> heightMeters
            ReferenceHeightType.NORMAL -> heightAnomalyMeters?.let { heightMeters + it }
        }

    /** Single preferences value: immutable, versioned, no partially updated fields. */
    fun encode(): String = listOf("v1", latitude, longitude, heightMeters,
        heightType.name, heightAnomalyMeters ?: "").joinToString("|")

    companion object {
        fun decode(value: String?): ReferencePosition? = runCatching {
            val a = value?.split('|') ?: return null
            require(a.size == 6 && a[0] == "v1")
            ReferencePosition(a[1].toDouble(), a[2].toDouble(), a[3].toDouble(),
                ReferenceHeightType.valueOf(a[4]), a[5].takeIf(String::isNotBlank)?.toDouble())
        }.getOrNull()
    }
}

data class SolutionCoordinate(
    val week: Int,
    val tow: Double,
    val q: Int,
    val x: Double,
    val y: Double,
    val z: Double,
) {
    companion object {
        fun fromLive(v: LivePppResult) = SolutionCoordinate(v.week, v.tow, v.q, v.x, v.y, v.z)
    }
}

data class PositionError(
    val eastMeters: Double,
    val northMeters: Double,
    val upMeters: Double?,
    val horizontalMeters: Double,
    val spatialMeters: Double?,
)

enum class ReferenceInputError { LATITUDE, LONGITUDE, HEIGHT, HEIGHT_ANOMALY }
data class ReferenceInputResult(val reference: ReferencePosition?, val error: ReferenceInputError?)

object ReferencePositionInput {
    fun parse(lat: String, lon: String, height: String, type: ReferenceHeightType,
              anomaly: String = ""): ReferenceInputResult {
        fun number(text: String) = text.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
        val latitude = number(lat)?.takeIf { it in -90.0..90.0 }
            ?: return ReferenceInputResult(null, ReferenceInputError.LATITUDE)
        val longitude = number(lon)?.takeIf { it in -180.0..180.0 }
            ?: return ReferenceInputResult(null, ReferenceInputError.LONGITUDE)
        val h = number(height) ?: return ReferenceInputResult(null, ReferenceInputError.HEIGHT)
        val zeta = if (type == ReferenceHeightType.NORMAL && anomaly.isNotBlank()) {
            number(anomaly) ?: return ReferenceInputResult(null, ReferenceInputError.HEIGHT_ANOMALY)
        } else null
        if (zeta != null && !(h + zeta).isFinite())
            return ReferenceInputResult(null, ReferenceInputError.HEIGHT_ANOMALY)
        return ReferenceInputResult(ReferencePosition(latitude, longitude, h, type, zeta), null)
    }
}

object PositionErrorCalculator {
    /** WGS84 ellipsoid, geodetic latitude/longitude (degrees), ellipsoidal h (m). */
    fun ecef(lat: Double, lon: Double, height: Double): DoubleArray {
        val p = Math.toRadians(lat)
        val l = Math.toRadians(lon)
        val f = 1.0 / 298.257223563
        val e2 = f * (2 - f)
        val n = 6378137.0 / sqrt(1 - e2 * sin(p) * sin(p))
        return doubleArrayOf((n + height) * cos(p) * cos(l),
            (n + height) * cos(p) * sin(l), (n * (1 - e2) + height) * sin(p))
    }

    fun compare(reference: ReferencePosition?, solution: SolutionCoordinate?): PositionError? {
        if (reference == null || solution == null || solution.q !in 1..7 ||
            !solution.tow.isFinite() || !solution.x.isFinite() || !solution.y.isFinite() ||
            !solution.z.isFinite() || (solution.x == 0.0 && solution.y == 0.0 && solution.z == 0.0)) return null
        // Reference height only translates the origin along Up. Choosing h=0
        // leaves E/N identical when normal height has no known height anomaly.
        val ref = ecef(reference.latitude, reference.longitude, reference.ellipsoidHeightMeters ?: 0.0)
        val dx = solution.x - ref[0]
        val dy = solution.y - ref[1]
        val dz = solution.z - ref[2]
        val lat = Math.toRadians(reference.latitude)
        val lon = Math.toRadians(reference.longitude)
        val east = -sin(lon) * dx + cos(lon) * dy
        val north = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val up = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val horizontal = Math.hypot(east, north)
        if (!east.isFinite() || !north.isFinite() || !up.isFinite() || !horizontal.isFinite()) return null
        val vertical = up.takeIf { reference.ellipsoidHeightMeters != null }
        val spatial = vertical?.let { Math.hypot(horizontal, it) }
        if (spatial != null && !spatial.isFinite()) return null
        return PositionError(east, north, vertical, horizontal, spatial)
    }
}
