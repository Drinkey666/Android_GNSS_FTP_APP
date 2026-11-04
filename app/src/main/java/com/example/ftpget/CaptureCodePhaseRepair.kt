package com.example.ftpget

import kotlin.math.*

/** Causal experimental adaptation of Mu et al., Remote Sensing 16(17),3117 (2024).
 * Input is immutable Android Raw, output is a separate candidate stream, never a Raw substitute.
 * A shared smooth range-rate model is NOT an identification of receiver code-carrier drift.
 */
data class CaptureRepairConfig(
    val gateSigma: Double = 2.0, // paper eq.22, two-sided
    val drivingStd: Double = 1.0, // engineering assumption, m/s^4; NOT specified in paper
    val maxGapSeconds: Double = 1.5,
    val maxPredictionEpochs: Int = 3,
    // Real-prefix injected-fault test did NOT support precise carrier reconstruction from code.
    val enablePhaseCandidates: Boolean = false,
) {
    init {
        require(gateSigma.isFinite() && gateSigma > 0)
        require(drivingStd.isFinite() && drivingStd > 0)
        require(maxGapSeconds.isFinite() && maxGapSeconds > 0)
        require(maxPredictionEpochs >= 0)
    }
}

data class CaptureObservation(
    val timeNanos: Long, val discontinuity: Int, val system: Int, val satellite: Int,
    val frequencyHz: Double, val code: String, val codeMeters: Double?, val codeSigma: Double?,
    val adrMeters: Double?, val adrSigma: Double?, val adrState: Int,
    val cn0: Double, val dopplerRate: Double?,
) {
    init {
        require(frequencyHz.isFinite() && frequencyHz > 0)
        require(codeMeters == null || codeMeters.isFinite())
        require(adrMeters == null || adrMeters.isFinite())
        require(codeSigma == null || codeSigma.isFinite() && codeSigma > 0)
        require(adrSigma == null || adrSigma.isFinite() && adrSigma > 0)
    }
    // Keep actual frequency (including GLONASS channel) and tracking code, never a frequency slot.
    val key: String get() = "$system:$satellite:${frequencyHz.roundToLong()}:$code"
    val phaseContinuous: Boolean get() = adrMeters != null && adrSigma != null &&
        adrState and 1 != 0 && adrState and 6 == 0 &&
        (adrState and 16 == 0 || adrState and 8 != 0)

    companion object {
        const val C = 299792458.0
        /** Raw -> physical candidates. Missing/invalid fields remain absent; no zero filling. */
        fun fromRaw(line: String): CaptureObservation? = try {
            val c = line.split(',')
            if (c.size < 36 || c[0] != "Raw") null else {
                fun number(i: Int) = c[i].toDoubleOrNull()?.takeIf { it.isFinite() }
                fun positive(i: Int) = number(i)?.takeIf { it > 0 }
                val time = c[2].toLong(); val full = c[5].toLong()
                val sys = c[28].toInt(); val state = c[13].toInt()
                val freq = positive(22) ?: throw IllegalArgumentException("frequency")
                val period = if (sys == 3) 86400000000000L else 604800000000000L
                // Integer remainder FIRST: conversion of a ~1.5e18 ns GPS time to Double loses range precision.
                val gps = Math.subtractExact(time, full)
                var rx = Math.floorMod(gps, period).toDouble() - (number(6) ?: 0.0) + (number(12) ?: 0.0)
                if (sys == 5) rx -= 14e9
                if (sys == 3) rx += (10800 - c[3].toInt()).toDouble() * 1e9
                val tx = c[14].toLong()
                val delta = (rx - tx) % period
                val flight = (if (delta < 0) delta + period else delta) * 1e-9
                val timeKnown = state and (8 or 16384) != 0 || sys == 3 && state and (128 or 32768) != 0
                val p = (flight * C).takeIf { timeKnown && state and 1 != 0 && flight in 0.001..0.2 }
                CaptureObservation(time, c[10].toInt(), sys, c[11].toInt(), freq, c[35],
                    p, positive(15)?.times(C * 1e-9), number(20), positive(21), c[19].toInt(),
                    number(16) ?: Double.NaN, number(17))
            }
        } catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }
    }
}

