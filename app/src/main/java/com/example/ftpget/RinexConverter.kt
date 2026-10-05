package com.example.ftpget // ★ 注意核对包名

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import kotlin.math.abs

/**
 * 离线格式转换：本项目 Raw TXT → 识别星座/信号 → 合并历元和卫星 → 预处理 → RINEX 3.05 OBS。
 * 与实时 LivePppNative/gnss_adapter 是两条独立入口，本对象并不调用实时 adapter。
 *
 * 注意：这里仍有自己的 ADR 0.50 m 门限、RESET/SLIP 当历元不写相位等规则，
 * 不能因为都使用 Android Raw 就认定与实时入口完全等价。本次只解释现有规则，不修改它们。
 * convert 会读入整份文件并建立多个列表，适用于离线转换，不是逐历元流式实时接口。
 * 对象内含共享 signalMap/lastStats，当前界面禁止重复点击转换；不要并发转换两份文件。
 */
object RinexConverter {
    private const val TAG = "RinexConverter"
    private const val CLIGHT = 299792458.0 // 真空光速(m/s)，用于 P=cΔt、λ=c/f。
    private const val LEAP_SECOND = 18 // GLO 时间转换使用的固定 GPST-UTC 秒差，未从 TXT 动态读取。
    private const val MAX_PRR_UNC_MPS = 10.0 // 伪距率 1σ 上限(m/s)，只控制 D 的有效性。
    private const val MAX_TOW_UNC_NS = 500L // 卫星发射时标 1σ 上限(ns)，只控制 P 的有效性。
    private const val MAX_ADR_UNC_METERS = 0.50 // 离线 L 的 ADR 1σ 上限(m)，实时 JNI 当前为 1.0 m。
    private const val MIN_TRAVEL_TIME_SECONDS = 0.001 // 最小传播时间(s)，排除明显错误的时差。
    private const val MAX_TRAVEL_TIME_SECONDS = 0.200 // 最大传播时间(s)，不是 PPP 残差门限。
    private const val DOPPLER_PHASE_JUMP_METERS = 0.50 // 去系统公共项后的相位-多普勒差分门限(m)。
    private const val CODE_JUMP_METERS = 30.0 // 去系统公共项后的码-多普勒跳变门限(m)。
    private const val MW_JUMP_METERS = 5.0 // 双频 MW 组合相邻历元跳变门限(m)。
    private const val MIN_COMMON_MODE_SIGNALS = 3 // 同系统至少 3 条创新量才求公共中位数。
    private const val NEAR_ZERO = 0.0001 // 按当前观测数值单位判断近零/空值；不是统一的“0.1 mm”。

    /*
     * 某些设备 FullBias 改变时不增加 HCDC：接收机时间会整体跳变，1 ms 对应约 300 km 伪距。
     * 这里只归一化同一时钟产生的大跳变，不能用来删除单星真实异常或冻结 FullBias。
     * HCDC 真正变化仍被标记为时钟不连续，并通知载波弧段重新开始。
     */
    private const val IMPLICIT_CLOCK_JUMP_NS = 50_000L       // 50 us
    private const val MAX_CLOCK_EPOCH_GAP_NS = 5_000_000_000L // 5 s

    // 以下是 Android constellationType 编号，不是 RTKLIB SYS_* 的位掩码，禁止直接混用。
    private const val SYS_GPS = 1
    private const val SYS_GLO = 3
    private const val SYS_GAL = 6
    private const val SYS_BDS = 5
    private const val SYS_QZS = 4
    private const val MAX_FRQ = 5 // 每系统最多存 5 种“频段+属性”信号；不是 RTKLIB NFREQ 或物理频数。

    // State 为位掩码：CODE_LOCK=码锁定；TOW=周内时已解码/已知；GLO TOD=日内时。
    // 测试某一位用 (state and mask)!=0，不能用 state==mask（可能同时有多个状态）。
    private const val STATE_CODE_LOCK = 1
    private const val STATE_TOW_DECODED = 8
    private const val STATE_GLO_STRING_SYNC = 64
    private const val STATE_GLO_TOD_DECODED = 128
    private const val STATE_TOW_KNOWN = 16384
    private const val STATE_GLO_TOD_KNOWN_NEW = 32768

    // ADR 状态与跟踪 State 分开：VALID=1、RESET=2、SLIP=4、半周已解决=8、已报告=16。
    // LLI_SLIP 是输出 RINEX 的失锁/周跳位，不是 Android ADR_SLIP 的数值 4。
    private const val GPS_ADR_STATE_CYCLE_SLIP = 4
    private const val GPS_ADR_STATE_RESET = 2
    private const val GPS_ADR_STATE_HALF_CYCLE_RESOLVED = 8
    private const val GPS_ADR_STATE_HALF_CYCLE_REPORTED = 16
    private const val LLI_SLIP = 0x01

    /** 一条 Raw 信号的中间结构，仍保持 Android 原始单位，尚未转换成 RINEX 观测。 */
    class GnssSat {
        var timeNanos: Long = 0 // 硬件时钟(ns)，TXT[2]。
        var fullBiasNanos: Long = 0 // GPST 大整数钟偏(ns)，TXT[5]。
        var biasNanos: Double = 0.0 // 精细钟偏(ns)，TXT[6]。
        var hardwareClockDiscontinuityCount: Int = 0 // 硬件时钟不连续计数，TXT[10]。
        var svid: Int = 0 // 星座内卫星号，TXT[11]；QZSS 识别时作编号归一化。
        var timeOffsetNanos: Double = 0.0 // 信号测量相对 Clock 的偏移(ns)，TXT[12]。
        var state: Int = 0 // 跟踪/时标状态位，TXT[13]。
        var receivedSvTimeNanos: Long = 0 // 卫星信号发射时标(ns)，TXT[14]。
        var receivedSvTimeUncertaintyNanos: Long = 0 // 发射时标 1σ(ns)，TXT[15]。
        var cn0DbHz: Double = 0.0 // C/N0(dB-Hz)，TXT[16]。
        var pseudorangeRateMps: Double = 0.0 // 伪距率(m/s)，TXT[17]。
        var pseudorangeRateUncertaintyMps: Double = 0.0 // 伪距率 1σ(m/s)，TXT[18]。
        var adrState: Int = 0 // ADR 有效/重置/周跳/半周状态，TXT[19]。
        var adrMeters: Double = 0.0 // 累计距离(m)，TXT[20]，用于生成 L。
        var adrUncertaintyMeters: Double = 0.0 // ADR 1σ(m)，TXT[21]。
        var carrierFrequencyHz: Double = 0.0 // 报告载频(Hz)，TXT[22]。
        var multipathIndicator: Int = 0 // 多路径枚举，TXT[26]；当前只解析，不按此删除观测。
        var constellationType: Int = 0 // Android 星座编号，TXT[28]。
        var codeType: String = "" // 信号属性，TXT[35]；仅 C/Q 等单字母不能单独表示频段。
        var sys: Int = 0 // 识别后的本转换器星座编号，当前沿用 Android 数值。
        var signalName: String = "" // 识别后的 RINEX 信号，如 1C/5Q；空表示未识别。
        // 累计静默钟跳修正(ns)：从 Time-FullBias 中减去，整历元所有信号使用同一个值。
        var receiverClockCorrectionNanos: Long = 0L
    }

