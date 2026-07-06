package com.example.ftpget // ★ 注意：保持你真实的包名 ★

import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.TreeMap
import kotlin.math.abs

/**
 * A logger that converts GNSS measurements to RINEX 3.05 format.
 * (Strict Kotlin Translation for exact RTKLIB compatibility)
 */
class RinexLogger(private val mContext: Context) {

    companion object {
        private const val TAG = "RinexLogger"
        private const val CLIGHT = 299792458.0
        private const val NEAR_ZERO = 0.0001
        private const val LEAP_SECOND = 18 // As of 2021/2026

        // System Constants
        private const val SYS_GPS = 1
        private const val SYS_GLO = 3
        private const val SYS_QZS = 4
        private const val SYS_BDS = 5
        private const val SYS_GAL = 6
        private const val MAX_SYS = 10
        private const val MAX_FRQ = 5

        // Measurement States
        private const val STATE_CODE_LOCK = 1 // 2^0
        private const val STATE_TOW_DECODED = 8 // 2^3
        private const val STATE_MSEC_AMBIGUOUS = 16 // 2^4
        private const val STATE_GLO_TOD_DECODED = 128 // 2^7
        private const val STATE_GAL_E1C_2ND_CODE_LOCK = 2048 // 2^11
        private const val STATE_GAL_E1BC_CODE_LOCK = 1024 // 2^10

        // ADR States
        private const val ADR_STATE_VALID = 1
        private const val ADR_STATE_RESET = 2
        private const val ADR_STATE_CYCLE_SLIP = 4
        private const val ADR_STATE_HALF_CYCLE_RESOLVED = 8
        private const val ADR_STATE_HALF_CYCLE_REPORTED = 16

        // LLI Flags
        private const val LLI_SLIP = 0x01
        private const val LLI_HALFC = 0x02
        private const val LLI_BOCTRK = 0x04

        // Thresholds
        private const val MAXPRRUNCMPS = 10.0
        private const val MAXTOWUNCNS = 500.0
        private const val MAXADRUNCNS = 1.0
    }

    private var mRinexFile: File? = null
    private var mTempBodyFile: File? = null
    private var mBodyWriter: BufferedWriter? = null
    private var mIsLogging = false

    // Accumulated data for Header
    private val mSignals = Array(MAX_SYS) { Array(MAX_FRQ) { "" } }
    private val mNumSignals = IntArray(MAX_SYS)
    private val mGlonassFreqMap = HashMap<Int, Int>()

    // Reference Clock State for Continuity
    private var mLastHwClockDiscontinuityCount = -1
    private var mRefFullBiasNanos: Long = 0
    private var mRefBiasNanos = 0.0

    // First Observation Time (High Precision)
    private var mFirstObsTime: RinexTime? = null
    private var mFirstObsSet = false

    // Track last observation time for duration
    private var mLastObsTime: RinexTime? = null

    // Previous Epoch for Galileo check
    private var mPreviousEpochSats = mutableListOf<RnxSat>()
    private var mPreviousEpochTimeMillis: Long = -1

    // Position
    private var mApproxPos = doubleArrayOf(0.0, 0.0, 0.0)

    // Naming components for output file
    private var mStationName = "GNSS00GEO"
    private var mSource = "R"   // receiver
    private var mFru = "01S"    // sampling interval
    private var mType = "MO"    // data type
    private var mStartTimeStr = "" // YYYYDDDHHMM

    // Configurable header fields
    private var mMarkerName = "GeoLog"
    private var mMarkerNumber = "Unknown"
    private var mMarkerType = "GEODETIC"
    private var mObserver = "SWJTU"
    private var mAgency = "SWJTU"
    private var mReceiverNumber = "Unknown"
    private var mReceiverType = "${Build.MANUFACTURER} ${Build.MODEL}"
    private var mReceiverVersion = Build.VERSION.RELEASE
    private var mAntennaNumber = "unknown"
    private var mAntennaType = "unknown"
    private var mAntennaDeltaH = 0.0
    private var mAntennaDeltaE = 0.0
    private var mAntennaDeltaN = 0.0