data class CaptureRepairRow(
    val observation: CaptureObservation, val type: String, val raw: Double?, val candidate: Double?,
    val status: String, val innovation: Double? = null, val innovationStd: Double? = null,
    val candidateSigmaUpper: Double? = null, val peers: Int = 0,
    // Even a successful repair is correlated/model-dependent, NOT an independent measurement.
    val synthetic: Boolean = false,
) {
    fun csv(): String {
        val o = observation
        fun n(x: Double?) = x?.takeIf { it.isFinite() }?.toString() ?: ""
        return listOf(o.timeNanos, o.discontinuity, o.system, o.satellite,
            o.frequencyHz, o.code, type, n(o.cn0), n(raw), n(candidate),
            n(if (raw != null && candidate != null) candidate - raw else null),
            status, n(innovation), n(innovationStd), n(candidateSigmaUpper), peers,
            synthetic, false, o.adrState, n(o.dopplerRate)).joinToString(",")
    }
    companion object {
        const val HEADER = "time_nanos,hcdc,system,svid,frequency_hz,code,type,cn0,raw_m,candidate_m,correction_m,status,innovation_mps,innovation_std_mps,candidate_sigma_upper_m,healthy_rows,synthetic,independent_measurement,adr_state,doppler_rate_mps"
    }
}

class CaptureCodePhaseRepair(private val config: CaptureRepairConfig = CaptureRepairConfig()) {
    private data class Previous(val o: CaptureObservation, val p: Double?, val l: Double?,
                                val ps: Double?, val ls: Double?)
    private data class Anchor(val t: Long, val l: Double, val sigma: Double)
    private class Track {
        var previous = mutableMapOf<String, Previous>()
        val anchor = ArrayList<Anchor>()
        var anchorKey = ""
        var x: DoubleArray? = null // [range rate, its derivative, its second derivative] at interval midpoint
        var p = DoubleArray(9)
        var midpoint = 0.0
        var predictedOnly = 0
    }
    private data class Test(val o: CaptureObservation, val type: String, val raw: Double,
                            val old: Double, val oldSigma: Double, val sigma: Double,
                            val dt: Double, val rate: Double, val variance: Double,
                            val residual: Double, val residualStd: Double, val accepted: Boolean)
    private val tracks = mutableMapOf<String, Track>()
    private var lastTime: Long? = null
    private var lastClock: Int? = null
    fun reset() { tracks.clear(); lastTime = null; lastClock = null }