    /** 一颗卫星在一个历元的多信号观测；数组下标由 signalMap 决定，不是固定 F1/F2/F3 槽。 */
    class RnxSat(val sys: Int, val prn: Int) {
        val p = DoubleArray(MAX_FRQ) // RINEX C 码观测，单位 m；0 在本实现中表示缺失。
        val l = DoubleArray(MAX_FRQ) // RINEX L 相位，单位 cycle；ADR/λ，不从旧 CarrierPhase 得到。
        val d = DoubleArray(MAX_FRQ) // RINEX D 多普勒，单位 Hz；-PRR/λ。
        val s = DoubleArray(MAX_FRQ) // RINEX S 载噪比，单位 dB-Hz，不是 SnrInDb。
        val lli = IntArray(MAX_FRQ) // 相位失锁标志位，非相位质量评分。
        val frequencyHz = DoubleArray(MAX_FRQ) // 名义载频(Hz)，用于波长和双频组合。
        // 没有 P/L 的卫星不输出，即便只带 D/S；这影响最终文件中的观测数量。
        fun isEmpty(): Boolean = p.all { it == 0.0 } && l.all { it == 0.0 }
    }

    class RnxEpoch {
        var time = DoubleArray(6) // GPST 日历表示：[年,月,日,时,分,秒(可含小数)]。
        var receiverTimeNanos: Long = 0L // 去静默钟跳后的接收机时间(ns)，供相邻历元 dt 使用。
        val sats = mutableListOf<RnxSat>()
        var clockDiscontinuity = false // 整历元时钟中断标记，finishEpoch 将其传播到各信号 LLI。
    }

    /**
     * 转换诊断计数，不等于 PPP 实际使用计数。codeAccepted/phaseAccepted/dopplerAccepted
     * 在初次赋值时递增：重复信号覆盖、预处理删码、最终空卫星过滤以后，实际写出数可更少。
     * dopplerSlipDetected/MW/ADR 是不同检测来源，不能直接相加当作独立周跳总数。
     */
    data class ConversionStats(
        var rawMeasurements: Int = 0, // parseLine 成功的 Raw 行数，不含无法解析的行。
        var unsupportedSignals: Int = 0, // 后续名义频率/GLO 编号拒绝数，不涵盖全部未识别情况。
        var invalidTracking: Int = 0, // 基础身份/频率/CN0 检查失败数，可包含未识别信号。
        var codeAccepted: Int = 0, // 初次通过 P 检查并赋值的次数。
        var phaseAccepted: Int = 0, // 初次通过 L 检查并赋值的次数。
        var dopplerAccepted: Int = 0, // 初次通过 D 检查并赋值的次数。
        var codeJumpRejected: Int = 0, // 多普勒预测码跳检测删除 P 的次数。
        var dopplerSlipDetected: Int = 0, // 相位-多普勒检测新置周跳的次数。
        var mwSlipDetected: Int = 0, // MW 双频组合跳变的对数，每次影响两个 LLI。
        var adrStateSlip: Int = 0, // ADR 状态引起且当历元有 L 的失锁标记次数。
        var clockDiscontinuityEpochs: Int = 0 // finishEpoch 标记时钟中断的历元数。
    )

    var lastStats = ConversionStats()
        private set

    // 历史按“星座+卫星+信号下标”隔离，避免把同星不同载频的相位当作连续弧段。
    private data class SignalKey(val sys: Int, val prn: Int, val signal: Int)
    private data class SignalHistory(
        val timeNanos: Long,
        val codeMeters: Double,
        val phaseCycles: Double,
        val dopplerHz: Double,
        val frequencyHz: Double
    )
    private data class Innovation(val sat: RnxSat, val signal: Int, val value: Double)

    // 五个系统 G/R/E/C/J 的观测类型表。信号如 1C：频段 1 + 属性 C；C1C/L1C 是不同观测量。
    private val signalMap = Array(5) { mutableListOf<String>() }
    private val glonassSlots = TreeMap<Int, Int>()

    // 仅用于 RINEX 文件头，不是定位真值约束；approxPos 为 ECEF X/Y/Z(m)，不是经纬高。
    // antennaDelta 为 H/E/N(m)，不能误当 X/Y/Z；当前默认全零表示未设置这些元数据。
    var markerName = "GNSSLOG"
    var observer = "SWJTU"
    var agency = "SWJTU"
    var approxPos = doubleArrayOf(0.0, 0.0, 0.0)
    var antennaDelta = doubleArrayOf(0.0, 0.0, 0.0)