    class HeaderSettings {
        var stationName: String? = null
        var markerName: String? = null
        var markerNumber: String? = null
        var markerType: String? = null
        var observer: String? = null
        var agency: String? = null
        var receiverNumber: String? = null
        var receiverType: String? = null
        var receiverVersion: String? = null
        var antennaNumber: String? = null
        var antennaType: String? = null
        var antennaDeltaH: Double = 0.0
        var antennaDeltaE: Double = 0.0
        var antennaDeltaN: Double = 0.0
    }

    private class RinexTime(
        val year: Int, val month: Int, val day: Int, val hour: Int, val min: Int, val sec: Double
    ) {
        fun toRoughMillis(): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(year, month - 1, day, hour, min, sec.toInt())
            return cal.timeInMillis
        }
    }

    private class RnxSat(val sys: Int, val prn: Int) {
        val p = DoubleArray(MAX_FRQ)
        val l = DoubleArray(MAX_FRQ)
        val d = DoubleArray(MAX_FRQ)
        val s = DoubleArray(MAX_FRQ)
        val lli = IntArray(MAX_FRQ)
    }

    init {
        resetSignals()
    }

    fun applyHeaderSettings(settings: HeaderSettings?) {
        if (settings == null) return
        mStationName = normalizeStationName(settings.stationName)
        mMarkerName = trimOrDefault(settings.markerName, "GeoLog")
        mMarkerNumber = trimOrDefault(settings.markerNumber, "Unknown")
        mMarkerType = trimOrDefault(settings.markerType, "GEODETIC")
        mObserver = trimOrDefault(settings.observer, "SWJTU")
        mAgency = trimOrDefault(settings.agency, "SWJTU")
        mReceiverNumber = trimOrDefault(settings.receiverNumber, "Unknown")
        mReceiverType = trimOrDefault(settings.receiverType, "${Build.MANUFACTURER} ${Build.MODEL}")
        mReceiverVersion = trimOrDefault(settings.receiverVersion, Build.VERSION.RELEASE)
        mAntennaNumber = trimOrDefault(settings.antennaNumber, "unknown")
        mAntennaType = trimOrDefault(settings.antennaType, "unknown")
        mAntennaDeltaH = settings.antennaDeltaH
        mAntennaDeltaE = settings.antennaDeltaE
        mAntennaDeltaN = settings.antennaDeltaN
    }

    private fun normalizeStationName(stationName: String?): String {
        val fallback = "GNSS00GEO"
        if (stationName == null) return fallback
        val cleaned = stationName.trim().uppercase(Locale.US).replace("[^A-Z0-9]".toRegex(), "")
        if (cleaned.length < 4) return fallback
        if (cleaned.length > 9) return cleaned.substring(0, 9)
        if (cleaned.length < 9) return String.format(Locale.US, "%-9s", cleaned).replace(' ', '0')
        return cleaned
    }

    private fun trimOrDefault(value: String?, defaultValue: String): String {
        if (value == null) return defaultValue
        val trimmed = value.trim()
        return if (trimmed.isEmpty()) defaultValue else trimmed
    }

    private fun fitField(value: String?, width: Int, defaultValue: String): String {
        val v = trimOrDefault(value, defaultValue)
        return if (v.length > width) v.substring(0, width) else v
    }

    private fun resetSignals() {
        for (i in 0 until MAX_SYS) {
            mSignals[i].fill("")
            mNumSignals[i] = 0
        }
        mGlonassFreqMap.clear()
        mFirstObsSet = false
        mFirstObsTime = null
        mLastObsTime = null
        mStartTimeStr = ""
        mLastHwClockDiscontinuityCount = -1
    }

    fun startNewLog(baseDirectory: File, stationName: String?, logDate: Date) {
        if (mIsLogging) {
            stopLog()
        }
        resetSignals()
        val rinexDir = File(baseDirectory, "RINEX")
        if (!rinexDir.exists() && !rinexDir.mkdirs()) {
            Log.e(TAG, "Failed to create RINEX directory")
            return
        }

        mStationName = normalizeStationName(mStationName)
        mSource = "R"
        mFru = "01S"
        mType = "MO"

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.time = logDate
        val year = cal.get(Calendar.YEAR)
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        mStartTimeStr = String.format(Locale.US, "%04d%03d%02d%02d", year, doy, hour, minute)

        val placeholderName = String.format(Locale.US, "%s_%s_%s_%s_%s_%s.%s",
            mStationName, mSource, mStartTimeStr, "XX", mFru, mType, "rnx")
        mRinexFile = File(rinexDir, placeholderName)
        mTempBodyFile = File(rinexDir, "$placeholderName.tmp")

        try {
            mBodyWriter = BufferedWriter(FileWriter(mTempBodyFile))
            mIsLogging = true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to open RINEX temp file", e)
        }
    }

    fun stopLog() {
        if (!mIsLogging) return
        mIsLogging = false

        try {
            mBodyWriter?.close()

            if (mRinexFile != null && mTempBodyFile != null && mTempBodyFile!!.exists()) {
                var durationStr = "00S"
                if (mFirstObsTime != null && mLastObsTime != null) {
                    var diff = mLastObsTime!!.toRoughMillis() - mFirstObsTime!!.toRoughMillis()
                    if (diff < 0) diff = 0
                    if (diff >= 86400000L) {
                        val days = Math.round(diff / 86400000.0).toInt()
                        durationStr = String.format(Locale.US, "%02dD", days)
                    } else if (diff >= 3600000L) {
                        val hrs = Math.round(diff / 3600000.0).toInt()
                        durationStr = String.format(Locale.US, "%02dH", hrs)
                    } else if (diff >= 60000L) {
                        val mins = Math.round(diff / 60000.0).toInt()
                        durationStr = String.format(Locale.US, "%02dM", mins)
                    } else {
                        var secs = Math.round(diff / 1000.0).toInt()
                        if (secs == 0) secs = 1
                        durationStr = String.format(Locale.US, "%02dS", secs)
                    }
                }

                val finalName = String.format(Locale.US, "%s_%s_%s_%s_%s_%s.rnx",
                    mStationName, mSource, mStartTimeStr, durationStr, mFru, mType)
                val finalFile = File(mRinexFile!!.parentFile, finalName)

                val finalWriter = BufferedWriter(FileWriter(finalFile))
                writeHeader(finalWriter)

                val bodyReader = BufferedReader(FileReader(mTempBodyFile))
                var line: String?
                while (bodyReader.readLine().also { line = it } != null) {
                    finalWriter.write(line)
                    finalWriter.newLine()
                }
                bodyReader.close()
                finalWriter.close()

                mTempBodyFile!!.delete()
                mRinexFile = finalFile
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error finalizing RINEX file", e)
        }
    }

    fun updateLocation(location: Location?) {
        if (location != null && mIsLogging) {
            val xyz = latLonHToXyz(location.latitude, location.longitude, location.altitude)
            mApproxPos = xyz
        }
    }

    fun processGnssMeasurements(event: GnssMeasurementsEvent) {
        if (!mIsLogging || mBodyWriter == null) return

        val clock = event.clock
        val discontinuityCount = clock.hardwareClockDiscontinuityCount

        if (mLastHwClockDiscontinuityCount == -1 || discontinuityCount != mLastHwClockDiscontinuityCount) {
            mLastHwClockDiscontinuityCount = discontinuityCount
            mRefFullBiasNanos = clock.fullBiasNanos
            mRefBiasNanos = if (clock.hasBiasNanos()) clock.biasNanos else 0.0
        }

        if (!mFirstObsSet) {
            val timeNanos = clock.timeNanos
            mFirstObsTime = calculateRinexTime(timeNanos, mRefFullBiasNanos, mRefBiasNanos)
            mFirstObsSet = true
        }

        processEpoch(clock, event.measurements)
    }

    private fun processEpoch(clock: GnssClock, measurements: Collection<GnssMeasurement>) {
        val timeNanos = clock.timeNanos
        val epochTime = calculateRinexTime(timeNanos, mRefFullBiasNanos, mRefBiasNanos)
        val currentEpochMillis = epochTime.toRoughMillis()

        val epochSats = mutableListOf<RnxSat>()

        var checkGalileo4ms = false
        if (mPreviousEpochTimeMillis != -1L) {
            val diff = abs(currentEpochMillis - mPreviousEpochTimeMillis)
            if (abs(diff - 1000) < 100) {
                checkGalileo4ms = true
            }
        }

        for (m in measurements) {
            val constType = m.constellationType
            val sysId = getSystemId(constType)
            if (sysId == -1) continue

            var rawCodeType = ""
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (m.hasCodeType()) {
                    rawCodeType = m.codeType
                }
            }
            val signalName = getSmartSignalCode(sysId, m.carrierFrequencyHz.toDouble(), rawCodeType)

            if (signalName.isNullOrEmpty()) continue

            if (sysId == SYS_GLO) {
                val svid = m.svid
                val k = calculateGlonassSlot(m.carrierFrequencyHz.toDouble())
                if (k != null) {
                    mGlonassFreqMap[svid] = k
                }
            }

            val freqIndex = registerSignal(sysId, signalName)
            if (freqIndex == -1) continue

            if (!isMeasurementValid(m, sysId, signalName)) continue

            val rawCarrierFreqHz = m.carrierFrequencyHz.toDouble()
            if (rawCarrierFreqHz == 0.0) continue

            val nominalFreq = getNominalFrequency(sysId, rawCarrierFreqHz, m.svid)
            val wavl = CLIGHT / nominalFreq

            val prSeconds = calculatePseudorangeSeconds(clock, m, sysId, mRefFullBiasNanos, mRefBiasNanos)
            if (prSeconds < 0 || prSeconds > 0.5) continue

            val pseudoRange = prSeconds * CLIGHT
            val accumulatedDeltaRange = m.accumulatedDeltaRangeMeters
            var carrierPhase = accumulatedDeltaRange / wavl
            val doppler = -m.pseudorangeRateMetersPerSecond / wavl
            val cno = m.cn0DbHz
            val adrState = m.accumulatedDeltaRangeState

            if ((adrState and ADR_STATE_VALID) == 0) {
                carrierPhase = 0.0
            }

            val sat = findOrCreateSat(epochSats, sysId, m.svid)
            sat.p[freqIndex] = pseudoRange
            sat.l[freqIndex] = carrierPhase
            sat.d[freqIndex] = doppler
            sat.s[freqIndex] = cno

            sat.lli[freqIndex] = 0
            if ((adrState and ADR_STATE_HALF_CYCLE_REPORTED) != 0 && (adrState and ADR_STATE_HALF_CYCLE_RESOLVED) == 0) {
                sat.lli[freqIndex] = sat.lli[freqIndex] or LLI_HALFC
            }
            if ((adrState and ADR_STATE_RESET) != 0 || (adrState and ADR_STATE_CYCLE_SLIP) != 0) {
                sat.lli[freqIndex] = sat.lli[freqIndex] or LLI_SLIP
            }
        }

        // Galileo 4ms correction
        if (checkGalileo4ms && mPreviousEpochSats.isNotEmpty()) {
            val range4ms = 0.004 * CLIGHT
            val threshold = 1500.0

            for (sat in epochSats) {
                if (sat.sys == SYS_GAL) {
                    var prevSat: RnxSat? = null
                    for (p in mPreviousEpochSats) {
                        if (p.sys == SYS_GAL && p.prn == sat.prn) {
                            prevSat = p
                            break
                        }
                    }
                    if (prevSat == null) continue

                    for (i in 0 until MAX_FRQ) {
                        val pCurr = sat.p[i]
                        val pPrev = prevSat.p[i]

                        if (pCurr != 0.0 && pPrev != 0.0) {
                            if (abs(pCurr - pPrev - range4ms) < threshold ||
                                abs(pCurr - pPrev + range4ms) < threshold) {
                                val sign = if ((pCurr - pPrev) < 0) -1 else 1
                                sat.p[i] = sat.p[i] - sign * range4ms
                            }
                        }
                    }
                }
            }
        }

        if (epochSats.isNotEmpty()) {
            try {
                mLastObsTime = epochTime
                writeEpoch(epochTime, epochSats)
                mPreviousEpochSats = epochSats
                mPreviousEpochTimeMillis = currentEpochMillis
            } catch (e: IOException) {
                Log.e(TAG, "Error writing epoch", e)
            }
        }
    }

    private fun calculateRinexTime(timeNanos: Long, fullBiasNanos: Long, biasNanos: Double): RinexTime {
        val gpsTimeNanos = timeNanos - fullBiasNanos - biasNanos.toLong()
        val gpsTimeMillis = gpsTimeNanos / 1000000L
        val gpsEpochMillis = 315964800000L // Jan 6 1980
        val rinexTimeMillis = gpsEpochMillis + gpsTimeMillis

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = rinexTimeMillis

        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)

        val secondsInt = cal.get(Calendar.SECOND)
        var nanosPart = gpsTimeNanos % 1000000000L
        if (nanosPart < 0) nanosPart += 1000000000L

        val preciseSeconds = secondsInt + (nanosPart / 1.0e9)

        return RinexTime(year, month, day, hour, min, preciseSeconds)
    }

    private fun getSmartSignalCode(sys: Int, carrierFreqHz: Double, androidCodeType: String?): String? {
        val freqMhz = Math.round(carrierFreqHz / 1e5) / 10.0
        val rawCode = androidCodeType ?: ""
        var bandId = ""
        var defaultAttr = ""

        if (sys == SYS_BDS && abs(freqMhz - 1561.1) < 1.0) {
            bandId = "2"
            defaultAttr = "I"
        } else if (abs(freqMhz - 1575.4) < 1.0 || (sys == SYS_GLO && freqMhz > 1590 && freqMhz < 1615)) {
            bandId = "1"
            defaultAttr = if (sys == SYS_BDS) "P" else "C"
        } else if (abs(freqMhz - 1176.4) < 1.0) {
            bandId = "5"
            defaultAttr = if (sys == SYS_BDS) "P" else "Q"
        } else if (abs(freqMhz - 1227.6) < 1.0 || (sys == SYS_GLO && freqMhz > 1230 && freqMhz < 1260)) {
            bandId = "2"
            defaultAttr = "C"
        } else if (abs(freqMhz - 1207.1) < 1.0) {
            bandId = "7"
            defaultAttr = if (sys == SYS_BDS) "I" else "Q"
        } else if (abs(freqMhz - 1268.5) < 1.0) {
            bandId = "6"
            defaultAttr = "I"
        }

        if (bandId.isEmpty()) return null
        var finalAttr = if (rawCode.isEmpty()) defaultAttr else rawCode

        if (sys == SYS_BDS && "5" == bandId && "Q" == finalAttr) finalAttr = "P"
        if ("1" == bandId && "L" == finalAttr) return null

        return bandId + finalAttr
    }

    private fun calculateGlonassSlot(freq: Double): Int? {
        if (freq > 1.59e9) return Math.round((freq - 1602.0e6) / 0.5625e6).toInt()
        if (freq > 1.23e9 && freq < 1.26e9) return Math.round((freq - 1246.0e6) / 0.4375e6).toInt()
        return null
    }

    private fun getNominalFrequency(sysId: Int, rawFreq: Double, svid: Int): Double {
        if (sysId == SYS_GLO) {
            val k = mGlonassFreqMap[svid]
            if (k != null) {
                return if (rawFreq > 1.5e9) 1602.0e6 + k * 0.5625e6 else 1246.0e6 + k * 0.4375e6
            }
            return rawFreq
        } else if (sysId == SYS_BDS) {
            if (abs(rawFreq - 1561.098e6) < 1.0e6) return 1561.098e6
        }
        return Math.round(rawFreq / 1000.0) * 1000.0
    }

    private fun calculatePseudorangeSeconds(
        clock: GnssClock, m: GnssMeasurement, sysId: Int, refFullBiasNanos: Long, refBiasNanos: Double
    ): Double {
        val timeNanos = clock.timeNanos
        val timeOffsetNanos = m.timeOffsetNanos

        val gpsTimeNanos = timeNanos - refFullBiasNanos + timeOffsetNanos.toLong()
        val tTxNanos = m.receivedSvTimeNanos

        val weekNanos = 604800L * 1000000000L
        val dayNanos = 86400L * 1000000000L

        var tRxModNanos: Long = 0

        if (sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_QZS || sysId == SYS_BDS) {
            var timeOfWeekNanos = gpsTimeNanos % weekNanos
            if (sysId == SYS_BDS) {
                timeOfWeekNanos = (gpsTimeNanos - 14000000000L) % weekNanos
            }
            tRxModNanos = timeOfWeekNanos
        } else if (sysId == SYS_GLO) {
            val timeOfDayNanos = gpsTimeNanos % dayNanos
            val gloOffsetNanos = (3 * 3600 - LEAP_SECOND) * 1000000000L
            tRxModNanos = timeOfDayNanos + gloOffsetNanos
        }

        var flightTimeNanos = tRxModNanos - tTxNanos

        val halfWeekNanos = 302400L * 1000000000L
        val halfDayNanos = 43200L * 1000000000L

        if (sysId != SYS_GLO) {
            if (flightTimeNanos > halfWeekNanos) {
                flightTimeNanos -= weekNanos
            } else if (flightTimeNanos < -halfWeekNanos) {
                flightTimeNanos += weekNanos
            }
        } else {
            if (flightTimeNanos > halfDayNanos) {
                flightTimeNanos -= dayNanos
            } else if (flightTimeNanos < -halfDayNanos) {
                flightTimeNanos += dayNanos
            }
        }

        var pr = (flightTimeNanos - refBiasNanos) * 1e-9

        if ((sysId == SYS_GPS || sysId == SYS_GAL || sysId == SYS_BDS || sysId == SYS_QZS) && pr > 604800)
            pr %= 604800.0
        if (sysId == SYS_GLO && pr > 86400)
            pr %= 86400.0

        return pr
    }

    private fun isMeasurementValid(m: GnssMeasurement, sysId: Int, signalName: String): Boolean {
        val state = m.state
        if ((state and STATE_MSEC_AMBIGUOUS) != 0) return false

        var towDecoded = false
        if (sysId == SYS_GLO) {
            towDecoded = (state and STATE_GLO_TOD_DECODED) != 0
        } else {
            towDecoded = (state and STATE_TOW_DECODED) != 0
        }
        if (!towDecoded) return false

        var codeLock = false
        if (sysId == SYS_GAL && "1C" == signalName) {
            codeLock = (state and STATE_GAL_E1BC_CODE_LOCK) != 0 || (state and STATE_GAL_E1C_2ND_CODE_LOCK) != 0
        } else {
            codeLock = (state and STATE_CODE_LOCK) != 0
        }
        if (!codeLock) return false

        if (m.pseudorangeRateUncertaintyMetersPerSecond > MAXPRRUNCMPS) return false
        if (m.receivedSvTimeUncertaintyNanos > MAXTOWUNCNS) return false
        if (m.accumulatedDeltaRangeUncertaintyMeters > MAXADRUNCNS) return false

        return true
    }

    private fun registerSignal(sys: Int, sig: String): Int {
        val sysIdx = getSystemIndex(sys)
        if (sysIdx == -1) return -1
        for (i in 0 until mNumSignals[sysIdx]) {
            if (mSignals[sysIdx][i] == sig) return i
        }
        if (mNumSignals[sysIdx] < MAX_FRQ) {
            mSignals[sysIdx][mNumSignals[sysIdx]] = sig
            mNumSignals[sysIdx]++
            return mNumSignals[sysIdx] - 1
        }
        return -1
    }

    private fun findOrCreateSat(sats: MutableList<RnxSat>, sys: Int, prn: Int): RnxSat {
        for (s in sats) {
            if (s.sys == sys && s.prn == prn) return s
        }
        val newSat = RnxSat(sys, prn)
        sats.add(newSat)
        return newSat
    }

    @Throws(IOException::class)
    private fun writeEpoch(t: RinexTime, sats: List<RnxSat>) {
        val validSats = mutableListOf<RnxSat>()
        for (sat in sats) {
            var allZero = true
            for (i in 0 until MAX_FRQ) {
                if (abs(sat.p[i]) > NEAR_ZERO || abs(sat.l[i]) > NEAR_ZERO) {
                    allZero = false
                    break
                }
            }
            if (!allZero) validSats.add(sat)
        }

        validSats.sortWith { o1, o2 ->
            val p1 = getSystemPriority(o1.sys)
            val p2 = getSystemPriority(o2.sys)
            if (p1 != p2) p1.compareTo(p2) else o1.prn.compareTo(o2.prn)
        }

        mBodyWriter?.write(String.format(Locale.US, "> %04d %02d %02d %02d %02d %10.7f  0 %2d",
            t.year, t.month, t.day, t.hour, t.min, t.sec, validSats.size))
        mBodyWriter?.newLine()

        for (sat in validSats) {
            val sysChar = getSystemChar(sat.sys)
            var prn = sat.prn
            if (sat.sys == SYS_QZS) prn -= 192

            mBodyWriter?.write(String.format(Locale.US, "%c%02d", sysChar, prn))

            val sysIdx = getSystemIndex(sat.sys)
            if (sysIdx != -1) {
                for (i in 0 until mNumSignals[sysIdx]) {
                    mBodyWriter?.write(formatObs(sat.p[i]))
                    val lli = sat.lli[i] and (LLI_SLIP or LLI_HALFC or LLI_BOCTRK)
                    mBodyWriter?.write(formatPhase(sat.l[i], lli))
                    mBodyWriter?.write(formatObs(sat.d[i]))
                    mBodyWriter?.write(formatObs(sat.s[i]))
                }
            }
            mBodyWriter?.newLine()
        }
    }

    private fun formatObs(value: Double): String {
        if (abs(value) < NEAR_ZERO) return "                "
        return String.format(Locale.US, "%14.3f  ", value)
    }

    private fun formatPhase(value: Double, lli: Int): String {
        if (abs(value) < NEAR_ZERO) return "                "
        val lliStr = if (lli == 0) " " else lli.toString()
        return String.format(Locale.US, "%14.3f%s ", value, lliStr)
    }

    @Throws(IOException::class)
    private fun writeHeader(writer: BufferedWriter) {
        writer.write("     3.05           OBSERVATION DATA    M: Mixed            RINEX VERSION / TYPE\n")
        val sdf = SimpleDateFormat("yyyyMMdd HHmmss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val dateStr = "${sdf.format(Date())} UTC"
        val pgm = "GeoLog"
        var runBy = Build.MANUFACTURER
        if (runBy.length > 20) runBy = runBy.substring(0, 20)

        val receiverNumber = fitField(mReceiverNumber, 20, "Unknown")
        val receiverType = fitField(mReceiverType, 20, "${Build.MANUFACTURER} ${Build.MODEL}")
        val receiverVersion = fitField(mReceiverVersion, 20, Build.VERSION.RELEASE)
        val antennaNumber = fitField(mAntennaNumber, 20, "unknown")
        val antennaType = fitField(mAntennaType, 40, "unknown")
        val observer = fitField(mObserver, 20, "SWJTU")
        val agency = fitField(mAgency, 40, "SWJTU")

        writer.write(String.format(Locale.US, "%-20s%-20s%-20sPGM / RUN BY / DATE   \n", pgm, runBy, dateStr))
        writer.write(String.format(Locale.US, "%-60sMARKER NAME         \n", mMarkerName))
        writer.write(String.format(Locale.US, "%-60sMARKER NUMBER       \n", mMarkerNumber))
        writer.write(String.format(Locale.US, "%-60sMARKER TYPE         \n", mMarkerType))
        writer.write(String.format(Locale.US, "%-20s%-40sOBSERVER / AGENCY   \n", observer, agency))
        writer.write(String.format(Locale.US, "%-20s%-20s%-20sREC # / TYPE / VERS \n", receiverNumber, receiverType, receiverVersion))
        writer.write(String.format(Locale.US, "%-20s%-40sANT # / TYPE        \n", antennaNumber, antennaType))
        writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  APPROX POSITION XYZ \n", mApproxPos[0], mApproxPos[1], mApproxPos[2]))
        writer.write(String.format(Locale.US, "%14.4f%14.4f%14.4f                  ANTENNA: DELTA H/E/N\n", mAntennaDeltaH, mAntennaDeltaE, mAntennaDeltaN))

        val sysChars = charArrayOf('G', 'R', 'E', 'C', 'J')
        val sysIds = intArrayOf(SYS_GPS, SYS_GLO, SYS_GAL, SYS_BDS, SYS_QZS)

        for (k in 0..4) {
            val sys = sysIds[k]
            val idx = getSystemIndex(sys)
            if (mNumSignals[idx] > 0) {
                val codes = mutableListOf<String>()
                for (i in 0 until mNumSignals[idx]) {
                    val suf = mSignals[idx][i]
                    codes.add("C$suf")
                    codes.add("L$suf")
                    codes.add("D$suf")
                    codes.add("S$suf")
                }

                val nObs = codes.size
                val firstBatch = codes.subList(0, Math.min(codes.size, 13))
                var sb = java.lang.StringBuilder()
                for (c in firstBatch) sb.append(String.format("%-4s", c))

                writer.write(String.format(Locale.US, "%c  %3d %-52s SYS / # / OBS TYPES \n", sysChars[k], nObs, sb.toString()))

                var i = 13
                while (i < codes.size) {
                    val batch = codes.subList(i, Math.min(codes.size, i + 13))
                    sb = java.lang.StringBuilder()
                    for (c in batch) sb.append(String.format("%-4s", c))
                    writer.write(String.format(Locale.US, "       %-52s SYS / # / OBS TYPES \n", sb.toString()))
                    i += 13
                }
            }
        }

        if (mGlonassFreqMap.isNotEmpty()) {
            val sortedSlots = TreeMap(mGlonassFreqMap)
            var count = 0
            var sb = java.lang.StringBuilder()
            sb.append(String.format(Locale.US, "%3d ", sortedSlots.size))
            for ((key, value) in sortedSlots) {
                sb.append(String.format(Locale.US, "R%02d %2d ", key, value))
                count++
                if (count == 8) {
                    writer.write(String.format(Locale.US, "%-60sGLONASS SLOT / FRQ #\n", sb.toString()))
                    sb = java.lang.StringBuilder("    ")
                    count = 0
                }
            }
            if (count > 0) {
                writer.write(String.format(Locale.US, "%-60sGLONASS SLOT / FRQ #\n", sb.toString()))
            }
        }

        if (mFirstObsTime != null) {
            writer.write(String.format(Locale.US,
                "  %04d    %02d    %02d    %02d    %02d   %10.7f     GPS         TIME OF FIRST OBS\n",
                mFirstObsTime!!.year, mFirstObsTime!!.month, mFirstObsTime!!.day,
                mFirstObsTime!!.hour, mFirstObsTime!!.min, mFirstObsTime!!.sec))
        }

        writer.write("                                                            END OF HEADER       \n")
    }

    private fun getSystemId(constType: Int): Int {
        return when (constType) {
            GnssStatus.CONSTELLATION_GPS -> SYS_GPS
            GnssStatus.CONSTELLATION_GLONASS -> SYS_GLO
            GnssStatus.CONSTELLATION_BEIDOU -> SYS_BDS
            GnssStatus.CONSTELLATION_GALILEO -> SYS_GAL
            GnssStatus.CONSTELLATION_QZSS -> SYS_QZS
            else -> -1
        }
    }

    private fun getSystemIndex(sys: Int): Int {
        return when (sys) {
            SYS_GPS -> 0
            SYS_GLO -> 1
            SYS_GAL -> 2
            SYS_BDS -> 3
            SYS_QZS -> 4
            else -> -1
        }
    }

    private fun getSystemChar(sys: Int): Char {
        return when (sys) {
            SYS_GPS -> 'G'
            SYS_GLO -> 'R'
            SYS_GAL -> 'E'
            SYS_BDS -> 'C'
            SYS_QZS -> 'J'
            else -> ' '
        }
    }

    private fun getSystemPriority(sys: Int): Int {
        return when (sys) {
            SYS_GPS -> 1
            SYS_GLO -> 2
            SYS_GAL -> 3
            SYS_BDS -> 4
            else -> 5
        }
    }

    private fun latLonHToXyz(lat: Double, lon: Double, alt: Double): DoubleArray {
        val a = 6378137.0
        val f = 1 / 298.257223563
        val eSq = 2 * f - f * f
        val radLat = Math.toRadians(lat)
        val radLon = Math.toRadians(lon)
        val N = a / Math.sqrt(1 - eSq * Math.pow(Math.sin(radLat), 2.0))
        val x = (N + alt) * Math.cos(radLat) * Math.cos(radLon)
        val y = (N + alt) * Math.cos(radLat) * Math.sin(radLon)
        val z = (N * (1 - eSq) + alt) * Math.sin(radLat)
        return doubleArrayOf(x, y, z)
    }

    fun getFile(): File? {
        return mRinexFile
    }
}