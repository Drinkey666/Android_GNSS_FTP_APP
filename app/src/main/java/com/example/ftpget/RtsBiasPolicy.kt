package com.example.ftpget

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** RTS filename ordering is not a certificate of the internal OSB record coverage. */
internal object RtsBiasPolicy {
    private val pattern = Regex(
        "^WUM0MGXRTS_(\\d{4})(\\d{3})(\\d{2})(\\d{2})_01D_05M_OSB\\.BIA(?:\\.GZ)?$",
        RegexOption.IGNORE_CASE
    )

    private fun issue(name: String): Long? = try {
        pattern.matchEntire(name)?.let { m ->
            LocalDate.ofYearDay(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                .atTime(m.groupValues[3].toInt(), m.groupValues[4].toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: RuntimeException) { null }

    // Product stamps use GPST. Keep the same current 18 s offset as the existing app.
    fun directories(referenceUtcMillis: Long): List<String> {
        val year = Instant.ofEpochMilli(referenceUtcMillis).plusSeconds(18)
            .atOffset(ZoneOffset.UTC).year
        return listOf(year, year - 1).map { "/pub/whu/phasebias/$it/bias" }
    }

    fun latest(names: Collection<String>, referenceUtcMillis: Long): String? = names.distinct()
        .mapNotNull { name -> issue(name)?.let { name to it } }
        .filter { it.second <= referenceUtcMillis + 18_000L }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }
            .thenByDescending { it.first.endsWith(".gz", ignoreCase = true) }
            .thenBy { it.first })
        .firstOrNull()?.first
}