    /**
     * @param inputFile 已停止记录的本项目 54 列 Raw TXT；# 行跳过，不读取 RINEX/导航产品。
     * @param outputFile 目标 OBS 文件，FileWriter 将覆盖同名内容；调用前应留存需保留的旧结果。
     * @return 文件成功写出为 true；不表示观测精度合格或 PPP 已成功。
     */
    fun convert(inputFile: File, outputFile: File): Boolean {
        signalMap.forEach { it.clear() }
        glonassSlots.clear()
        lastStats = ConversionStats()
        val rawEpochs = mutableListOf<GnssSat>()

        Log.d(TAG, "开始读取原始数据: ${inputFile.name}")
        try {
            BufferedReader(FileReader(inputFile)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line!!.startsWith("Raw")) {
                        val sat = parseLine(line!!)
                        if (sat != null) {
                            rawEpochs.add(sat)
                            lastStats.rawMeasurements++
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "读取原始观测失败", e)
            return false
        }

        if (rawEpochs.isEmpty()) return false
        // 必须先扫描所有 Raw 以确定头部 SYS / # / OBS TYPES，再按时间组织观测。
        identifySignals(rawEpochs)
        signalMap.forEach { signals -> signals.sortWith(compareBy({ it[0] }, { it[1] })) }
        val rinexEpochs = processEpochs(rawEpochs)
        if (rinexEpochs.isEmpty()) return false
        preprocessRinexEpochs(rinexEpochs)
        val success = writeRinexFile(outputFile, rinexEpochs)
        Log.i(TAG, "转换统计: $lastStats")
        return success
    }

    /**
     * 依文件原顺序处理（不先排序）；校正后的接收机时间差 >100 ms 时切分历元。
     * 同历元内按 sys+PRN 合并多信号；同一信号重复时后面的有效赋值可覆盖前值，
     * 并非实时 adapter 的“signal policy 优先，再按质量选择”策略。
     */
    private fun processEpochs(rawList: List<GnssSat>): List<RnxEpoch> {
        val rinexEpochs = mutableListOf<RnxEpoch>()
        var currentEpoch = RnxEpoch()
        var lastTimeMillis = correctedReceiverTimeNanos(rawList[0]) / 1_000_000L

        var lastClockDiscontinuityCount: Int? = null
        var activeClockTimeNanos = Long.MIN_VALUE
        var lastClockTimeNanos = Long.MIN_VALUE
        var lastRawReceiverTimeNanos = 0L
        var clockCorrectionNanos = 0L
        var syntheticClockDiscontinuity = false
        val lastAdrState = mutableMapOf<SignalKey, Int>()
        val pendingAdrSlip = mutableSetOf<SignalKey>()

        for (obs in rawList) {
            /*
             * 一个 Clock 对应多个信号行；以 TimeNanos 的变化判定新 Clock，钟跳只计算一次。
             * step=(当前原始接收机时差)-(硬件 TimeNanos 时差)，累计 correction 让历元连续。
             * 触发条件：HCDC 未变、间隔 0～5 s、|step|>=50 μs；不是对所有钟偏作平滑。
             */
            if (obs.timeNanos != activeClockTimeNanos) {
                val rawReceiverTimeNanos = obs.timeNanos - obs.fullBiasNanos
                val hcdcChanged = lastClockDiscontinuityCount != null &&
                    obs.hardwareClockDiscontinuityCount != lastClockDiscontinuityCount
                syntheticClockDiscontinuity = false

                if (lastClockTimeNanos != Long.MIN_VALUE) {
                    val elapsedNanos = obs.timeNanos - lastClockTimeNanos
                    val rawReceiverDelta = rawReceiverTimeNanos - lastRawReceiverTimeNanos
                    val clockStepNanos = rawReceiverDelta - elapsedNanos

                    if (!hcdcChanged && elapsedNanos in 1..MAX_CLOCK_EPOCH_GAP_NS &&
                        abs(clockStepNanos) >= IMPLICIT_CLOCK_JUMP_NS) {
                        clockCorrectionNanos += clockStepNanos
                        syntheticClockDiscontinuity = true
                        Log.w(TAG, "Normalized unreported GnssClock step: " +
                            "step=${clockStepNanos}ns, correction=${clockCorrectionNanos}ns, " +
                            "timeNanos=${obs.timeNanos}")
                    }
                }
                activeClockTimeNanos = obs.timeNanos
                lastClockTimeNanos = obs.timeNanos
                lastRawReceiverTimeNanos = rawReceiverTimeNanos
            }
            obs.receiverClockCorrectionNanos = clockCorrectionNanos
            val currentTimeMillis = correctedReceiverTimeNanos(obs) / 1_000_000L

            // 历元切分，放宽阈值至 100ms 防止同秒数据被切碎
            if (abs(currentTimeMillis - lastTimeMillis) > 100) {
                if (currentEpoch.sats.isNotEmpty()) {
                    finishEpoch(currentEpoch)
                    rinexEpochs.add(currentEpoch)
                }
                currentEpoch = RnxEpoch()
                lastTimeMillis = currentTimeMillis
            }

            if (lastClockDiscontinuityCount != null &&
                obs.hardwareClockDiscontinuityCount != lastClockDiscontinuityCount) {
                currentEpoch.clockDiscontinuity = true
            }
            if (syntheticClockDiscontinuity) {
                currentEpoch.clockDiscontinuity = true
            }
            lastClockDiscontinuityCount = obs.hardwareClockDiscontinuityCount

            currentEpoch.time = getRinexTime(obs)
            currentEpoch.receiverTimeNanos = correctedReceiverTimeNanos(obs)

            if (!isTrackingUsable(obs)) {
                lastStats.invalidTracking++
                continue
            }

            var sat = currentEpoch.sats.find { it.sys == obs.sys && it.prn == obs.svid }
            if (sat == null) {
                sat = RnxSat(obs.sys, obs.svid)
                currentEpoch.sats.add(sat)
            }

            val freqIdx = getSignalIndex(obs.sys, obs.signalName)
            if (freqIdx !in 0 until MAX_FRQ) continue

            val nominalFreq = getNominalFrequency(obs.sys, obs.carrierFrequencyHz, obs.svid)
            if (nominalFreq == 0.0) {
                lastStats.unsupportedSignals++
                continue
            }
            val lambda = CLIGHT / nominalFreq // 波长(m/cycle)，用名义频率避免报告载频微小偏差累积。
            sat.frequencyHz[freqIdx] = nominalFreq

            if (obs.sys == SYS_GLO && obs.svid > 80) {
                lastStats.unsupportedSignals++
                continue
            }

            // P/L/D 分别判有效：P 需要码锁及明确发射时标，D 看 PRR，L 看 ADR 状态。
            // TOW 暂时不可用不自动删除有效 L；没有合法 P 则保持空，不根据 L 编造码观测。
            if (isCodeValid(obs)) {
                val prSeconds = calculatePrecisePseudorangeSeconds(obs)
                if (prSeconds in MIN_TRAVEL_TIME_SECONDS..MAX_TRAVEL_TIME_SECONDS) {
                    sat.p[freqIdx] = prSeconds * CLIGHT
                    lastStats.codeAccepted++
                }
            }
            if (isDopplerValid(obs)) {
                sat.d[freqIdx] = -obs.pseudorangeRateMps / lambda
                lastStats.dopplerAccepted++
            }
            val key = SignalKey(obs.sys, obs.svid, freqIdx)
            val rawAdrSlip = (obs.adrState and
                (GPS_ADR_STATE_CYCLE_SLIP or GPS_ADR_STATE_RESET)) != 0
            // 离线路径 RESET/SLIP 当历元 L 无效，先记 pending；恢复有效 L 时补 LLI_SLIP。
            if (rawAdrSlip) pendingAdrSlip.add(key)

            if (isPhaseValid(obs)) {
                sat.l[freqIdx] = obs.adrMeters / lambda
                lastStats.phaseAccepted++
                if (pendingAdrSlip.remove(key)) {
                    sat.lli[freqIdx] = sat.lli[freqIdx] or LLI_SLIP
                }
            }
            sat.s[freqIdx] = obs.cn0DbHz // 严格使用载噪比字段

            val previousAdrState = lastAdrState[key]
            val adrValid = (obs.adrState and 1) != 0
            val previousAdrValid = previousAdrState?.let { (it and 1) != 0 }
            val halfCycleUnresolved =
                (obs.adrState and GPS_ADR_STATE_HALF_CYCLE_REPORTED) != 0 &&
                    (obs.adrState and GPS_ADR_STATE_HALF_CYCLE_RESOLVED) == 0
            val previousHalfCycleUnresolved = previousAdrState?.let {
                (it and GPS_ADR_STATE_HALF_CYCLE_REPORTED) != 0 &&
                    (it and GPS_ADR_STATE_HALF_CYCLE_RESOLVED) == 0
            }
            if (previousAdrValid != null && previousAdrValid != adrValid &&
                abs(sat.l[freqIdx]) > NEAR_ZERO) {
                sat.lli[freqIdx] = sat.lli[freqIdx] or LLI_SLIP
            }
            // 本离线实现仅在半周未解决状态发生变化时置 LLI_SLIP；稳定未解决不每秒写 LLI=2。
            // 实时 adapter 当前会设置 LLI_HALFC，规则不同，需用 obs dump 检验，不能推断等价。
            if (previousHalfCycleUnresolved != null &&
                previousHalfCycleUnresolved != halfCycleUnresolved &&
                abs(sat.l[freqIdx]) > NEAR_ZERO) {
                sat.lli[freqIdx] = sat.lli[freqIdx] or LLI_SLIP
            }
            if ((sat.lli[freqIdx] and LLI_SLIP) != 0 &&
                abs(sat.l[freqIdx]) > NEAR_ZERO) {
                lastStats.adrStateSlip++
            }
            lastAdrState[key] = obs.adrState
        }

        if (currentEpoch.sats.isNotEmpty()) {
            finishEpoch(currentEpoch)
            rinexEpochs.add(currentEpoch)
        }
        return rinexEpochs
    }

    // HCDC 或合成钟跳影响整个历元，给当历元所有非空相位置失锁位，防止跨钟跳沿用模糊度。
    private fun finishEpoch(epoch: RnxEpoch) {
        if (!epoch.clockDiscontinuity) return
        lastStats.clockDiscontinuityEpochs++
        epoch.sats.forEach { sat ->
            for (i in 0 until MAX_FRQ) {
                if (abs(sat.l[i]) > NEAR_ZERO) sat.lli[i] = sat.lli[i] or LLI_SLIP
            }
        }
    }

    /**
     * 在 RINEX 观测域作因果预处理：只用当前与历史历元，不用未来观测。
     * 包括相位-多普勒一致性、码-多普勒跳变和 MW 双频周跳；不执行伪距平滑。
     * 时间间隔只接受 0.2～5.0 s；不足三条同系统创新量时跳过该系统公共项检测。
     * 这些检测并非 PPP 后验残差抗差，两层处理不能混为一谈。
     */
    private fun preprocessRinexEpochs(epochs: List<RnxEpoch>) {
        val history = mutableMapOf<SignalKey, SignalHistory>()
        val mwHistory = mutableMapOf<SignalKey, Double>()

        for (epoch in epochs) {
            val phaseBySystem = mutableMapOf<Int, MutableList<Innovation>>()
            val codeBySystem = mutableMapOf<Int, MutableList<Innovation>>()

            for (sat in epoch.sats) {
                for (k in 0 until MAX_FRQ) {
                    val freq = sat.frequencyHz[k]
                    if (freq <= 0.0) continue
                    val key = SignalKey(sat.sys, sat.prn, k)
                    val previous = history[key] ?: continue
                    val dt = (epoch.receiverTimeNanos - previous.timeNanos) * 1e-9
                    if (dt !in 0.2..5.0 || previous.frequencyHz <= 0.0) continue
                    val lambda = CLIGHT / freq

                    if (abs(sat.l[k]) > NEAR_ZERO &&
                        abs(previous.phaseCycles) > NEAR_ZERO &&
                        abs(sat.d[k]) > NEAR_ZERO &&
                        abs(previous.dopplerHz) > NEAR_ZERO &&
                        (sat.lli[k] and LLI_SLIP) == 0) {
                        // D=-速度/λ，因此 ΔL + 平均D*dt 应接近零；乘 λ 得到米域创新量。
                        val innovationMeters = (sat.l[k] - previous.phaseCycles +
                            0.5 * (previous.dopplerHz + sat.d[k]) * dt) * lambda
                        phaseBySystem.getOrPut(sat.sys) { mutableListOf() }
                            .add(Innovation(sat, k, innovationMeters))
                    }
                    if (sat.p[k] > 0.0 && previous.codeMeters > 0.0 &&
                        abs(sat.d[k]) > NEAR_ZERO &&
                        abs(previous.dopplerHz) > NEAR_ZERO) {
                        // 用梯形积分的 Doppler 预测当前 P，不将预测值写回观测，不是 Hatch 平滑。
                        val predictedCode = previous.codeMeters -
                            lambda * 0.5 * (previous.dopplerHz + sat.d[k]) * dt
                        codeBySystem.getOrPut(sat.sys) { mutableListOf() }
                            .add(Innovation(sat, k, sat.p[k] - predictedCode))
                    }
                }
            }

            for ((_, innovations) in phaseBySystem) {
                if (innovations.size < MIN_COMMON_MODE_SIGNALS) continue
                // 系统公共中位数用于减少接收机公共变化影响；单星偏离超过门限才标记。
                val common = median(innovations.map { it.value })
                for (item in innovations) {
                    if (abs(item.value - common) <= DOPPLER_PHASE_JUMP_METERS) continue
                    item.sat.lli[item.signal] = item.sat.lli[item.signal] or LLI_SLIP
                    lastStats.dopplerSlipDetected++
                }
            }
            for ((_, innovations) in codeBySystem) {
                if (innovations.size < MIN_COMMON_MODE_SIGNALS) continue
                val common = median(innovations.map { it.value })
                for (item in innovations) {
                    if (abs(item.value - common) <= CODE_JUMP_METERS) continue
                    item.sat.p[item.signal] = 0.0
                    lastStats.codeJumpRejected++
                }
            }

            // MW 使用两个不同载频及相应 P/L，量纲为 m；与历史 MW 相差超过 5 m 时两信号置失锁。
            // 手机码噪声会影响 MW，此规则不是“所有超过 5 m 的 P 都直接删除”。
            for (sat in epoch.sats) {
                val f0 = sat.frequencyHz[0]
                if (f0 <= 0.0) continue
                for (k in 1 until MAX_FRQ) {
                    val fk = sat.frequencyHz[k]
                    if (fk <= 0.0 || abs(f0 - fk) < 1.0 ||
                        sat.p[0] <= 0.0 || sat.p[k] <= 0.0 ||
                        abs(sat.l[0]) <= NEAR_ZERO || abs(sat.l[k]) <= NEAR_ZERO) continue
                    val mw = (sat.l[0] - sat.l[k]) * CLIGHT / (f0 - fk) -
                        (f0 * sat.p[0] + fk * sat.p[k]) / (f0 + fk)
                    val key = SignalKey(sat.sys, sat.prn, k)
                    val previousMw = mwHistory[key]
                    if (previousMw != null &&
                        (sat.lli[0] and LLI_SLIP) == 0 &&
                        (sat.lli[k] and LLI_SLIP) == 0 &&
                        abs(mw - previousMw) > MW_JUMP_METERS) {
                        sat.lli[0] = sat.lli[0] or LLI_SLIP
                        sat.lli[k] = sat.lli[k] or LLI_SLIP
                        lastStats.mwSlipDetected++
                    }
                    mwHistory[key] = mw
                }
            }

            for (sat in epoch.sats) for (k in 0 until MAX_FRQ) {
                val freq = sat.frequencyHz[k]
                if (freq <= 0.0) continue
                history[SignalKey(sat.sys, sat.prn, k)] = SignalHistory(
                    epoch.receiverTimeNanos, sat.p[k], sat.l[k], sat.d[k], freq
                )
            }
        }
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else 0.5 * (sorted[middle - 1] + sorted[middle])
    }

    /**
     * 返回传播时间(s)，调用方乘 c 得 P(m)。绝对 GPST 纳秒很大，先用 Long 求差/取模，
     * 最后才转 Double，避免把 ~10^18 ns 直接转浮点导致精细时差丢失。
     * 信号接收时刻加入 TimeOffsetNanos；当前 toLong 截掉其亚纳秒部分。
     * GPS/GAL/QZS 用周内时，BDT=GPST-14 s，GLO 用 UTC+3 h 日内时，随后修正跨周/跨日。
     */
    private fun calculatePrecisePseudorangeSeconds(obs: GnssSat): Double {
        val gpsTimeNanos = correctedReceiverTimeNanos(obs) + obs.timeOffsetNanos.toLong()
        val tTxNanos = obs.receivedSvTimeNanos
        val weekNanos = 604800L * 1000000000L
        val dayNanos = 86400L * 1000000000L
        var tRxModNanos = 0L

        when (obs.sys) {
            SYS_GPS, SYS_GAL, SYS_QZS -> tRxModNanos = gpsTimeNanos % weekNanos
            SYS_BDS -> tRxModNanos = (gpsTimeNanos - 14000000000L) % weekNanos // BDS 差 14s
            SYS_GLO -> tRxModNanos = (gpsTimeNanos % dayNanos) + (3 * 3600 - LEAP_SECOND) * 1000000000L
        }

        var flightTimeNanos = tRxModNanos - tTxNanos
        val halfWeekNanos = 302400L * 1000000000L
        val halfDayNanos = 43200L * 1000000000L

        if (obs.sys != SYS_GLO) {
            if (flightTimeNanos > halfWeekNanos) flightTimeNanos -= weekNanos
            else if (flightTimeNanos < -halfWeekNanos) flightTimeNanos += weekNanos
        } else {
            if (flightTimeNanos > halfDayNanos) flightTimeNanos -= dayNanos
            else if (flightTimeNanos < -halfDayNanos) flightTimeNanos += dayNanos
        }
        return (flightTimeNanos - obs.biasNanos) * 1e-9
    }

    /** 写出 RINEX 3.05：头标签从第 61 列起；C/L/D/S 均按 signalMap 顺序写。 */
    private fun writeRinexFile(file: File, epochs: List<RnxEpoch>): Boolean {
        try {
            BufferedWriter(FileWriter(file)).use { writer ->
                writer.write(pad60("     3.05           OBSERVATION DATA    M: Mixed") + "RINEX VERSION / TYPE\n")

                val sdf = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val manufacturer = android.os.Build.MANUFACTURER ?: "UNKNOWN"
                val androidVersion = android.os.Build.VERSION.RELEASE ?: "UNKNOWN"
                val runByDate = String.format("%-20s%-20s%s UTC", "GeoLogKt", manufacturer, sdf.format(Date()))
                writer.write(pad60(runByDate) + "PGM / RUN BY / DATE\n")

                // 头部设备、测站、天线信息为元数据；UNKNOWN 表示未提供真实天线型号。
                writer.write(pad60(markerName) + "MARKER NAME\n")
                writer.write(pad60("UNKNOWN") + "MARKER NUMBER\n")
                writer.write(pad60("GEODETIC") + "MARKER TYPE\n")
                writer.write(String.format(Locale.US, "%-20s%-40s", observer, agency) + "OBSERVER / AGENCY\n")

                val recInfo = String.format("%-20s%-20s%-20s", "000001", "Generic Device", androidVersion)
                writer.write(pad60(recInfo) + "REC # / TYPE / VERS\n")
                writer.write(pad60("UNKNOWN") + "ANT # / TYPE\n")

                writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  ", approxPos[0], approxPos[1], approxPos[2]) + "APPROX POSITION XYZ\n")
                writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  ", antennaDelta[0], antennaDelta[1], antennaDelta[2]) + "ANTENNA: DELTA H/E/N\n")
                writer.write(pad60(String.format(Locale.US,
                    "QC RAW=%d CODE=%d PHASE=%d DOP=%d",
                    lastStats.rawMeasurements, lastStats.codeAccepted,
                    lastStats.phaseAccepted, lastStats.dopplerAccepted)) + "COMMENT\n")
                writer.write(pad60(String.format(Locale.US,
                    "QC REJ_CODE=%d SLIP_DOP=%d SLIP_MW=%d",
                    lastStats.codeJumpRejected, lastStats.dopplerSlipDetected,
                    lastStats.mwSlipDetected)) + "COMMENT\n")
                writer.write(pad60(String.format(Locale.US,
                    "QC SLIP_ADR=%d CLOCK_EPOCH=%d",
                    lastStats.adrStateSlip, lastStats.clockDiscontinuityEpochs)) + "COMMENT\n")

                for (i in 0..4) {
                    val sysChar = getSysCharFromIndex(i)
                    val sigs = signalMap[i]
                    if (sigs.isEmpty()) continue
                    val obsTypes = mutableListOf<String>()
                    sigs.forEach { sig ->
                        val band = sig[0]
                        val attr = sig[1]
                        obsTypes.addAll(listOf("C$band$attr", "L$band$attr", "D$band$attr", "S$band$attr"))
                    }
                    val chunks = obsTypes.chunked(13) // 每行最多 13 个观测类型，超出写续行。
                    for ((chunkIdx, chunk) in chunks.withIndex()) {
                        val sb = StringBuilder()
                        if (chunkIdx == 0) sb.append(String.format(Locale.US, "%c %4d", sysChar, obsTypes.size))
                        else sb.append("      ")
                        chunk.forEach { type -> sb.append(" $type") }
                        writer.write(pad60(sb.toString()) + "SYS / # / OBS TYPES\n")
                    }
                }

                if (glonassSlots.isNotEmpty()) {
                    var count = 0
                    val sb = StringBuilder(String.format(Locale.US, "%3d ", glonassSlots.size))
                    for ((prn, k) in glonassSlots) {
                        sb.append(String.format(Locale.US, "R%02d %2d ", prn, k))
                        count++
                        if (count == 8) {
                            writer.write(pad60(sb.toString()) + "GLONASS SLOT / FRQ #\n")
                            sb.clear().append("    ")
                            count = 0
                        }
                    }
                    if (count > 0) writer.write(pad60(sb.toString()) + "GLONASS SLOT / FRQ #\n")
                }

                if (epochs.isNotEmpty()) {
                    val t = epochs[0].time
                    val timeStr = String.format(Locale.US, "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS",
                        t[0].toInt(), t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), t[5])
                    writer.write(pad60(timeStr) + "TIME OF FIRST OBS\n")
                }
                writer.write(pad60("") + "END OF HEADER\n")

                // “>” 行记录 GPST 年月日时分秒、事件标志及有效卫星数，不使用 TXT 第 1 列。
                for (epoch in epochs) {
                    val validSats = epoch.sats.filter { !it.isEmpty() }
                    if (validSats.isEmpty()) continue
                    val t = epoch.time
                    writer.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  0 %2d\n",
                        t[0].toInt(), t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), t[5], validSats.size))

                    val sortedSats = validSats.sortedWith(compareBy({ getSysPriority(it.sys) }, { it.prn }))
                    for (sat in sortedSats) {
                        writer.write(String.format(Locale.US, "%c%02d", getSysChar(sat.sys), sat.prn))
                        val sysIdx = getSysIndex(sat.sys)
                        for (k in 0 until minOf(signalMap[sysIdx].size, MAX_FRQ)) {
                            writeObsValue(writer, sat.p[k])
                            if (abs(sat.l[k]) > NEAR_ZERO) {
                                // 每个观测域宽 16 字符：14 位数值+1 位 LLI+1 位 SSI；L 数值保留 0.001 cycle。
                                val lliStr = if (sat.lli[k] != 0) sat.lli[k].toString() else " "
                                writer.write(String.format(Locale.US, "%14.3f%s ", sat.l[k], lliStr))
                            } else writer.write("                ")
                            writeObsValue(writer, sat.d[k])
                            writeObsValue(writer, sat.s[k])
                        }
                        writer.write("\n")
                    }
                }
            }
            return true
        } catch (e: Exception) { return false }
    }

    // 接收机整数 GPST(ns)，暂不扣 Bias 的浮点细项；传播时间最后才扣它。
    private fun correctedReceiverTimeNanos(obs: GnssSat): Long =
        obs.timeNanos - obs.fullBiasNanos - obs.receiverClockCorrectionNanos

    // Calendar 用 UTC 时区避免本地时区偏移，但输入是 GPST 秒数，输出仍是 GPST 日历！
    // 此处不减闰秒；“用 UTC Calendar”不等于“RINEX 观测历元是 UTC”。
    private fun getRinexTime(obs: GnssSat): DoubleArray {
        val gpsTimeNanos = correctedReceiverTimeNanos(obs) - obs.biasNanos.toLong()
        val seconds = gpsTimeNanos / 1000000000L
        var nanos = gpsTimeNanos % 1000000000L
        if (nanos < 0) nanos += 1000000000L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(1980, 0, 6, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis += seconds * 1000L
        return doubleArrayOf(
            cal.get(Calendar.YEAR).toDouble(), (cal.get(Calendar.MONTH) + 1).toDouble(), cal.get(Calendar.DAY_OF_MONTH).toDouble(),
            cal.get(Calendar.HOUR_OF_DAY).toDouble(), cal.get(Calendar.MINUTE).toDouble(), cal.get(Calendar.SECOND).toDouble() + (nanos / 1e9)
        )
    }

    /**
     * 按固定 CSV 下标取值，最少需要 29 列；CodeType 在 35 列，旧文件缺此列时无法完整识别信号。
     * safeXXX 将空/错误数字回退成 0，因而会丢失部分“缺失”和“真实零”的区别。
     * FullBias=0 直接拒绝；不读取 utcTimeMillis、旧 CarrierPhase 和 31～34 信号间偏差列。
     */
    private fun parseLine(line: String): GnssSat? {
        val tokens = line.split(",")
        if (tokens.size < 29) return null
        return try {
            val sat = GnssSat()
            sat.timeNanos = safeLong(tokens, 2)
            sat.fullBiasNanos = safeLong(tokens, 5)
            if (sat.fullBiasNanos == 0L) return null
            sat.biasNanos = safeDouble(tokens, 6)
            sat.hardwareClockDiscontinuityCount = safeInt(tokens, 10)
            sat.svid = safeInt(tokens, 11)
            sat.timeOffsetNanos = safeDouble(tokens, 12)
            sat.state = safeInt(tokens, 13)
            sat.receivedSvTimeNanos = safeLong(tokens, 14)
            sat.receivedSvTimeUncertaintyNanos = safeLong(tokens, 15)
            sat.cn0DbHz = safeDouble(tokens, 16)
            sat.pseudorangeRateMps = safeDouble(tokens, 17)
            sat.pseudorangeRateUncertaintyMps = safeDouble(tokens, 18)
            sat.adrState = safeInt(tokens, 19)
            sat.adrMeters = safeDouble(tokens, 20)
            sat.adrUncertaintyMeters = safeDouble(tokens, 21)
            sat.carrierFrequencyHz = safeDouble(tokens, 22)
            sat.multipathIndicator = safeInt(tokens, 26)
            sat.constellationType = safeInt(tokens, 28)
            if (tokens.size > 35) sat.codeType = tokens[35].trim()
            sat
        } catch (e: Exception) { null }
    }

    /**
     * 信号身份 = constellationType + CarrierFrequencyHz + CodeType，不能只凭频率识别。
     * 例如 BDS B1I 是 1561.098 MHz 的 2I，B1C 是 1575.42 MHz 的 1D/1P/1X。
     * 此处识别/写入 RINEX 不代表当前 PPP ppp-safe 一定接纳；具体支持表见工程说明。
     */
    private fun identifySignals(rawList: List<GnssSat>) {
        for (obs in rawList) {
            val freq = obs.carrierFrequencyHz
            val code = obs.codeType.trim().uppercase(Locale.US)
            when (obs.constellationType) {
                1 -> {
                    obs.sys = SYS_GPS
                    when {
                        isNear(freq, 1575420000.0) && code in setOf("C", "S", "L", "X", "P", "W", "Y", "M", "N") ->
                            addSignal(SYS_GPS, "1$code", obs)
                        isNear(freq, 1176450000.0) && code in setOf("I", "Q", "X") ->
                            addSignal(SYS_GPS, "5$code", obs)
                    }
                }
                3 -> {
                    obs.sys = SYS_GLO
                    if (freq > 1.59e9) {
                        if (code in setOf("C", "P")) addSignal(SYS_GLO, "1$code", obs)
                        glonassSlots[obs.svid] = Math.round((freq - 1602.0e6) / 0.5625e6).toInt()
                    } else if (freq > 1.23e9 && freq < 1.26e9) {
                        if (code in setOf("C", "P")) addSignal(SYS_GLO, "2$code", obs)
                        glonassSlots[obs.svid] = Math.round((freq - 1246.0e6) / 0.4375e6).toInt()
                    }
                }
                5 -> {
                    obs.sys = SYS_BDS
                    when {
                        isNear(freq, 1561098000.0) && code in setOf("I", "Q", "X") ->
                            addSignal(SYS_BDS, "2$code", obs)
                        isNear(freq, 1575420000.0) && code in setOf("D", "P", "X") ->
                            addSignal(SYS_BDS, "1$code", obs)
                        isNear(freq, 1176450000.0) && code in setOf("D", "P", "Q", "X") ->
                            addSignal(SYS_BDS, "5$code", obs)
                        isNear(freq, 1207140000.0) && code in setOf("I", "Q", "X") ->
                            addSignal(SYS_BDS, "7$code", obs)
                    }
                }
                6 -> {
                    obs.sys = SYS_GAL
                    when {
                        isNear(freq, 1575420000.0) && code in setOf("A", "B", "C", "X", "Z") ->
                            addSignal(SYS_GAL, "1$code", obs)
                        isNear(freq, 1176450000.0) && code in setOf("I", "Q", "X") ->
                            addSignal(SYS_GAL, "5$code", obs)
                        isNear(freq, 1207140000.0) && code in setOf("I", "Q", "X") ->
                            addSignal(SYS_GAL, "7$code", obs)
                    }
                }
                4 -> {
                    if (obs.svid > 192) obs.svid -= 192
                    obs.sys = SYS_QZS
                    when {
                        isNear(freq, 1575420000.0) && code in setOf("C", "S", "L", "X", "Z", "B") ->
                            addSignal(SYS_QZS, "1$code", obs)
                        isNear(freq, 1176450000.0) && code in setOf("I", "Q", "X", "D", "P", "Z") ->
                            addSignal(SYS_QZS, "5$code", obs)
                    }
                }
            }
        }
    }

    // 空观测用 16 个空格而非字符串 0；P(m)、D(Hz)、S(dB-Hz)统一保留 3 位小数。
    private fun writeObsValue(writer: BufferedWriter, value: Double) {
        if (abs(value) > NEAR_ZERO) writer.write(String.format(Locale.US, "%14.3f  ", value))
        else writer.write("                ")
    }

    private fun pad60(content: String): String = content.take(60).padEnd(60)
    // 普通系统归一化为名义频率；GLO FDMA 需卫星频道 k，G1=1602+k*0.5625 MHz、G2=1246+k*0.4375 MHz。
    private fun getNominalFrequency(sysId: Int, rawFreq: Double, prn: Int): Double {
        if (sysId == SYS_GLO) {
            val k = glonassSlots[prn]
            if (k != null) return if (rawFreq > 1.5e9) 1602.0e6 + k * 0.5625e6 else 1246.0e6 + k * 0.4375e6
            return rawFreq
        } else if (sysId == SYS_BDS) {
            if (abs(rawFreq - 1561.098e6) < 1.0e6) return 1561.098e6
        }
        return when (sysId) {
            SYS_GPS, SYS_GAL, SYS_QZS -> when {
                isNear(rawFreq, 1575420000.0) -> 1575420000.0
                isNear(rawFreq, 1176450000.0) -> 1176450000.0
                isNear(rawFreq, 1207140000.0) -> 1207140000.0
                else -> 0.0
            }
            SYS_BDS -> when {
                isNear(rawFreq, 1561098000.0) -> 1561098000.0
                isNear(rawFreq, 1575420000.0) -> 1575420000.0
                isNear(rawFreq, 1176450000.0) -> 1176450000.0
                isNear(rawFreq, 1207140000.0) -> 1207140000.0
                else -> 0.0
            }
            else -> 0.0
        }
    }
    private fun addSignal(sys: Int, sigCode: String, obs: GnssSat) {
        obs.signalName = sigCode
        val sysIdx = getSysIndex(sys)
        if (sysIdx != -1 && !signalMap[sysIdx].contains(sigCode)) signalMap[sysIdx].add(sigCode)
    }

    private fun getSysIndex(sys: Int): Int = when (sys) { SYS_GPS -> 0; SYS_GLO -> 1; SYS_GAL -> 2; SYS_BDS -> 3; SYS_QZS -> 4; else -> -1 }
    private fun getSignalIndex(sys: Int, sigName: String): Int = signalMap[getSysIndex(sys)].indexOf(sigName)
    private fun getSysChar(sys: Int): Char = when (sys) { SYS_GPS -> 'G'; SYS_GLO -> 'R'; SYS_GAL -> 'E'; SYS_BDS -> 'C'; SYS_QZS -> 'J'; else -> ' ' }
    private fun getSysPriority(sys: Int): Int = when (sys) { SYS_GPS -> 1; SYS_GLO -> 2; SYS_GAL -> 3; SYS_BDS -> 4; SYS_QZS -> 5; else -> 99 }
    private fun getSysCharFromIndex(index: Int): Char = arrayOf('G', 'R', 'E', 'C', 'J').getOrElse(index) { ' ' }
    private fun safeDouble(tokens: List<String>, idx: Int): Double = try { if (idx < tokens.size && tokens[idx].trim().isNotEmpty()) tokens[idx].trim().toDouble() else 0.0 } catch (e: Exception) { 0.0 }
    private fun safeLong(tokens: List<String>, idx: Int): Long = try { if (idx < tokens.size && tokens[idx].trim().isNotEmpty()) tokens[idx].trim().toLong() else 0L } catch (e: Exception) { 0L }
    private fun safeInt(tokens: List<String>, idx: Int): Int = try { if (idx < tokens.size && tokens[idx].trim().isNotEmpty()) tokens[idx].trim().toDouble().toInt() else 0 } catch (e: Exception) { 0 }
    // 基础可用性：身份/载频/CN0 合法；这里只要求 CN0>0，不是例如 30 dB-Hz 的强信号筛选。
    private fun isTrackingUsable(obs: GnssSat): Boolean =
        obs.svid > 0 && obs.signalName.isNotEmpty() &&
            obs.carrierFrequencyHz.isFinite() && obs.carrierFrequencyHz > 0.0 &&
            obs.cn0DbHz.isFinite() && obs.cn0DbHz > 0.0

    // P 依赖明确的卫星时标及码锁；GLO 是 STRING_SYNC+TOD，不能套普通 TOW 条件。
    private fun isCodeValid(obs: GnssSat): Boolean {
        if (obs.receivedSvTimeNanos == 0L ||
            obs.receivedSvTimeUncertaintyNanos < 0L ||
            obs.receivedSvTimeUncertaintyNanos > MAX_TOW_UNC_NS) return false
        val codeLock = (obs.state and STATE_CODE_LOCK) != 0
        val tow = (obs.state and STATE_TOW_DECODED) != 0 ||
            (obs.state and STATE_TOW_KNOWN) != 0
        return when (obs.sys) {
            SYS_GPS, SYS_BDS, SYS_QZS -> codeLock && tow
            SYS_GLO -> (obs.state and STATE_GLO_STRING_SYNC) != 0 &&
                ((obs.state and STATE_GLO_TOD_DECODED) != 0 ||
                    (obs.state and STATE_GLO_TOD_KNOWN_NEW) != 0)
            /* A Galileo secondary-code lock alone does not resolve the full
               transmit time. PPP needs an unambiguous TOW pseudorange. */
            SYS_GAL -> codeLock && tow
            else -> false
        }
    }

    // D 的不确定度 0 在此仍允许；不要将 P 不可用或 ADR 无效直接套到 D。
    private fun isDopplerValid(obs: GnssSat): Boolean =
        obs.pseudorangeRateMps.isFinite() &&
            obs.pseudorangeRateUncertaintyMps.isFinite() &&
            obs.pseudorangeRateUncertaintyMps in 0.0..MAX_PRR_UNC_MPS

    // 现有离线 L 门限：VALID、finite、非近零、无 RESET/SLIP、ADR 1σ 在 0～0.50 m。
    // 为保持本次仅注释，不将其改成实时入口的“保留 L+LLI_SLIP、1.0 m”行为。
    private fun isPhaseValid(obs: GnssSat): Boolean =
        (obs.adrState and 1) != 0 && obs.adrMeters.isFinite() &&
            (obs.adrState and (GPS_ADR_STATE_RESET or GPS_ADR_STATE_CYCLE_SLIP)) == 0 &&
            abs(obs.adrMeters) > NEAR_ZERO &&
            obs.adrUncertaintyMeters.isFinite() &&
            obs.adrUncertaintyMeters in 0.0..MAX_ADR_UNC_METERS
    // 10 kHz 是信号识别容差，不代表载频精度或 PPP 测距精度。
    private fun isNear(val1: Double, val2: Double): Boolean = abs(val1 - val2) < 10000.0
}