    fun process(epoch: List<CaptureObservation>): List<CaptureRepairRow> {
        if (epoch.isEmpty()) return emptyList()
        val time = epoch.first().timeNanos; val clock = epoch.first().discontinuity
        require(epoch.all { it.timeNanos == time && it.discontinuity == clock }) { "mixed epoch" }
        val dt = lastTime?.let { (time - it).toDouble() * 1e-9 }
        val resetReason = when {
            lastClock != null && clock != lastClock -> "CLOCK_RESET"
            dt != null && dt <= 0 -> "TIME_RESET"
            dt != null && dt > config.maxGapSeconds -> "GAP_RESET"
            else -> null
        }
        if (resetReason != null) tracks.clear()
        lastTime = time; lastClock = clock
        val result = ArrayList<CaptureRepairRow>()
        val groups = epoch.groupBy { "${it.system}:${it.satellite}" }
        tracks.keys.retainAll(groups.keys) // no bridging a missing satellite epoch
        for ((sat, obs) in groups) {
            val track = tracks.getOrPut(sat) { Track() }
            // Never arbitrarily pick one of duplicate identities.
            if (obs.map { it.key }.toSet().size != obs.size) {
                tracks.remove(sat)
                result += obs.flatMap { plain(it, "DUPLICATE_SIGNAL") }; continue
            }
            val anchor = obs.filter { it.phaseContinuous }
                .firstOrNull { it.key == track.anchorKey }
                ?: obs.filter { it.phaseContinuous }.maxByOrNull { it.frequencyHz }
            if (anchor == null || anchor.key != track.anchorKey) {
                track.x = null; track.anchor.clear(); track.anchorKey = anchor?.key ?: ""
            }
            if (anchor != null) {
                track.anchor.add(Anchor(time, anchor.adrMeters!!, anchor.adrSigma!!))
                if (track.anchor.size > 4) track.anchor.removeAt(0)
            }
            if (track.x == null) {
                result += obs.flatMap { plain(it, resetReason ?: "WARMUP") }
                storeOriginal(track, obs)
                if (track.anchor.size == 4) initialize(track)
                continue // initialization uses only these past samples; never correct its own initializer
            }
            val latestPrevious = track.previous.values.firstOrNull()?.o?.timeNanos
            val step = latestPrevious?.let { (time - it).toDouble() * 1e-9 } ?: 0.0
            if (step <= 0 || step > config.maxGapSeconds) {
                tracks.remove(sat); result += obs.flatMap { plain(it, "SIGNAL_GAP_RESET") }; continue
            }
            // Midpoints keep variable callback intervals causal; equal 1s reduces to paper's increment state.
            // Absolute monotonic seconds are safe here (not GPS nanoseconds).
            val absoluteMidpoint = time.toDouble() * 1e-9 - step / 2
            val prediction = propagate(track.x!!, track.p, absoluteMidpoint - track.midpoint, config.drivingStd)
            val xp = prediction.first; val pp = prediction.second
            val tests = ArrayList<Test>()
            val bypass = ArrayList<CaptureRepairRow>()
            for (o in obs) {
                val previous = track.previous[o.key]
                for (type in listOf("P", "L")) {
                    val raw = if (type == "P") o.codeMeters else o.adrMeters?.takeIf { o.phaseContinuous }
                    val sigma = if (type == "P") o.codeSigma else o.adrSigma
                    val old = if (type == "P") previous?.p else previous?.l?.takeIf { previous.o.phaseContinuous }
                    val oldSigma = if (type == "P") previous?.ps else previous?.ls
                    if (raw == null || sigma == null || old == null || oldSigma == null) {
                        bypass += CaptureRepairRow(o, type, raw, raw, "UNAVAILABLE_OR_NEW_ARC"); continue
                    }
                    val rate = (raw - old) / step
                    val variance = (sigma * sigma + oldSigma * oldSigma) / (step * step)
                    val sd = sqrt(pp[0] + variance)
                    val residual = rate - xp[0]
                    tests += Test(o, type, raw, old, oldSigma, sigma, step, rate, variance,
                        residual, sd, sd.isFinite() && sd > 0 && abs(residual) <= config.gateSigma * sd)
                }
            }
            val accepted = tests.filter { it.accepted }
            var x = xp.copyOf(); var p = pp.copyOf()
            // All gates above use the SAME prediction, before ANY observation update.
            for (t in accepted) {
                val update = scalarUpdate(x, p, t.rate, t.variance)
                x = update.first; p = update.second
            }
            val finite = x.all { it.isFinite() } && p.all { it.isFinite() } && (0..2).all { p[it * 3 + it] >= 0 }
            if (!finite) {
                tracks.remove(sat); result += obs.flatMap { plain(it, "NUMERICAL_RESET") }; continue
            }
            track.predictedOnly = if (accepted.isEmpty()) track.predictedOnly + 1 else 0
            if (track.predictedOnly > config.maxPredictionEpochs) {
                tracks.remove(sat); result += obs.flatMap { plain(it, "PREDICTION_LIMIT_RESET") }; continue
            }
            val rows = ArrayList<CaptureRepairRow>(); rows += bypass
            for (t in tests) {
                val hasSupport = if (t.type == "P") accepted.any { it.type == "L" } else accepted.isNotEmpty()
                val repaired = !t.accepted && hasSupport && (t.type == "P" || config.enablePhaseCandidates)
                val candidate = if (repaired) t.old + x[0] * t.dt else t.raw
                // Unknown cross-correlation: Cauchy upper bound, NOT an independent measurement sigma.
                val upper = if (repaired) t.oldSigma + sqrt(max(0.0, p[0])) * t.dt else t.sigma
                rows += CaptureRepairRow(t.o, t.type, t.raw, candidate,
                    when {
                        t.accepted -> "ORIGINAL_ACCEPTED"
                        repaired -> "REPAIR_CANDIDATE"
                        t.type == "L" && !config.enablePhaseCandidates -> "PHASE_ANOMALY_NOT_REPAIRED"
                        t.type == "P" && accepted.isNotEmpty() -> "NO_HEALTHY_PHASE"
                        else -> "NO_HEALTHY_PEER"
                    },
                    t.residual, t.residualStd, upper, accepted.size, repaired)
            }
            result += rows
            track.x = x; track.p = p; track.midpoint = absoluteMidpoint
            track.previous = obs.associate { o ->
                val pr = rows.first { it.observation.key == o.key && it.type == "P" }
                val lr = rows.first { it.observation.key == o.key && it.type == "L" }
                o.key to Previous(o, pr.candidate, lr.candidate,
                    pr.candidateSigmaUpper ?: o.codeSigma, lr.candidateSigmaUpper ?: o.adrSigma)
            }.toMutableMap()
        }
        return result
    }

