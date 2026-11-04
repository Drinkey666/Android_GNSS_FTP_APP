package com.example.ftpget

import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

data class PostComparisonSummary(val output: File, val epochs: Int, val lastSolution: SolutionCoordinate?)

/** Reader of this app's GPST/WGS84/ellipsoidal .pos output, not arbitrary POS formats. */
object PostPositionComparison {
    private val gpsEpoch = LocalDateTime.of(1980, 1, 6, 0, 0)
    private val format = DateTimeFormatterBuilder().appendPattern("uuuu/MM/dd HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter(Locale.US)

    fun parseLine(line: String): SolutionCoordinate? = runCatching {
        if (line.trimStart().startsWith("%") || line.isBlank()) return null
        val p = line.trim().split(Regex("\\s+"))
        require(p.size >= 6)
        val latitude = p[2].toDouble(); val longitude = p[3].toDouble(); val height = p[4].toDouble()
        require(latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 && height.isFinite())
        val time = LocalDateTime.parse(p[0] + " " + p[1], format)
        val duration = Duration.between(gpsEpoch, time)
        require(duration.seconds >= 0)
        val week = (duration.seconds / 604800L).toInt()
        val tow = (duration.seconds % 604800L).toDouble() + duration.nano * 1e-9
        val xyz = PositionErrorCalculator.ecef(latitude, longitude, height)
        SolutionCoordinate(week, tow, p[5].toInt(), xyz[0], xyz[1], xyz[2])
    }.getOrNull()

    fun export(pos: File, output: File, reference: ReferencePosition?): PostComparisonSummary {
        require(pos.canonicalFile != output.canonicalFile && !output.exists())
        val header = pos.bufferedReader().use { reader ->
            generateSequence { reader.readLine() }.takeWhile { it.startsWith("%") || it.isBlank() }.joinToString("\n")
        }
        require(header.contains("GPST") && header.contains("latitude(deg)") &&
            header.contains("longitude(deg)") && header.contains("WGS84/ellipsoidal")) {
            "Only GPST/WGS84 ellipsoidal latitude-longitude POS output is supported"
        }
        var count = 0; var last: SolutionCoordinate? = null
        val pending = File(output.absolutePath + ".incomplete")
        require(!pending.exists())
        pending.bufferedWriter().use { writer ->
            writer.write(PositionErrorCsv.header)
            pos.forEachLine { line ->
                if (line.isNotBlank() && !line.trimStart().startsWith("%")) {
                    val solution = parseLine(line) ?: error("Invalid POS row: no comparison emitted")
                    writer.write(PositionErrorCsv.row(reference, solution, "POST"))
                    last = solution; count++
                }
            }
        }
        require(count > 0) { "No solution epochs: comparison incomplete" }
        check(pending.renameTo(output)) { "Could not commit comparison output" }
        return PostComparisonSummary(output, count, last)
    }
}
