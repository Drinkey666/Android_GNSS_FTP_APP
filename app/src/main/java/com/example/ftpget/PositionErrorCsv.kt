package com.example.ftpget

/** Append-only diagnostic format. Each row carries its actual manual reference,
 * so editing/clearing a reference mid-session never silently mixes origins. */
object PositionErrorCsv {
    val header = "week,tow,Q,source,reference_set,reference_lat_deg,reference_lon_deg," +
        "reference_height_m,reference_height_type,height_anomaly_m,reference_ellipsoid_height_m," +
        "error_valid,vertical_valid,E_m,N_m,U_m,horizontal_error_m,spatial_error_m,reference_frame\n"

    fun row(reference: ReferencePosition?, solution: SolutionCoordinate, source: String): String {
        require(source == "LIVE" || source == "POST")
        val error = PositionErrorCalculator.compare(reference, solution)
        return listOf(solution.week, solution.tow.takeIf(Double::isFinite), solution.q, source,
            if (reference == null) 0 else 1, reference?.latitude, reference?.longitude,
            reference?.heightMeters, reference?.heightType?.name, reference?.heightAnomalyMeters,
            reference?.ellipsoidHeightMeters, if (error == null) 0 else 1,
            if (error?.upMeters == null) 0 else 1, error?.eastMeters, error?.northMeters,
            error?.upMeters, error?.horizontalMeters, error?.spatialMeters, reference?.let { "WGS84" })
            .joinToString(",") { it?.toString() ?: "" } + "\n"
    }
}
