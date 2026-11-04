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
 * 固定连续硬件钟段首个有效 FullBias/Bias；不重采样，不对观测拟合改正。
 * ADR 1.0m 上限与 Native 一致；RESET/SLIP 当历元不输出连续相位。
 * convert 会读入整份文件并建立多个列表，适用于离线转换，不是逐历元流式实时接口。
 * 对象内含共享 signalMap/lastStats，当前界面禁止重复点击转换；不要并发转换两份文件。
 */
object RinexConverter {
    private const val TAG = "RinexConverter"
    private const val CLIGHT = 299792458.0 // 真空光速(m/s)，用于 P=cΔt、λ=c/f。
    private const val MAX_PRR_UNC_MPS = 10.0 // 伪距率 1σ 上限(m/s)，只控制 D 的有效性。
    private const val MAX_TOW_UNC_NS = 500L // 卫星发射时标 1σ 上限(ns)，只控制 P 的有效性。
    private const val MAX_ADR_UNC_METERS = 1.0 // 离线 L 的 ADR 1σ 上限(m)，实时 JNI 当前为 1.0 m。
    private const val MIN_TRAVEL_TIME_SECONDS = 0.001 // 最小传播时间(s)，排除明显错误的时差。
    private const val MAX_TRAVEL_TIME_SECONDS = 0.200 // 最大传播时间(s)，不是 PPP 残差门限。
    private const val DOPPLER_PHASE_JUMP_METERS = 0.50 // 去系统公共项后的相位-多普勒差分门限(m)。
    private const val CODE_JUMP_METERS = 30.0 // 去系统公共项后的码-多普勒跳变门限(m)。
    private const val MW_JUMP_METERS = 5.0 // 双频 MW 组合相邻历元跳变门限(m)。
    private const val MIN_COMMON_MODE_SIGNALS = 3 // 同系统至少 3 条创新量才求公共中位数。
    private const val NEAR_ZERO = 0.0001 // 按当前观测数值单位判断近零/空值；不是统一的“0.1 mm”。

