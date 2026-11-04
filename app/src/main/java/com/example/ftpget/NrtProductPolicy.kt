package com.example.ftpget

import java.time.LocalDate
import java.time.ZoneOffset

/** 只在当前历元覆盖有效的候选中，选择同一起算时次的 SP3/CLK；不看本地缓存。 */
internal object NrtProductPolicy {
    data class Product(val name: String, val start: Long, val end: Long, val interval: Long, val kind: String)
    data class Pair(val orbit: String, val clock: String, val start: Long)
    private val pattern = Regex(
        "^WUM0MGXNRT_(\\d{4})(\\d{3})(\\d{2})(\\d{2})_(\\d{2})D_(\\d{2})([SMH])_(ORB\\.SP3|CLK\\.CLK)(?:\\.GZ)?$",
        RegexOption.IGNORE_CASE
    )

    fun parse(name: String): Product? = try {
        val m = pattern.matchEntire(name)
        if (m == null) null else {
            val start = LocalDate.ofYearDay(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                .atTime(m.groupValues[3].toInt(), m.groupValues[4].toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
            val days = m.groupValues[5].toLong()
            val interval = m.groupValues[6].toLong() * when (m.groupValues[7].uppercase()) {
                "S" -> 1L; "M" -> 60L; else -> 3600L
            }
            if (days <= 0 || interval <= 0) null else Product(name, start,
                start + days * 86_400_000L, interval, m.groupValues[8].uppercase())
        }
    } catch (_: RuntimeException) { null }

    fun pairs(names: Collection<String>, referenceMillis: Long): List<Pair> = names.distinct()
        .mapNotNull(::parse)
        .filter { it.start <= referenceMillis && referenceMillis < it.end }
        .groupBy { it.start to it.end }.values.mapNotNull { group ->
            fun select(kind: String) = group.filter { it.kind == kind }
                .sortedWith(compareBy<Product> { it.interval }
                    .thenByDescending { it.name.endsWith(".gz", ignoreCase = true) }.thenBy { it.name })
                .firstOrNull()
            val orbit = select("ORB.SP3")
            val clock = select("CLK.CLK")
            if (orbit == null || clock == null) null else Pair(orbit.name, clock.name, orbit.start)
        }.sortedByDescending { it.start }

    /** 两天产品可能起算于昨天或前天，且可能跨 GPS 周／公历年。 */
    fun weekDirectories(referenceMillis: Long): List<String> {
        val today = java.time.Instant.ofEpochMilli(referenceMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val gpsEpoch = LocalDate.of(1980, 1, 6).toEpochDay()
        return (0..2).map { days ->
            val week = (today.minusDays(days.toLong()).toEpochDay() - gpsEpoch) / 7L
            "/pub/gnss/products/mgex/$week"
        }.distinct()
    }
}