    private fun plain(o: CaptureObservation, status: String) = listOf(
        CaptureRepairRow(o, "P", o.codeMeters, o.codeMeters, status, candidateSigmaUpper = o.codeSigma),
        CaptureRepairRow(o, "L", o.adrMeters?.takeIf { o.phaseContinuous },
            o.adrMeters?.takeIf { o.phaseContinuous }, status, candidateSigmaUpper = o.adrSigma))

    private fun storeOriginal(t: Track, obs: List<CaptureObservation>) {
        t.previous = obs.associate { o -> o.key to Previous(o, o.codeMeters,
            o.adrMeters?.takeIf { o.phaseContinuous }, o.codeSigma, o.adrSigma) }.toMutableMap()
    }

    private fun initialize(t: Track) {
        val a = t.anchor
        val times = a.map { (it.t - a[0].t).toDouble() * 1e-9 }
        val d = (0..2).map { times[it + 1] - times[it] }
        if (d.any { it <= 0 || it > config.maxGapSeconds }) return
        val mid = (0..2).map { (times[it] + times[it + 1]) / 2 }
        // Interpolate 3 past interval rates; the exact Jacobian includes adjacent-increment covariance.
        val basis = Array(3) { j ->
            val others = (0..2).filter { it != j }
            val b = mid[others[0]] - mid[2]; val c = mid[others[1]] - mid[2]
            val den = (mid[j] - mid[others[0]]) * (mid[j] - mid[others[1]])
            doubleArrayOf(b * c / den, -(b + c) / den, 2 / den)
        }
        val transform = Array(3) { DoubleArray(4) }
        for (k in 0..2) for (j in 0..2) {
            transform[k][j] -= basis[j][k] / d[j]
            transform[k][j + 1] += basis[j][k] / d[j]
        }
        t.x = DoubleArray(3) { k -> (0..3).sumOf { transform[k][it] * a[it].l } }
        t.p = DoubleArray(9) { idx -> (0..3).sumOf {
            transform[idx / 3][it] * transform[idx % 3][it] * a[it].sigma.pow(2) } }
        t.midpoint = a[0].t.toDouble() * 1e-9 + mid[2]
    }

    companion object {
        internal fun propagate(x: DoubleArray, p: DoubleArray, dt: Double, drivingStd: Double): Pair<DoubleArray, DoubleArray> {
            val f = doubleArrayOf(1.0, dt, dt * dt / 2, 0.0, 1.0, dt, 0.0, 0.0, 1.0)
            val g = doubleArrayOf(dt.pow(3) / 6, dt * dt / 2, dt)
            val xp = DoubleArray(3) { i -> (0..2).sumOf { f[i * 3 + it] * x[it] } }
            val pp = DoubleArray(9) { q -> val i = q / 3; val j = q % 3
                (0..2).sumOf { k -> (0..2).sumOf { l -> f[i * 3 + k] * p[k * 3 + l] * f[j * 3 + l] } } +
                    g[i] * g[j] * drivingStd * drivingStd }
            return xp to pp
        }

        internal fun scalarUpdate(x: DoubleArray, p: DoubleArray, z: Double, variance: Double): Pair<DoubleArray, DoubleArray> {
            val s = p[0] + variance; val k = DoubleArray(3) { p[it * 3] / s }
            val a = DoubleArray(9) { i -> (if (i / 3 == i % 3) 1.0 else 0.0) - if (i % 3 == 0) k[i / 3] else 0.0 }
            val pp = DoubleArray(9) { q -> val i = q / 3; val j = q % 3
                (0..2).sumOf { u -> (0..2).sumOf { v -> a[i * 3 + u] * p[u * 3 + v] * a[j * 3 + v] } } + k[i] * variance * k[j] }
            return DoubleArray(3) { x[it] + k[it] * (z - x[0]) } to pp
        }
    }
}
