package com.example.ftpget // ★ 注意：保持你真实的包名 ★

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * 原始观测记录器：Android GNSS 回调 → 54 列 CSV 文本，不在采集阶段计算 PPP。
 *
 * 一个 event 包含一个公共 GnssClock 和多条 GnssMeasurement；每条 measurement 是
 * “一颗卫星的一个信号”，不是一颗卫星的全部频点。因此同一历元可能有几十行 Raw。
 * 本类原样保存 State、ADR、频率等字段，不按 C/N0 或 ADR 门限删除原始观测，
 * 以便以后重做转换和对照实验。字段可用性由 hasXXX() 判断，缺失值不能伪造为 0。
 *
 * @param context 用于取得 LocationManager 和应用专属外部文件目录；不是观测数据。
 */
class GnssDataLogger(private val context: Context) {

    private val tag = "GnssLogger_ppp"
    private var locationManager: LocationManager? = null
    // 回调注册成功后才为 true；它表示记录会话正在运行，不表示已有有效卫星数据。
    var isRecording = false
        private set

    // 当前实例最近建立的 TXT 文件；重启应用后这个变量不会自动恢复历史文件路径。
    var currentSaveFile: File? = null
        private set

    private var fileWriter: BufferedWriter? = null

    // IO 协程只负责磁盘写入；SupervisorJob 避免子任务异常直接取消整个作用域。
    // Channel 容量是 10000 条“measurement 行”，不是 10000 个历元。
    // trySend 不等待磁盘：队列满/关闭时会丢行并计数，不能把有界队列理解成绝不丢数据。
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null
    private var dataChannel = Channel<String>(10000)
    private val droppedMeasurements = AtomicLong(0) // 跨回调/界面读取的线程安全丢行计数。

    val droppedMeasurementCount: Long
        get() = droppedMeasurements.get()

    // 请求 GPS 定位更新以维持定位工作；这里不保存 Location 坐标，也不将其用作 PPP 真值。
    // 空监听器不是系统 WakeLock，不能保证所有机型在锁屏/后台时持续提供原始数据。
    private val dummyLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            if (!isRecording) return

            // 同一回调的所有信号共用这份硬件时钟，不能为每行重新获取手机系统时间代替它。
            val clock = eventArgs.clock

