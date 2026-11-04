package com.example.ftpget

import android.location.GnssClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 实时入口的本地产品路径选择，不下载产品，不解析实时 OBS。
 * NAV 按产品日期选最新可用文件；NRT 轨道／钟差按同一起算时次成对选择。
 * 文件名时段不等于实际记录覆盖，JNI 仍需检查实际观测覆盖及星历有效期。
 * VMF3 则按文件名中的 UTC 时刻选择包围首历元的两期；不是任选当天两份文件。
 */
object LivePppProducts {
    private const val GPS_EPOCH_UNIX_MS = 315964800000L // 1980-01-06 00:00:00 UTC 的 Unix 毫秒数。
    private val vmfName = Regex("VMF3_(\\d{8})\\.H(00|06|12|18)", RegexOption.IGNORE_CASE)

    // 仅用于选择 UTC 命名的 VMF3 文件：GPST(ns)→秒差减闰秒→加 Unix 起点。
    // Clock 未提供闰秒时沿用 18 s 后备值，未来闰秒变化须更新；不是系统 Date()。
    private fun epochUtcMillis(clock: GnssClock): Long {
        require(clock.hasFullBiasNanos()) { "等待 GNSS FullBiasNanos" }
        val gpsNanos = clock.timeNanos - clock.fullBiasNanos -
            if (clock.hasBiasNanos()) clock.biasNanos.toLong() else 0L
        val leap = if (clock.hasLeapSecond()) clock.leapSecond else 18
        return GPS_EPOCH_UNIX_MS + gpsNanos / 1_000_000L - leap * 1000L
    }

    // 只有 gz 才解压；已有非空且时间更新的解压目标会复用，这是本地解压检查而非下载缓存策略。
    private fun unpack(file: File): File {
        if (!file.name.endsWith(".gz", ignoreCase = true)) return file
        val target = File(file.parentFile, file.name.dropLast(3))
        if (!target.isFile || target.length() == 0L || target.lastModified() < file.lastModified()) {
            require(FileUtil.unGzip(file, target) && target.length() > 0L) {
                "无法解压 ${file.name}"
            }
        }
        return target
    }

    private fun latest(dir: File, label: String, accept: (String) -> Boolean): File {
        val candidate = dir.listFiles()?.filter {
            it.isFile && it.length() > 0L && accept(it.name.uppercase(Locale.US))
        }?.maxByOrNull { it.lastModified() }
        require(candidate != null) { "缺少 $label 产品，请先下载到 ${dir.absolutePath}" }
        val resolved = unpack(candidate)
        require(resolved.isFile && resolved.length() > 0L) { "$label 文件为空" }
        return resolved
    }

    /** 旧版本可能留下日期更近但损坏的 NAV，不能让它遮住本次成功回退下载的文件。 */
    internal fun resolveNavFile(dir: File, utcMillis: Long): File {
        val byName = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty().associateBy { it.name }
        for (name in BroadcastNavPolicy.candidates(byName.keys, utcMillis)) {
            val candidate = byName.getValue(name)
            try {
                // 先检查 gzip CRC，避免损坏的压缩副本覆盖原本完好的解压文件。
                if (!FtpDownloader.reusableLocalFile(candidate)) continue
                val resolved = unpack(candidate)
                if (BroadcastNavPolicy.validRinex3(resolved, false)) return resolved
            } catch (_: Exception) { /* 继续尝试下一份近期 NAV，不删除用户文件。 */ }
        }
        throw IllegalArgumentException("缺少近期有效的 RINEX3 NAV，请下载最新可用广播星历")
    }

    /** Live sessions use the latest RTS issue, never an old NRT file with a newer filesystem mtime. */
    internal fun resolveBiasFile(dir: File, utcMillis: Long): File {
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty().associateBy { it.name }
        val name = RtsBiasPolicy.latest(files.keys, utcMillis)
            ?: throw IllegalArgumentException("缺少 RTS BIA 产品，请下载最新 RTS 偏差文件")
        val source = files.getValue(name)
        require(FtpDownloader.reusableLocalFile(source)) { "RTS BIA 文件损坏：$name，请重新下载" }
        return unpack(source)
    }

    /** @return 固定 9 项路径，顺序与 LivePppNative.nativeInit/gnss_jni.cpp 一致。 */
    fun resolve(dir: File, clock: GnssClock): Array<String> {
        require(dir.isDirectory) { "产品目录不存在：${dir.absolutePath}" }
        val utcMillis = epochUtcMillis(clock)
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 0L }.orEmpty()
        val byName = files.associateBy { it.name }
        val nav = resolveNavFile(dir, utcMillis)
        val nrtNames = byName.keys.filter { NrtProductPolicy.parse(it) != null }
        val nrtPair = NrtProductPolicy.pairs(nrtNames, utcMillis).firstOrNull()
        require(nrtNames.isEmpty() || nrtPair != null) {
            "缺少同一起算时次且覆盖当前时间的 NRT SP3/CLK，请刷新产品"
        }
        val sp3 = if (nrtPair != null) unpack(byName.getValue(nrtPair.orbit)) else latest(dir, "SP3") {
            it.endsWith(".SP3") || it.endsWith(".SP3.GZ")
        }
        val clk = if (nrtPair != null) unpack(byName.getValue(nrtPair.clock)) else latest(dir, "CLK") {
            it.endsWith(".CLK") || it.endsWith(".CLK.GZ")
        }
        val bia = resolveBiasFile(dir, utcMillis)
        val ionex = latest(dir, "IONEX") {
            it.endsWith(".INX") || it.endsWith(".INX.GZ")
        }
        val orog = latest(dir, "VMF3 orography") { it == "OROGRAPHY_ELL_5X5" }
        val atx = latest(dir, "ATX") { it == "IGS20.ATX" }

        val parser = SimpleDateFormat("yyyyMMddHH", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val grids = dir.listFiles()?.mapNotNull { file ->
            val match = vmfName.matchEntire(file.name) ?: return@mapNotNull null
            val instant = parser.parse(match.groupValues[1] + match.groupValues[2])
                ?: return@mapNotNull null
            instant.time to file
        }?.sortedBy { it.first }.orEmpty()
        // 左期 <= 当前 UTC，右期严格 > 当前 UTC；正好 H12 时仍需 H18 文件。
        // 加载发生在会话开始，长时间越过右期不会在此自动重选/重载产品。
        val left = grids.lastOrNull { it.first <= utcMillis }
        val right = grids.firstOrNull { it.first > utcMillis }
        require(left != null && right != null) {
            "缺少包围当前 GNSS 时间的两期 VMF3 文件；请刷新预报产品"
        }
        return arrayOf(
            nav.absolutePath, sp3.absolutePath, clk.absolutePath, bia.absolutePath,
            ionex.absolutePath, left.second.absolutePath, right.second.absolutePath,
            orog.absolutePath, atx.absolutePath
        )
    }
}
