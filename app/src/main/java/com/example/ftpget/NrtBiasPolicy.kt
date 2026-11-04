package com.example.ftpget

import java.time.LocalDate
import java.time.ZoneOffset

/** NRT OSB only. Filename time orders releases; it does NOT certify OSB record validity. */
internal object NrtBiasPolicy {
    private val pattern = Regex(
        "^WUM0MGXNRT_(\\d{4})(\\d{3})(\\d{2})(\\d{2})_(\\d{2})D_(\\d{2})[SMHD]_OSB\\.BIA(?:\\.GZ)?$",
        RegexOption.IGNORE_CASE
    )

    private fun issue(name: String): Long? = try {
        pattern.matchEntire(name)?.let { m ->
            if (m.groupValues[5].toInt() <= 0 || m.groupValues[6].toInt() <= 0) null
            else LocalDate.ofYearDay(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                .atTime(m.groupValues[3].toInt(), m.groupValues[4].toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    } catch (_: RuntimeException) { null }

    fun latest(names: Collection<String>): String? = names.distinct()
        .mapNotNull { name -> issue(name)?.let { name to it } }
        .sortedWith(compareByDescending<Pair<String, Long>> { it.second }
            .thenByDescending { it.first.endsWith(".gz", ignoreCase = true) }
            .thenBy { it.first })
        .firstOrNull()?.first
}