            for (measurement in eventArgs.measurements) {
                val csvLine = formatMeasurementToCsv(clock, measurement)
                if (dataChannel.trySend(csvLine).isFailure) {
                    droppedMeasurements.incrementAndGet()
                }
            }
        }
    }

    /**
     * 建文件/写表头 → 启动写盘协程 → 请求定位及原始观测回调。
     * 定位权限须由 Activity 提前申请；SuppressLint 只关闭静态提示，不授予权限。
     * 返回 true 仅表示注册成功，不保证 ADR 有效、每秒都有事件或所有频率都可用。
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return false

        try {
            // 通常位于 /storage/emulated/0/Android/data/com.example.ftpget/files/GNSS_Products。
            // 这是应用专属目录，卸载可能清除；产品与采集 TXT 当前放在同一个目录中。
            val dir = context.getExternalFilesDir("GNSS_Products")
            if (dir != null && !dir.exists()) dir.mkdirs()

            // 文件名使用手机本地时区的系统时间，仅便于选择文件；GNSS 历元以 Clock 为准。
            val timeString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentSaveFile = File(dir, "RawData_$timeString.txt")
            fileWriter = BufferedWriter(FileWriter(currentSaveFile))

            // 固定的 54 列“本项目格式”，下标从 0 开始。RinexConverter.parseLine 按列号读，
            // 所以缺失字段必须留空，不能删列或改变顺序；不等于所有 GnssLogger 版本均用此表头。
            val header =
                "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos," +
                        "BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond," +
                        "HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos," +
                        "ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond," +
                        "PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
                        "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters," +
                        "CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty," +
                        "MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz," +
                        "FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos," +
                        "SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos," +
                        "CodeType,ChipsetElapsedRealtimeNanos,IsFullTracking,SvPositionEcefXMeters," +
                        "SvPositionEcefYMeters,SvPositionEcefZMeters,SvVelocityEcefXMetersPerSecond," +
                        "SvVelocityEcefYMetersPerSecond,SvVelocityEcefZMetersPerSecond,SvClockBiasMeters," +
                        "SvClockDriftMetersPerSecond,KlobucharAlpha0,KlobucharAlpha1,KlobucharAlpha2," +
                        "KlobucharAlpha3,KlobucharBeta0,KlobucharBeta1,KlobucharBeta2,KlobucharBeta3"

            // 写入 GnssLogger 标准文件头
            fileWriter?.write("# \n")
            fileWriter?.write("# Header Description:\n")
            fileWriter?.write("# \n")
            fileWriter?.write("# Version: GeoLog Kt v3.0 Platform: ${Build.VERSION.RELEASE} Manufacturer: ${Build.MANUFACTURER} Model: ${Build.MODEL}\n")
            fileWriter?.write("# \n")
            fileWriter?.write("# $header\n")
            fileWriter?.write("# \n")

            droppedMeasurements.set(0)

            // 重置 Channel
            if (dataChannel.isClosedForSend) {
                dataChannel = Channel(10000)
            }

            // 唯一写盘消费者；关闭 Channel 后仍先读完缓存，再 flush/close。
            writeJob = coroutineScope.launch {
                for (line in dataChannel) {
                    fileWriter?.write(line)
                    fileWriter?.write("\n")
                }
                fileWriter?.flush()
                fileWriter?.close()
                Log.d(tag, "文件 I/O 流已安全关闭")
            }

            // 参数依次是 provider、最小更新间隔(ms)、最小位移(m)、监听器。
            // 1000/0 表示申请约 1 秒定位且不按位移限流；不是强制 GNSS Raw 回调频率。
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0.0f,
                dummyLocationListener
            )

            // Android 12+ 请求 full tracking 以减少占空比对连续性的影响，是否实现取决于设备。
            // 它不是“强制全频段”，不能保证无周跳；旧系统使用普通注册接口。
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = android.location.GnssMeasurementRequest.Builder()
                    .setFullTracking(true)
                    .build()
                locationManager?.registerGnssMeasurementsCallback(
                    request,
                    { it.run() },
                    measurementCallback
                ) ?: false
            } else {
                locationManager?.registerGnssMeasurementsCallback(measurementCallback, null)
                    ?: false
            }

            if (success) {
                isRecording = true
                Log.d(tag, "全频段满血采集已启动...")
            } else {
                dataChannel.trySend("# RecordingStartFailed")
                dataChannel.close()
                locationManager?.removeUpdates(dummyLocationListener)
                Log.e(tag, "GNSS原始观测回调注册失败")
            }
            return success
        } catch (e: Exception) {
            Log.e(tag, "启动异常", e)
            return false
        }
    }

    /**
     * 先注销数据源，再关闭队列；join 等待 IO 协程把已经入队的行写完。
     * 本函数是挂起函数，调用方不能在主线程用阻塞等待代替它。
     * 文件尾 DroppedMeasurements 只统计 Raw 行入队失败，不检测芯片未上报的历元。
     */
    suspend fun stopRecording(): File? {
        if (!isRecording) return null

        locationManager?.unregisterGnssMeasurementsCallback(measurementCallback)
        locationManager?.removeUpdates(dummyLocationListener)
        isRecording = false

        dataChannel.trySend("# DroppedMeasurements,${droppedMeasurements.get()}")
        dataChannel.close()
        writeJob?.join()
        return currentSaveFile
    }

    /**
     * 将“一条信号 measurement + 公共 clock”序列化为一行；并不计算伪距 P 或相位周数 L。
     * 时间(ns)、距离(m)、频率(Hz)保持 Android 原单位，转换器之后再生成 P/L/D/S。
     * 54 列完整字典见 docs/ANDROID_GNSS_DATA_GUIDE.md；空字符串表示未提供。
     */
    private fun formatMeasurementToCsv(clock: GnssClock, measurement: GnssMeasurement): String {
        val values = Array<Any>(54) { "" }
        // 时间关系：GPST 自 1980-01-06 起算 ≈ TimeNanos - FullBiasNanos - BiasNanos。
        // 注意历史字段 utcTimeMillis 在下面没有加 GPS→Unix 起点差 315964800000 ms，
        // 因而不是 Unix UTC 毫秒！实际转换不读此列，而读 2/5/6 列；本次注释不修改文件格式。
        var utcTimeMillisStr = ""
        val towDecoded = (measurement.state and GnssMeasurement.STATE_TOW_DECODED) != 0 ||
            (measurement.state and 16384) != 0 // STATE_TOW_KNOWN
        if (towDecoded && clock.hasFullBiasNanos() && clock.hasLeapSecond()) {
            val bias = if (clock.hasBiasNanos()) clock.biasNanos.toLong() else 0L
            val gpsTimeNanos = clock.timeNanos - clock.fullBiasNanos - bias
            val utcTimeNanos = gpsTimeNanos - (clock.leapSecond * 1_000_000_000L)
            utcTimeMillisStr = (utcTimeNanos / 1_000_000L).toString()
        }

        // 2. 填充 Clock 信息 (★ 直接写入原始值)
        values[0] = "Raw" // 记录类型标识；以 # 开头的行是元数据，不是测量。
        values[1] = utcTimeMillisStr // 历史辅助时间列，含义限制见上方；TOW/闰秒缺失则为空。
        values[2] = clock.timeNanos // 硬件接收机时钟读数(ns)，不是 UTC/手机开机时间。
        if (clock.hasLeapSecond()) values[3] = clock.leapSecond // GPST - UTC 的整秒差。
        if (clock.hasTimeUncertaintyNanos()) values[4] = clock.timeUncertaintyNanos // 时钟读数 1σ(ns)。

        if (clock.hasFullBiasNanos()) values[5] = clock.fullBiasNanos // 硬件时间相对 GPST 的大整数偏移(ns)。
        if (clock.hasBiasNanos()) values[6] = clock.biasNanos // FullBias 之外的精细偏移(ns，可有小数)。

        if (clock.hasBiasUncertaintyNanos()) values[7] = clock.biasUncertaintyNanos // Bias 的 1σ(ns)。
        if (clock.hasDriftNanosPerSecond()) values[8] = clock.driftNanosPerSecond // 钟偏变化率(ns/s)。
        // 第 9 列是钟漂变化率的 1σ(ns/s)，不是伪距率不确定度。
        if (clock.hasDriftUncertaintyNanosPerSecond()) values[9] =
            clock.driftUncertaintyNanosPerSecond
        values[10] = clock.hardwareClockDiscontinuityCount // 计数改变说明硬件时钟不连续，须重判载波弧段。

        // 3. 填充 Measurement 核心信息
        values[11] = measurement.svid // 星座内卫星号；须与第 28 列一起识别卫星，不能当 RTKLIB sat。
        values[12] = measurement.timeOffsetNanos // 此信号测量时刻相对公共 Clock 的偏移(ns)。
        values[13] = measurement.state // 跟踪/码锁/TOW 等位掩码，多个状态按位 OR 同时存在。
        values[14] = measurement.receivedSvTimeNanos // 所接收信号的卫星发射时标(ns)，依星座解释周内/日内。
        values[15] = measurement.receivedSvTimeUncertaintyNanos // 上述发射时刻的 1σ(ns)。
        values[16] = measurement.cn0DbHz // 天线端 C/N0(dB-Hz)，用于 RINEX S / RTKLIB SNR。
        values[17] = measurement.pseudorangeRateMetersPerSecond // 伪距率(m/s)，满足转换后 -λD≈此值。
        values[18] = measurement.pseudorangeRateUncertaintyMetersPerSecond // 伪距率的 1σ(m/s)。
        values[19] = measurement.accumulatedDeltaRangeState // ADR 位掩码，与第 13 列不是同一种状态。
        values[20] = measurement.accumulatedDeltaRangeMeters // 累计距离 ADR(m)；L=ADR/λ，不是伪距 P。
        values[21] = measurement.accumulatedDeltaRangeUncertaintyMeters // ADR 的 1σ(m)，不是 cycle。

        if (measurement.hasCarrierFrequencyHz()) values[22] = measurement.carrierFrequencyHz // Hz；λ=c/f。

        // 23～25 是已弃用的旧载波接口；留作原始记录，当前 L 由第 20 列 ADR 生成。
        if (measurement.hasCarrierCycles()) values[23] = measurement.carrierCycles // 完整载波周数。
        if (measurement.hasCarrierPhase()) values[24] = measurement.carrierPhase // 小数相位(cycle)。
        // 第 25 列为旧 CarrierPhase 的不确定度(cycle)。
        if (measurement.hasCarrierPhaseUncertainty()) values[25] =
            measurement.carrierPhaseUncertainty

        values[26] = measurement.multipathIndicator // 0未知/1检测到/2未检测到；不是多路径误差米数。
        if (measurement.hasSnrInDb()) values[27] = measurement.snrInDb // 信噪比(dB)，不能与 C/N0(dB-Hz)混用。
        values[28] = measurement.constellationType // Android 编号：1 GPS/3 GLO/4 QZS/5 BDS/6 GAL。

        // 29 AGC(dB)：接收机自动增益控制量，不是观测权；30 BasebandCn0 是基带 C/N0(dB-Hz)。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && measurement.hasAutomaticGainControlLevelDb()) {
            values[29] = measurement.automaticGainControlLevelDb
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasBasebandCn0DbHz()) {
            values[30] = measurement.basebandCn0DbHz
        }

        // 31～34：Android 信号间偏差及其 1σ(ns)，不是外部 BIA/OSB 产品，也不是 PPP 的 ISB 状态。
        // 31 FullInterSignalBias 包括接收机与卫星贡献；33 SatelliteInterSignalBias 是卫星部分。
        // 缺失时本格式写 -1 哨兵，但偏差本身可以为负，脱离 hasXXX 信息不能把所有负值视为缺失。
        // 当前 Kotlin RINEX 转换和 JNI 输入并未将这四列直接用于 P/L 改正。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasNanos()) {
            values[31] = measurement.fullInterSignalBiasNanos
        } else {
            values[31] = "-1"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasUncertaintyNanos()) {
            values[32] = measurement.fullInterSignalBiasUncertaintyNanos
        } else {
            values[32] = "-1"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasNanos()) {
            values[33] = measurement.satelliteInterSignalBiasNanos
        } else {
            values[33] = "-1"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasUncertaintyNanos()) {
            values[34] = measurement.satelliteInterSignalBiasUncertaintyNanos
        } else {
            values[34] = "-1"
        }
        // 35 CodeType 是跟踪属性(如 C/Q/I/P)，不是频率；需与 Hz、星座组合成 1C/5Q/2I 等。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && measurement.hasCodeType()) {
            values[35] = measurement.codeType
        }

        // 36 为开机后单调时间(ns)，用于关联事件/延时分析；不是 GPST，不能计算卫星伪距。
        // 优先用 Clock 对应的 elapsedRealtime；回退值是当前序列化时刻，二者并非完全同义。
        values[36] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            clock.hasElapsedRealtimeNanos()) {
            clock.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
        // 37 IsFullTracking 未采集可靠状态，保持空；请求 setFullTracking(true) 不等于实测状态。
        values[37] = ""

        // 38～40 卫星 ECEF 位置(m)，41～43 速度(m/s)，44 钟差(m)，45 钟漂(m/s)，
        // 46～49 Klobuchar α 系数、50～53 β 系数均为预留列，当前全部空白，并未计算/下载填入。
        // α/β 的各阶系数单位不同，不能把它们都解释成米；详见说明文档。
        return values.joinToString(separator = ",")
    }
}