    // Clock segmentation and integer/fractional time arithmetic are in ObservationClock.

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
        // Deprecated compatibility field; canonical generation does not apply this old correction.
        var receiverClockCorrectionNanos: Long = 0L // deprecated legacy field; never added to the fixed reference
        var reference: ObservationClock.Reference? = null
        var hasFullBias: Boolean = false
        var hasSvTime: Boolean = false
        var leapSeconds: Int? = null
        var sourceRow: Long = 0L
    }

    /** 一颗卫星在一个历元的多信号观测；数组下标由 signalMap 决定，不是固定 F1/F2/F3 槽。 */
    class RnxSat(val sys: Int, val prn: Int, val signalCount: Int = MAX_FRQ) {
        val p = DoubleArray(signalCount) // RINEX C 码观测，单位 m；0 在本实现中表示缺失。
        val l = DoubleArray(signalCount) // RINEX L 相位，单位 cycle；ADR/λ，不从旧 CarrierPhase 得到。
        val d = DoubleArray(signalCount) // RINEX D 多普勒，单位 Hz；-PRR/λ。
        val s = DoubleArray(signalCount) // RINEX S 载噪比，单位 dB-Hz，不是 SnrInDb。
        val lli = IntArray(signalCount) // 相位失锁标志位，非相位质量评分。
        val frequencyHz = DoubleArray(signalCount) // 名义载频(Hz)，用于波长和双频组合。
        val hasL=BooleanArray(signalCount)
        val hasD=BooleanArray(signalCount)
        val hasS=BooleanArray(signalCount)
        // Explicit presence preserves valid zero Doppler/phase and D/S-only rows.
        fun isEmpty(): Boolean = p.all { it == 0.0 } && !hasL.any { it } && !hasD.any { it } && !hasS.any { it } && lli.all { it==0 }
    }

    class RnxEpoch {
        var time = DoubleArray(6) // GPST 日历表示：[年,月,日,时,分,秒(可含小数)]。
        var receiverTimeNanos: Long = 0L // 固定钟段基准的接收机时间(ns)，供相邻历元 dt 使用。
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
    data class ObservationAudit(val sourceRow: Long,val timeNanos: Long,val rawFullBias: Long,val rawBias: Double,
        val referenceFullBias: Long?,val referenceBias: Double?,val segment: Int?,val rxNs: Long?,val rxFractionNs: Double?,
        val system: Int,val svid: Int,val signal: String,val frequencyHz: Double?,val P: Double?,val L: Double?,val D: Double?,val lli: Int,val status: String)
    private var auditSink: ((ObservationAudit)->Unit)?=null
    var lastError: String?=null
        private set
    private fun audit(obs: GnssSat,rx: ObservationClock.NanoTime?,frequency: Double?,sat: RnxSat?,slot: Int,status: String) {
        auditSink?.invoke(ObservationAudit(obs.sourceRow,obs.timeNanos,obs.fullBiasNanos,obs.biasNanos,
            obs.reference?.fullBias,obs.reference?.bias,obs.reference?.segment,rx?.ns,rx?.fractionNs,
            obs.sys,obs.svid,obs.signalName,frequency,sat?.p?.get(slot)?.takeIf { it!=0.0 },
            sat?.let { if(it.hasL[slot])it.l[slot]else null },sat?.let {if(it.hasD[slot])it.d[slot]else null},sat?.lli?.get(slot)?:0,status))
    }
    @Synchronized
    fun convert(inputFile: File, outputFile: File, applyLegacyQc: Boolean = false, audit: ((ObservationAudit)->Unit)? = null): Boolean {
        lastError=null;auditSink=audit
        try {
        signalMap.forEach { it.clear() }
        glonassSlots.clear()
        lastStats = ConversionStats()
        val rawEpochs = mutableListOf<GnssSat>()

        Log.d(TAG, "开始读取原始数据: ${inputFile.name}")
        try {
            BufferedReader(FileReader(inputFile)).use { reader ->
                var line: String?
                var sourceRow=0L
                while (reader.readLine().also { line = it } != null) {
                    sourceRow++
                    if (line!!.startsWith("Raw")) {
                        val sat = parseLine(line!!)
                        if (sat != null) {
                            sat.sourceRow=sourceRow
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
        if(applyLegacyQc) {
            require(signalMap.all { it.size<=MAX_FRQ }) { "Legacy QC supports at most $MAX_FRQ signals" }
            preprocessRinexEpochs(rinexEpochs)
            for(epoch in rinexEpochs)for(sat in epoch.sats)for(k in sat.l.indices)
                if(sat.hasL[k] && sat.l[k]==0.0)sat.hasL[k]=false
        }
        val success = writeRinexFile(outputFile, rinexEpochs)
        Log.i(TAG, "转换统计: $lastStats")
        return success
    
        } catch(error: Exception) {
            lastError=error.message?:error.javaClass.simpleName
            Log.e(TAG,"Observation generation failed",error)
            return false
        } finally {auditSink=null}
    }

    /** Preserve callback order and actual TimeOffset; RINEX rounds only to its 100 ns resolution. */
    private fun processEpochs(rawList: List<GnssSat>): List<RnxEpoch> {
        val result=mutableListOf<RnxEpoch>()
        val clock=ObservationClock()
        val previousAdr=mutableMapOf<SignalKey,Int>()
        val previousFrequency=mutableMapOf<SignalKey,Double>()
        val previousSegment=mutableMapOf<SignalKey,Int>()
        val pending=mutableSetOf<SignalKey>()
        val forwarded=mutableMapOf<SignalKey,Int>()
        fun markPending(key: SignalKey){pending.add(key);forwarded[key]=0}
        var index=0
        while(index<rawList.size) {
            val first=rawList[index]
            var end=index+1
            while(end<rawList.size && rawList[end].timeNanos==first.timeNanos &&
                rawList[end].hardwareClockDiscontinuityCount==first.hardwareClockDiscontinuityCount)end++
            // Use first VALID Clock in this callback, not necessarily its first row.
            val validClock=(index until end).map { rawList[it] }.firstOrNull {
                it.hasFullBias && ObservationClock.split(it.timeNanos,it.fullBiasNanos,it.biasNanos,0.0)!=null
            } ?: first
            val ref=clock.observe(validClock.timeNanos,validClock.fullBiasNanos.takeIf { validClock.hasFullBias },
                validClock.biasNanos,validClock.hardwareClockDiscontinuityCount)
            // Exact-identity duplicates: use one whole row, with the generic Native ranking and first-on-tie.
            fun identity(o: GnssSat)=listOf(o.sys,o.svid,o.signalName,o.timeOffsetNanos)
            fun score(o: GnssSat): Int {
                var s=if(o.state and STATE_CODE_LOCK!=0 && o.state and (STATE_TOW_DECODED or STATE_TOW_KNOWN)!=0 &&
                    o.receivedSvTimeNanos>0 && o.receivedSvTimeUncertaintyNanos in 0L..MAX_TOW_UNC_NS)100 else 0
                if(o.adrState and 1!=0 && o.adrState and (GPS_ADR_STATE_RESET or GPS_ADR_STATE_CYCLE_SLIP)==0 &&
                    o.adrMeters.isFinite() && o.adrMeters!=0.0)s+=40
                if(o.pseudorangeRateMps.isFinite())s+=10
                return s
            }
            val winners=mutableMapOf<List<Any>,Int>()
            for(i in index until end)if(isTrackingUsable(rawList[i])) {
                val id=identity(rawList[i]);val previous=winners[id]
                if(previous==null || score(rawList[i])>score(rawList[previous]))winners[id]=i
            }
            val groups=sortedMapOf<Long,RnxEpoch>()
            for(i in index until end) {
                val obs=rawList[i]
                if(!isTrackingUsable(obs)) { lastStats.invalidTracking++;audit(obs,null,null,null,0,"UNSUPPORTED_SIGNAL");continue }
                if(winners[identity(obs)]!=i){audit(obs,null,null,null,0,"DUPLICATE_NOT_SELECTED");continue}
                val freqIdx=getSignalIndex(obs.sys,obs.signalName)
                if(freqIdx !in signalMap[getSysIndex(obs.sys)].indices)continue
                val key=SignalKey(obs.sys,obs.svid,freqIdx)
                if(obs.adrState and (GPS_ADR_STATE_RESET or GPS_ADR_STATE_CYCLE_SLIP)!=0)markPending(key)
                if(ref==null){audit(obs,null,null,null,0,"INVALID_CLOCK");continue}
                obs.reference=ref
                val actual=receiverTime(obs)
                if(actual==null){audit(obs,null,null,null,0,"INVALID_TIME_OFFSET");continue}
                val tick=actual.rinexTick()
                val epoch=groups.getOrPut(tick) { RnxEpoch().apply {
                    time=calendarTime(tick);receiverTimeNanos=actual.ns;clockDiscontinuity=ref.reset
                } }
                val freq=getNominalFrequency(obs.sys,obs.carrierFrequencyHz,obs.svid)
                if(freq<=0) {lastStats.unsupportedSignals++;continue}
                val sat=epoch.sats.find { it.sys==obs.sys && it.prn==obs.svid }?:
                    RnxSat(obs.sys,obs.svid,signalMap[getSysIndex(obs.sys)].size).also {epoch.sats.add(it)}
                // A row is kept as a whole: never mix P from one duplicate with L from another.
                sat.p[freqIdx]=0.0;sat.l[freqIdx]=0.0;sat.d[freqIdx]=0.0;sat.s[freqIdx]=0.0
                sat.hasL[freqIdx]=false;sat.hasD[freqIdx]=false;sat.hasS[freqIdx]=false;sat.lli[freqIdx]=0
                val lambda=CLIGHT/freq;sat.frequencyHz[freqIdx]=freq
                if(isCodeValid(obs)) {
                    val travel=calculatePrecisePseudorangeSeconds(obs)
                    if(travel in MIN_TRAVEL_TIME_SECONDS..MAX_TRAVEL_TIME_SECONDS) {
                        sat.p[freqIdx]=travel*CLIGHT;lastStats.codeAccepted++
                    }
                }
                if(isDopplerValid(obs)) {sat.d[freqIdx]=-obs.pseudorangeRateMps/lambda;sat.hasD[freqIdx]=true;lastStats.dopplerAccepted++}
                val prev=previousAdr[key]
                val half=(obs.adrState and GPS_ADR_STATE_HALF_CYCLE_REPORTED)!=0 &&
                    (obs.adrState and GPS_ADR_STATE_HALF_CYCLE_RESOLVED)==0
                val oldHalf=prev?.let { it and GPS_ADR_STATE_HALF_CYCLE_REPORTED!=0 && it and GPS_ADR_STATE_HALF_CYCLE_RESOLVED==0 }
                if(prev!=null && (((prev and 1) != (obs.adrState and 1)) || oldHalf!=half))markPending(key)
                if(previousFrequency[key]?.let { it!=freq }==true || previousSegment[key]?.let { it!=ref.segment }==true)markPending(key)
                if(isPhaseValid(obs)) {
                    sat.l[freqIdx]=obs.adrMeters/lambda;sat.hasL[freqIdx]=true;lastStats.phaseAccepted++
                    if(half)sat.lli[freqIdx]=sat.lli[freqIdx] or 2
                    previousFrequency[key]=freq;previousSegment[key]=ref.segment
                }
                if(ref.reset)markPending(key)
                if(key in pending) {
                    val stage=if(!sat.hasL[freqIdx])1 else if(sat.p[freqIdx]==0.0)2 else 3
                    if(stage>(forwarded[key]?:0)){sat.lli[freqIdx]=sat.lli[freqIdx] or LLI_SLIP;forwarded[key]=stage}
                    if(stage==3){pending.remove(key);forwarded.remove(key)}
                }
                if(obs.cn0DbHz.isFinite() && obs.cn0DbHz>=0){sat.s[freqIdx]=obs.cn0DbHz;sat.hasS[freqIdx]=true}
                previousAdr[key]=obs.adrState
                val reasons=mutableListOf<String>()
                if(sat.p[freqIdx]==0.0)reasons.add(if(isCodeValid(obs))"CODE_TIME_OUT_OF_RANGE" else "CODE_FLAGS_INVALID")
                if(!sat.hasL[freqIdx])reasons.add("PHASE_FLAGS_OR_UNCERTAINTY_INVALID")
                if(!sat.hasD[freqIdx])reasons.add("DOPPLER_INVALID")
                audit(obs,actual,freq,sat,freqIdx,if(reasons.isEmpty())"VALID"else reasons.joinToString(";"))
            }
            for(epoch in groups.values)if(epoch.sats.any { !it.isEmpty() }) {finishEpoch(epoch);result.add(epoch)}
            index=end
        }
        return result
    }

    // 已确认硬件钟段变化影响整个历元，给当历元所有非空相位置失锁位，防止跨钟跳沿用模糊度。
    private fun finishEpoch(epoch: RnxEpoch) {
        if (!epoch.clockDiscontinuity) return
        lastStats.clockDiscontinuityEpochs++
        epoch.sats.forEach { sat ->
            for (i in sat.l.indices) {
                if (sat.hasL[i]) sat.lli[i] = sat.lli[i] or LLI_SLIP
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
                for (k in sat.l.indices) {
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
                for (k in 1 until sat.l.size) {
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

            for (sat in epoch.sats) for (k in sat.l.indices) {
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
     * 信号接收时刻加入 TimeOffsetNanos；整数与亚纳秒分离，均保留参与时差计算。
     * GPS/GAL/QZS 用周内时，BDT=GPST-14 s，GLO 用 UTC+3 h 日内时，随后修正跨周/跨日。
     */
    private fun calculatePrecisePseudorangeSeconds(obs: GnssSat): Double {
        val rx=receiverTime(obs)?:return Double.NaN
        return ObservationClock.travelNs(rx,obs.sys,obs.receivedSvTimeNanos,obs.leapSeconds)*1e-9
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
                writer.write(pad60("OBSGEN FIXED_CLOCK_2023; GPST; NO RESAMPLING")+"COMMENT\n")
                writer.write(pad60("     0")+"RCV CLOCK OFFS APPL\n")
                writer.write(pad60("") + "END OF HEADER\n")

                // “>” 行记录 GPST 年月日时分秒、事件标志及有效卫星数，不使用 TXT 第 1 列。
                for (epoch in epochs) {
                    val validSats = epoch.sats.filter { !it.isEmpty() }
                    if (validSats.isEmpty()) continue
                    val t = epoch.time
                    writer.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  %1d %2d\n",
                        t[0].toInt(), t[1].toInt(), t[2].toInt(), t[3].toInt(), t[4].toInt(), t[5], if(epoch.clockDiscontinuity)1 else 0, validSats.size))

                    val sortedSats = validSats.sortedWith(compareBy({ getSysPriority(it.sys) }, { it.prn }))
                    for (sat in sortedSats) {
                        writer.write(String.format(Locale.US, "%c%02d", getSysChar(sat.sys), sat.prn))
                        val sysIdx = getSysIndex(sat.sys)
                        for (k in signalMap[sysIdx].indices) {
                            writeObsValue(writer, sat.p[k])
                            if (sat.hasL[k]) {
                                // 每个观测域宽 16 字符：14 位数值+1 位 LLI+1 位 SSI；L 数值保留 0.001 cycle。
                                val lliStr = if (sat.lli[k] != 0) sat.lli[k].toString() else " "
                                writer.write(String.format(Locale.US, "%14.3f%s ", sat.l[k], lliStr))
                            } else writer.write("              "+(if(sat.lli[k]!=0)sat.lli[k].toString()else " ")+" ")
                            writeObsValue(writer, sat.d[k], sat.hasD[k])
                            writeObsValue(writer, sat.s[k], sat.hasS[k])
                        }
                        writer.write("\n")
                    }
                }
            }
            return true
        } catch (e: Exception) { return false }
    }

    private fun receiverTime(obs: GnssSat): ObservationClock.NanoTime? =
        (obs.reference?:ObservationClock.Reference(obs.fullBiasNanos,obs.biasNanos,0,false)).receive(obs.timeNanos,obs.timeOffsetNanos)

    private fun correctedReceiverTimeNanos(obs: GnssSat): Long = receiverTime(obs)?.ns?:0L
    private fun getRinexTime(obs: GnssSat): DoubleArray = calendarTime(requireNotNull(receiverTime(obs)).rinexTick())
    private fun calendarTime(gpsTimeNanos: Long): DoubleArray {
        val seconds=Math.floorDiv(gpsTimeNanos,1000000000L)
        val nanos=Math.floorMod(gpsTimeNanos,1000000000L)
        val cal=Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis=315964800000L+seconds*1000L
        return doubleArrayOf(cal.get(Calendar.YEAR).toDouble(),(cal.get(Calendar.MONTH)+1).toDouble(),
            cal.get(Calendar.DAY_OF_MONTH).toDouble(),cal.get(Calendar.HOUR_OF_DAY).toDouble(),
            cal.get(Calendar.MINUTE).toDouble(),cal.get(Calendar.SECOND)+nanos*1e-9)
    }

    /**
     * 按固定 CSV 下标取值，最少需要 29 列；CodeType 在 35 列，旧文件缺此列时无法完整识别信号。
     * safeXXX 将空/错误数字回退成 0，因而会丢失部分“缺失”和“真实零”的区别。
     * 缺失 FullBias 只可沿用已建立的同钟段基准；不读取 utcTimeMillis、旧 CarrierPhase 和 31～34 信号间偏差列。
     */
    private fun parseLine(line: String): GnssSat? {
        val tokens = line.split(",")
        if (tokens.size < 29) return null
        return try {
            val sat = GnssSat()
            sat.timeNanos = tokens[2].trim().toLongOrNull()?:return null
            sat.fullBiasNanos = safeLong(tokens, 5)
            sat.hasFullBias=tokens.getOrNull(5)?.trim()?.toLongOrNull()!=null
            sat.leapSeconds=tokens.getOrNull(3)?.trim()?.toIntOrNull()
            sat.biasNanos = tokens.getOrNull(6)?.trim().let { if(it.isNullOrEmpty())0.0 else it.toDoubleOrNull()?:Double.NaN }
            sat.hardwareClockDiscontinuityCount = safeInt(tokens, 10)
            sat.svid = safeInt(tokens, 11)
            sat.timeOffsetNanos = tokens.getOrNull(12)?.trim().let { if(it.isNullOrEmpty())0.0 else it.toDoubleOrNull()?:Double.NaN }
            sat.state = safeInt(tokens, 13)
            sat.receivedSvTimeNanos = safeLong(tokens, 14)
            sat.hasSvTime=tokens.getOrNull(14)?.trim()?.toLongOrNull()!=null
            sat.receivedSvTimeUncertaintyNanos = tokens.getOrNull(15)?.trim()?.toLongOrNull()?:-1L
            sat.cn0DbHz = tokens.getOrNull(16)?.trim()?.toDoubleOrNull()?:Double.NaN
            sat.pseudorangeRateMps = tokens.getOrNull(17)?.trim()?.toDoubleOrNull()?:Double.NaN
            sat.pseudorangeRateUncertaintyMps = tokens.getOrNull(18)?.trim()?.toDoubleOrNull()?:Double.NaN
            sat.adrState = safeInt(tokens, 19)
            sat.adrMeters = tokens.getOrNull(20)?.trim()?.toDoubleOrNull()?:Double.NaN
            sat.adrUncertaintyMeters = tokens.getOrNull(21)?.trim()?.toDoubleOrNull()?:Double.NaN
            sat.carrierFrequencyHz = tokens.getOrNull(22)?.trim()?.toDoubleOrNull()?:Double.NaN
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
            if(!freq.isFinite() || freq<=0.0)continue
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
                    obs.sys=SYS_GLO
                    val base=if(freq>1.59e9)1602e6 else 1246e6
                    val spacing=if(freq>1.59e9).5625e6 else .4375e6
                    val k=Math.round((freq-base)/spacing).toInt()
                    if(obs.svid !in 1..27 || k !in -7..6 || !isNear(freq,base+k*spacing) || code !in setOf("C","P"))continue
                    require(glonassSlots[obs.svid]?.let { it==k }!=false) { "GLONASS channel changed within file; a header update is required" }
                    glonassSlots[obs.svid]=k
                    addSignal(SYS_GLO,(if(freq>1.59e9)"1" else "2")+code,obs)
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
    private fun writeObsValue(writer: BufferedWriter, value: Double, present: Boolean = abs(value)>NEAR_ZERO) {
        if (present && value.isFinite()) writer.write(String.format(Locale.US, "%14.3f  ", value))
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
            obs.sys in setOf(SYS_GPS,SYS_GLO,SYS_GAL,SYS_BDS,SYS_QZS)

    // P 依赖明确的卫星时标及码锁；GLO 是 STRING_SYNC+TOD，不能套普通 TOW 条件。
    private fun isCodeValid(obs: GnssSat): Boolean {
        if (!obs.hasSvTime || obs.receivedSvTimeNanos < 0L ||
            obs.receivedSvTimeNanos >= (if(obs.sys==SYS_GLO)ObservationClock.DAY_NS else ObservationClock.WEEK_NS) ||
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

    // VALID, finite, no RESET/SLIP; ADR uncertainty 0..1.0 m, matching Native.
    private fun isPhaseValid(obs: GnssSat): Boolean =
        (obs.adrState and 1) != 0 && obs.adrMeters.isFinite() &&
            (obs.adrState and (GPS_ADR_STATE_RESET or GPS_ADR_STATE_CYCLE_SLIP)) == 0 &&
            obs.adrUncertaintyMeters.isFinite() &&
            obs.adrUncertaintyMeters in 0.0..MAX_ADR_UNC_METERS
    // 10 kHz 是信号识别容差，不代表载频精度或 PPP 测距精度。
    private fun isNear(val1: Double, val2: Double): Boolean = abs(val1 - val2) < 10000.0
}
