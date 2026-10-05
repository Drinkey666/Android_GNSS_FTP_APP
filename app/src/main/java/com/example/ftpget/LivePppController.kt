package com.example.ftpget

import android.annotation.SuppressLint
import android.app.Activity
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * 实时输入调度器：一个 Android event → 一次 JNI 转换 → 至多一次 rtkpos。
 * 不读 TXT、不生成 RINEX OBS、不在每秒重新初始化；本类不实现 PPP 滤波公式。
 *
 * @param activity 仅用于向主线程派发界面消息/结果。
 * @param locationManager 原始 GNSS 回调的数据源，调用前应具有精确定位权限。
 * @param productDir 手机本地产品目录，第一次事件到来时才按 GNSS 时间选择产品。
 * @param dumpDir 转换后 obsd_t 的 CSV 诊断输出目录，不是原始 TXT 目录。
 * @param onMessage 运行/错误提示，在主线程更新界面。
 * @param onResult 一个历元的返回结果，在主线程显示，不代表持久化了坐标。
 */
class LivePppController(
    private val activity: Activity,
    private val locationManager: LocationManager,
    private val productDir: File,
    private val dumpDir: File,
    private val onMessage: (String) -> Unit,
    private val onResult: (LivePppResult) -> Unit
) {
    private val tag = "LivePppController"
    @Volatile private var running = false // 多线程可见的会话开关，不等于定位成功。
    @Volatile private var initialized = false // 本次会话产品/原生状态是否初始化成功。
    private var thread: HandlerThread? = null
    private var callback: GnssMeasurementsEvent.Callback? = null
    private var obsDump: File? = null

    private fun show(message: String) {
        activity.runOnUiThread { if (running) onMessage(message) }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        if (Build.VERSION.SDK_INT < 29) {
            onMessage("实时 PPP 需要 Android 10+ 的 GnssMeasurement CodeType")
            return false
        }
        // 独立 Looper 串行处理事件，避免解算占用界面线程，也避免同一滤波器并行处理历元。
        // 队列排队不会变成固定的 1 秒输入；输入频率仍由设备事件决定。
        val worker = HandlerThread("PPP-live-worker").apply { start() }
        val handler = Handler(worker.looper)
        val listener = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                if (!running) return
                try {
                    // 用第一个事件的 Clock 选择产品，不用手机 Date() 替代 GNSS 时间。
                    // 此分支成功后整次会话跳过，不在每个 event 重载文件。
                    if (!initialized) {
                        show("正在载入本地 PPP 产品…")
                        BundledProducts.ensure(activity.applicationContext, productDir) {
                            Log.i(tag, it)
                        }
                        val paths = LivePppProducts.resolve(productDir, event.clock)
                        if (!running) return
                        if (!LivePppNative.nativeInit(paths)) {
                            val message = "PPP 初始化失败：${LivePppNative.nativeLastError()}"
                            activity.runOnUiThread {
                                stop()
                                onMessage(message)
                            }
                            return
                        }
                        if (!running) {
                            LivePppNative.nativeRelease()
                            return
                        }
                        initialized = true
                        dumpDir.mkdirs()
                        obsDump = File(dumpDir, "ppp_live_obs_${System.currentTimeMillis()}.csv")
                        obsDump?.writeText("week,tow,sat,signal,slot,P_m,L_cycle,D_Hz,CN0_dBHz,LLI,code\n")
                        Log.i(tag, "PPP ready; OBS dump=${obsDump?.absolutePath}")
                    }
                    // 传入同一事件的公共 Clock 和完整测量集合；不要逐 measurement 调用此函数。
                    val values = LivePppNative.nativeProcessEpoch(
                        event.clock, event.measurements.toTypedArray()
                    )
                    if (values == null) {
                        val error = LivePppNative.nativeLastError()
                        Log.w(tag, "Epoch skipped: $error")
                        if (error.contains("覆盖范围")) {
                            activity.runOnUiThread {
                                stop()
                                onMessage("PPP 产品已过期：$error")
                            }
                        }
                        return
                    }
                    // 记录 adapter 转换后的观测，与 PC --obs-dump 的列和精度一致。
                    // 它是 PPP 输入，不是 Raw，不是最终定位坐标，也不等于每条观测已被滤波接受。
                    obsDump?.appendText(LivePppNative.nativeObsRows())
                    val result = LivePppResult.fromNative(values)
                    activity.runOnUiThread { if (running) onResult(result) }
                } catch (error: Exception) {
                    Log.e(tag, "Live PPP event failed", error)
                    if (!initialized) {
                        activity.runOnUiThread {
                            stop()
                            onMessage("PPP 初始化失败：${error.message}")
                        }
                    } else {
                        show("实时 PPP 出错：${error.message}")
                    }
                }
            }
        }
        running = true
        // Android 12+ 请求完整跟踪，Executor 只负责投递到 handler，实际处理在 worker 上。
        // Android 10/11 使用带 Handler 的旧注册接口；注册成功不等于保证每秒都有相位。
        val registered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = android.location.GnssMeasurementRequest.Builder()
                    .setFullTracking(true).build()
                locationManager.registerGnssMeasurementsCallback(
                    request, { command -> handler.post(command) }, listener
                )
            } else {
                locationManager.registerGnssMeasurementsCallback(listener, handler)
            }
        } catch (error: SecurityException) {
            Log.e(tag, "GNSS permission lost", error)
            false
        }
        if (!registered) {
            running = false
            worker.quitSafely()
            onMessage("无法注册 GnssMeasurementsEvent.Callback")
            return false
        }
        thread = worker
        callback = listener
        onMessage("等待 GnssMeasurementsEvent…")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        callback?.let(locationManager::unregisterGnssMeasurementsCallback)
        callback = null
        // 原生互斥锁等待正在处理的历元结束，然后释放状态；quitSafely 结束工作线程。
        // 再次“启动实时 PPP”属于新会话，会重新初始化；本次会话内不每秒初始化。
        if (initialized) LivePppNative.nativeRelease()
        initialized = false
        thread?.quitSafely()
        thread = null
        onMessage("实时 PPP 已停止")
    }

    fun isRunning(): Boolean = running
}
