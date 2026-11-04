package com.example.ftpget

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Temporary infrastructure probe, no PPP, positions, products or defaults. */
@RunWith(AndroidJUnit4::class)
class GnssFullTrackingProbeTest {
    @SdkSuppress(minSdkVersion = 31)
    @Test fun reportActualHardwareTrackingMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Existing fine location permission is required; do not bypass it",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        // This probe does not need a fresh monitored activity lifecycle.
        // Do not block GNSS registration waiting for startActivitySync.
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val worker = HandlerThread("gnss-tracking-probe").apply { start() }
        val handler = Handler(worker.looper)
        val enough = CountDownLatch(15)
        val lock = Any()
        var events = 0
        var available = 0
        var full = 0
        var reduced = 0
        var measurements = 0
        var adrValid = 0
        var adrReset = 0
        var adrSlip = 0
        var firstElapsed = 0L
        var lastElapsed = 0L
        val discontinuities = sortedSetOf<Int>()
        val stateCounts = mutableMapOf<Int, Int>()
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                synchronized(lock) {
                    events++
                    if (firstElapsed == 0L) firstElapsed = SystemClock.elapsedRealtime()
                    lastElapsed = SystemClock.elapsedRealtime()
                    discontinuities += eventArgs.clock.hardwareClockDiscontinuityCount
                    if (Build.VERSION.SDK_INT >= 34 && eventArgs.hasIsFullTracking()) {
                        available++
                        if (eventArgs.isFullTracking) full++ else reduced++
                    }
                    for (measurement in eventArgs.measurements) {
                        val state = measurement.accumulatedDeltaRangeState
                        measurements++
                        if (state and 1 != 0) adrValid++
                        if (state and 2 != 0) adrReset++
                        if (state and 4 != 0) adrSlip++
                        stateCounts[state] = (stateCounts[state] ?: 0) + 1
                    }
                }
                enough.countDown()
            }
        }
        val listener = LocationListener { _: Location -> /* do not record location */ }
        var registered = false
        try {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, worker.looper)
            registered = manager.registerGnssMeasurementsCallback(
                GnssMeasurementRequest.Builder().setFullTracking(true).build(),
                { runnable -> handler.post(runnable) }, callback)
            assertTrue("Full-tracking callback registration failed", registered)
            enough.await(25, TimeUnit.SECONDS)
        } finally {
            if (registered) manager.unregisterGnssMeasurementsCallback(callback)
            manager.removeUpdates(listener)
            handler.post {
                synchronized(lock) {
                    val report = JSONObject().apply {
                        put("device", Build.MODEL)
                        put("sdk", Build.VERSION.SDK_INT)
                        put("requested_full_tracking", true)
                        put("registration_success", registered)
                        put("events", events)
                        put("reported_mode_available_events", available)
                        put("reported_full_events", full)
                        put("reported_reduced_events", reduced)
                        put("reported_mode_unavailable_events", events - available)
                        put("measurements", measurements)
                        put("ADR_valid", adrValid)
                        put("ADR_reset", adrReset)
                        put("ADR_cycle_slip", adrSlip)
                        put("ADR_state_counts", JSONObject(stateCounts.mapKeys { it.key.toString() }))
                        put("hardware_clock_discontinuity_values", JSONArray(discontinuities.toList()))
                        put("first_elapsed_ms", firstElapsed)
                        put("last_elapsed_ms", lastElapsed)
                        put("coordinates_recorded", false)
                        put("PPP_filter_started", false)
                        put("scope", "Current short infrastructure probe; not proof of historical tracking mode, outdoor conditions or PPP accuracy")
                    }
                    File(context.filesDir, "full_tracking_probe.json").writeText(report.toString(2))
                }
                worker.quitSafely()
            }
            worker.join(5000)
        }
        assertTrue("No raw events in this short probe; see saved report", events > 0)
    }
}
