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

class GnssDataLogger(private val context: Context) {

    private var locationManager: LocationManager? = null
    var isRecording = false
        private set

    var currentSaveFile: File? = null
        private set

    private var fileWriter: BufferedWriter? = null

    // 异步无阻塞 I/O 队列
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writeJob: Job? = null
    private val dataChannel = Channel<String>(Channel.UNLIMITED)

    // 🌟 核心修复 1：虚拟定位监听器，用于死死拖住 GPS 芯片，不让它休眠
    private val dummyLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {}
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    init {
        locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    private val measurementCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
            if (!isRecording) return

            val clock = eventArgs.clock
            val timestamp = System.currentTimeMillis()

            for (measurement in eventArgs.measurements) {
                if (measurement.receivedSvTimeUncertaintyNanos > 500.0) continue
                if ((measurement.state and GnssMeasurement.STATE_CODE_LOCK) == 0) continue

                val csvLine = formatMeasurementToCsv(clock, measurement, timestamp)
                dataChannel.trySend(csvLine)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (isRecording) return false

        try {
            val dir = context.getExternalFilesDir("GNSS_Products")
            if (dir != null && !dir.exists()) dir.mkdirs()

            val timeString = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            currentSaveFile = File(dir, "RawData_$timeString.txt")
            fileWriter = BufferedWriter(FileWriter(currentSaveFile))

            val header = "Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos,IsFullTracking"
            fileWriter?.write("# $header\n")

            writeJob = coroutineScope.launch {
                for (line in dataChannel) {
                    fileWriter?.write(line)
                    fileWriter?.write("\n")
                }
            }

            // 🌟 核心修复 2：强行请求 1000ms (1Hz) 的高频定位，剥夺系统休眠权限
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,
                0.0f,
                dummyLocationListener
            )

            // 🌟 核心修复 3：调用 Android 12+ API 彻底关闭硬件占空比 (Duty Cycle)
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = android.location.GnssMeasurementRequest.Builder()
                    .setFullTracking(true) // 🔥 最关键的一句！强制全频段持续跟踪！
                    .build()
                locationManager?.registerGnssMeasurementsCallback(request, { it.run() }, measurementCallback) ?: false
            } else {
                locationManager?.registerGnssMeasurementsCallback(measurementCallback, null) ?: false
            }

            if (success) {
                isRecording = true
                Log.d("GnssLogger", "全频段满血采集已启动...")
            }
            return success
        } catch (e: Exception) {
            Log.e("GnssLogger", "启动异常", e)
            return false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return null

        locationManager?.unregisterGnssMeasurementsCallback(measurementCallback)
        // 释放定位监听器，允许手机恢复省电模式
        locationManager?.removeUpdates(dummyLocationListener)

        isRecording = false

        writeJob?.cancel()
        try {
            fileWriter?.flush()
            fileWriter?.close()
        } catch (e: Exception) {
            Log.e("GnssLogger", "流关闭异常", e)
        }

        return currentSaveFile
    }

    private fun formatMeasurementToCsv(clock: GnssClock, measurement: GnssMeasurement, timestamp: Long): String {
        val sb = StringBuilder()
        sb.append("Raw")
        sb.append(",").append(timestamp)
        sb.append(",").append(clock.timeNanos)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && clock.hasLeapSecond()) sb.append(clock.leapSecond)
        sb.append(",")
        if (clock.hasTimeUncertaintyNanos()) sb.append(clock.timeUncertaintyNanos)
        sb.append(",")
        if (clock.hasFullBiasNanos()) sb.append(clock.fullBiasNanos)
        sb.append(",")
        if (clock.hasBiasNanos()) sb.append(clock.biasNanos)
        sb.append(",")
        if (clock.hasBiasUncertaintyNanos()) sb.append(clock.biasUncertaintyNanos)
        sb.append(",")
        if (clock.hasDriftNanosPerSecond()) sb.append(clock.driftNanosPerSecond)
        sb.append(",")
        if (clock.hasDriftUncertaintyNanosPerSecond()) sb.append(clock.driftUncertaintyNanosPerSecond)
        sb.append(",").append(clock.hardwareClockDiscontinuityCount)
        sb.append(",").append(measurement.svid)
        sb.append(",").append(measurement.timeOffsetNanos)
        sb.append(",").append(measurement.state)
        sb.append(",").append(measurement.receivedSvTimeNanos)
        sb.append(",").append(measurement.receivedSvTimeUncertaintyNanos)
        sb.append(",").append(measurement.cn0DbHz)
        sb.append(",").append(measurement.pseudorangeRateMetersPerSecond)
        sb.append(",").append(measurement.pseudorangeRateUncertaintyMetersPerSecond)
        sb.append(",").append(measurement.accumulatedDeltaRangeState)
        sb.append(",").append(measurement.accumulatedDeltaRangeMeters)
        sb.append(",").append(measurement.accumulatedDeltaRangeUncertaintyMeters)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && measurement.hasCarrierFrequencyHz()) sb.append(measurement.carrierFrequencyHz)
        sb.append(",")
        sb.append(",")
        sb.append(",")
        sb.append(",").append(measurement.multipathIndicator)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && measurement.hasSnrInDb()) sb.append(measurement.snrInDb)
        sb.append(",").append(measurement.constellationType)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && measurement.hasAutomaticGainControlLevelDb()) sb.append(measurement.automaticGainControlLevelDb)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasBasebandCn0DbHz()) sb.append(measurement.basebandCn0DbHz)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasNanos()) sb.append(measurement.fullInterSignalBiasNanos)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasFullInterSignalBiasUncertaintyNanos()) sb.append(measurement.fullInterSignalBiasUncertaintyNanos)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasNanos()) sb.append(measurement.satelliteInterSignalBiasNanos)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && measurement.hasSatelliteInterSignalBiasUncertaintyNanos()) sb.append(measurement.satelliteInterSignalBiasUncertaintyNanos)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && measurement.hasCodeType()) sb.append(measurement.codeType)
        sb.append(",")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && clock.hasElapsedRealtimeNanos()) sb.append(clock.elapsedRealtimeNanos)
        sb.append(",1")
        return sb.toString()

    }
}