package com.example.ftpget

import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPInputStream

/** NAV 的发布日期检索规则；不把“已下载”误认为星历 TOE 对当前历元仍有效。 */
internal object BroadcastNavPolicy {
    private val directory = Regex("^(.*?/BRDC)/(\\d{4})/(\\d{3})/?$", RegexOption.IGNORE_CASE)
    private val namePattern = Regex(
        "^(?:BRDC|BRDM)[A-Z0-9]{5}_[RS]_(\\d{4})(\\d{3})(\\d{2})(\\d{2})_\\d{2}[DH]_MN\\.RNX(?:\\.GZ)?$",
        RegexOption.IGNORE_CASE
    )

    fun epochMillis(name: String): Long? = try {
        val m = namePattern.matchEntire(name)
        if (m == null) null else LocalDate.ofYearDay(m.groupValues[1].toInt(),
            m.groupValues[2].toInt()).atTime(m.groupValues[3].toInt(),
            m.groupValues[4].toInt()).toInstant(ZoneOffset.UTC).toEpochMilli()
    } catch (_: RuntimeException) { null }

    /** 按日历减天，自动处理元旦／闰年；禁止用 doy-1 拼出 000 号日期。 */
    fun directories(first: String, maxDaysBack: Int): List<String> {
        require(maxDaysBack in 0..7)
        val m = directory.matchEntire(first) ?: return listOf(first)
        val start = LocalDate.ofYearDay(m.groupValues[2].toInt(), m.groupValues[3].toInt())
        return (0..maxDaysBack).map { offset ->
            val d = start.minusDays(offset.toLong())
            String.format(Locale.US, "%s/%04d/%03d/", m.groupValues[1], d.year, d.dayOfYear)
        }
    }

    /** 不依赖远端 mtime；同一时刻优先 IGS，再尝试其他 RINEX3 合并产品。 */
    fun candidates(names: Collection<String>, referenceMillis: Long, maxDaysBack: Int = 3): List<String> {
        val today = Instant.ofEpochMilli(referenceMillis).atZone(ZoneOffset.UTC).toLocalDate()
        val oldest = today.minusDays(maxDaysBack.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return names.distinct().mapNotNull { n -> epochMillis(n)?.let { n to it } }
            .filter { (_, t) -> t >= oldest && t <= referenceMillis + 60_000L }
            .sortedWith(compareByDescending<Pair<String, Long>> { it.second }
                .thenByDescending { it.first.startsWith("BRDC00IGS", ignoreCase = true) }
                .thenByDescending { it.first.endsWith(".gz", ignoreCase = true) }
                .thenBy { it.first })
            .map { it.first }
    }

    /** 只提取站内可识别的 NAV 文件名，忽略父目录、排序链接和任意外链路径。 */
    fun listingNames(html: String): List<String> = Regex("href\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        .findAll(html).map { it.groupValues[1] }
        .filter { !it.contains('/') && !it.contains('\\') && epochMillis(it) != null }
        .distinct().toList()

    /** 在 .part 发布前验证：gzip 外壳不等于里面真的是 NAV，也不能喂给当前引擎 RINEX4。 */
    fun validRinex3(file: File, compressed: Boolean): Boolean = try {
        val input = FileInputStream(file)
        val stream = try { if (compressed) GZIPInputStream(input) else input }
            catch (e: Exception) { input.close(); throw e }
        stream.bufferedReader(Charsets.US_ASCII).use { reader ->
            val first = reader.readLine().orEmpty()
            val version = first.take(9).trim().toDoubleOrNull()
            if (version == null || !version.isFinite() || version < 3.0 || version >= 4.0 ||
                !first.contains("RINEX VERSION / TYPE") || first.getOrNull(20) != 'N') return@use false
            var headerEnded = false
            repeat(10_000) {
                val line = reader.readLine() ?: return@use false
                if (line.contains("END OF HEADER")) headerEnded = true
                else if (headerEnded && line.isNotBlank()) {
                    if (!Regex("^[GRECJIS]\\d{2}\\s+\\d{4}\\s+").containsMatchIn(line)) return@use false
                    // 首条星历也必须完整，避免 gzip 完整但 NAV 本身被截断。
                    val continuationLines = if (line[0] == 'R' || line[0] == 'S') 3 else 7
                    repeat(continuationLines) {
                        val continuation = reader.readLine() ?: return@use false
                        if (continuation.isBlank() || !continuation.startsWith("   ")) return@use false
                    }
                    return@use true
                }
            }
            false
        }
    } catch (_: Exception) { false }
}
