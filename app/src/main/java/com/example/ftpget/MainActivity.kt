package com.example.ftpget // ★ 务必确保这里的包名和你的项目一致 ★

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.Chronometer
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 主界面/业务入口：组织权限、卫星显示、TXT 记录、RINEX 转换、本地产品和实时会话。
 * 阅读数据链路时先看 startGnssRecording / stopGnssRecording / convertRawToRinex。
 * 本类只负责调度，不把界面显示的 Android Location 当作 PPP 的参考真值。
 */
class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity_PPP"
    private val rawFileNamePattern = Regex(
        "^RawData_\\d{8}_\\d{6}\\.txt$", RegexOption.IGNORE_CASE
    )

    // 原始 TXT 记录器；不是 RINEX 直出引擎，RINEX 由 RinexConverter 单独生成。
    private lateinit var gnssLogger: GnssDataLogger

    private lateinit var chronometer: Chronometer
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnConvert: Button
    private lateinit var tvFilePath: TextView

    private lateinit var btnDownload: Button
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var tvDownloadStage: TextView
    private lateinit var tvDownloadPercent: TextView
    private lateinit var tvDownloadFile: TextView
    private lateinit var tvDownloadDate: TextView
    private lateinit var tvCaptureMessage: TextView

    private lateinit var btnRunPpp: Button
    private lateinit var btnLivePpp: Button
    private lateinit var tvPppResult: TextView
    private var livePppController: LivePppController? = null
    private lateinit var tabNavigation: TabLayout
    private lateinit var pages: List<View>
    private lateinit var skyPlotView: SkyPlotView
    private lateinit var signalBarView: SignalBarView
    private lateinit var satelliteTable: GnssDataTableView
    private lateinit var tvSatelliteSummary: TextView
    private lateinit var measurementTable: GnssDataTableView
    private lateinit var tvMeasurementSummary: TextView
    private lateinit var tvHeaderStatus: TextView
    private lateinit var tvDevicePosition: TextView
    private lateinit var tvMapStatus: TextView
    private lateinit var mapWebView: WebView
    private lateinit var btnRefreshMap: Button
    private lateinit var locationManager: LocationManager
    private var lastLocation: Location? = null
    private var gnssUiRegistered = false
    private var lastMeasurementRefresh = 0L
    private var lastMapPosition: Pair<Double, Double>? = null

    // 只用于显示的回调，与 TXT 采集、实时 JNI 回调分开。
    // 下面每 1000 ms 刷新且只显示 15 条不代表只采集 15 条，也不会给采集数据降采样。
    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMeasurementRefresh < 1000L || !::measurementTable.isInitialized) return
            lastMeasurementRefresh = now
            val measurements = event.measurements.sortedWith(
                compareBy({ satelliteSystem(it.constellationType) }, { it.svid })
            ).take(15)
            val rows = measurements.map { m ->
                val system = satelliteSystem(m.constellationType)
                val prn = "${satelliteSystem(m.constellationType)}%02d".format(Locale.US, m.svid)
                val adr = if (m.accumulatedDeltaRangeState and
                    android.location.GnssMeasurement.ADR_STATE_VALID != 0) {
                    "%.1f".format(Locale.US, m.accumulatedDeltaRangeMeters)
                } else "—"
                val mhz = if (m.hasCarrierFrequencyHz()) "%.1f".format(
                    Locale.US, m.carrierFrequencyHz / 1e6
                ) else "—"
                GnssTableRow(
                    listOf(
                        prn,
                        (m.receivedSvTimeNanos % 100_000_000_000L).toString(),
                        adr,
                        "%.1f".format(Locale.US, m.cn0DbHz),
                        mhz
                    ),
                    system = system
                )
            }
            measurementTable.updateRows(rows, "等待原始观测…")
            tvMeasurementSummary.text = "${event.measurements.size} 条 · 显示 ${rows.size} 条"
        }
    }

    // GnssStatus 提供方位角/高度角/系统导航 usedInFix；后者不是 RTKLIB PPP 是否采用此卫星。
    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            if (!::skyPlotView.isInitialized) return
            val items = (0 until status.satelliteCount).map { index ->
                SatelliteDisplay(
                    satelliteSystem(status.getConstellationType(index)),
                    status.getSvid(index), status.getCn0DbHz(index),
                    status.getElevationDegrees(index), status.getAzimuthDegrees(index),
                    status.usedInFix(index)
                )
            }
            skyPlotView.updateSatellites(items)
            signalBarView.updateSatellites(items)
            tvSatelliteSummary.text = "可见 ${items.size} 颗 · 用于定位 ${items.count { it.usedInFix }} 颗"
            tvHeaderStatus.text = "GNSS 在线 · ${items.size} 颗可见卫星"
            satelliteTable.updateRows(
                items.sortedWith(compareBy({ it.system }, { it.svid })).map { s ->
                    GnssTableRow(
                        listOf(
                            "%s%02d".format(Locale.US, s.system, s.svid),
                            "%.1f".format(Locale.US, s.cn0),
                            "%.1f°".format(Locale.US, s.elevation),
                            "%.1f°".format(Locale.US, s.azimuth),
                            if (s.usedInFix) "● 已用" else "—"
                        ),
                        system = s.system,
                        usedInFix = s.usedInFix
                    )
                },
                "等待卫星状态…"
            )
        }
    }

    private val uiLocationListener = LocationListener { location ->
        updateDeviceLocation(location)
    }

    private val visualPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) registerGnssUi()
        else tvHeaderStatus.text = "需要定位权限才能显示卫星与地图"
    }

    private val livePppPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startLivePpp()
        else tvPppResult.text = "实时 PPP 需要精确定位权限"
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startGnssRecording()
            registerGnssUi()
        } else {
            Toast.makeText(this, "必须授予位置权限才能采集卫星数据！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 1. 初始化 UI 控件
        initViews()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 2. 初始化全新的后台极速采集引擎（注意变量名改为 gnssLogger）
        gnssLogger = GnssDataLogger(this)

        // 3. 设置各个按钮的点击事件监听
        setupListeners()
    }

    private fun initViews() {
        chronometer = findViewById(R.id.chronometer)
        tvStatus = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnConvert = findViewById(R.id.btnConvert)
        tvFilePath = findViewById(R.id.tvFilePath)

        btnDownload = findViewById(R.id.btnDownload)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        tvDownloadStage = findViewById(R.id.tvDownloadStage)
        tvDownloadPercent = findViewById(R.id.tvDownloadPercent)
        tvDownloadFile = findViewById(R.id.tvDownloadFile)
        tvDownloadDate = findViewById(R.id.tvDownloadDate)
        tvCaptureMessage = findViewById(R.id.tvCaptureMessage)
        updateDownloadDate(0)

        btnRunPpp = findViewById(R.id.btnRunPpp)
        btnLivePpp = findViewById(R.id.btnLivePpp)
        tvPppResult = findViewById(R.id.tvPppResult)
        tabNavigation = findViewById(R.id.tabNavigation)
        pages = listOf(R.id.pageLog, R.id.pageView, R.id.pagePos, R.id.pageMap)
            .map { findViewById(it) }
        skyPlotView = findViewById(R.id.skyPlotView)
        signalBarView = findViewById(R.id.signalBarView)
        satelliteTable = findViewById(R.id.satelliteTable)
        tvSatelliteSummary = findViewById(R.id.tvSatelliteSummary)
        measurementTable = findViewById(R.id.measurementTable)
        tvMeasurementSummary = findViewById(R.id.tvMeasurementSummary)
        measurementTable.configure(listOf(
            GnssTableColumn("PRN", 56, Gravity.START),
            GnssTableColumn("SV 时间(ns)", 125),
            GnssTableColumn("ADR(m)", 88),
            GnssTableColumn("C/N₀", 64),
            GnssTableColumn("MHz", 70)
        ))
        satelliteTable.configure(listOf(
            GnssTableColumn("PRN", 56, Gravity.START),
            GnssTableColumn("C/N₀", 64),
            GnssTableColumn("高度角", 72),
            GnssTableColumn("方位角", 72),
            GnssTableColumn("定位", 64, Gravity.CENTER_HORIZONTAL)
        ))
        tvHeaderStatus = findViewById(R.id.tvHeaderStatus)
        tvDevicePosition = findViewById(R.id.tvDevicePosition)
        tvMapStatus = findViewById(R.id.tvMapStatus)
        mapWebView = findViewById(R.id.mapWebView)
        btnRefreshMap = findViewById(R.id.btnRefreshMap)
        mapWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    tvMapStatus.text = "地图暂时无法加载，请检查网络；设备坐标仍可在 POS 页面查看。"
                }
            }
        }
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.allowFileAccess = false
        mapWebView.settings.allowContentAccess = false

        listOf("LOG", "VIEW", "POS", "MAP").forEach { tabNavigation.addTab(tabNavigation.newTab().setText(it)) }
        tabNavigation.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                pages.forEachIndexed { index, page ->
                    page.visibility = if (index == tab.position) View.VISIBLE else View.GONE
                }
                if (tab.position > 0 && ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED) {
                    visualPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                } else if (tab.position == 3) refreshMap()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        updateRecordButtons(false)
    }

    private fun setupListeners() {
        btnStart.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                startGnssRecording()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        btnStop.setOnClickListener { stopGnssRecording() }
        btnConvert.setOnClickListener { convertRawToRinex() }
        btnDownload.setOnClickListener { startFtpDownload() } // 保持之前的FTP下载不变
        btnRunPpp.setOnClickListener { startPppProcessing() }
        btnLivePpp.setOnClickListener {
            if (livePppController?.isRunning() == true) stopLivePpp()
            else if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) startLivePpp()
            else livePppPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        btnRefreshMap.setOnClickListener { refreshMap(force = true) }
        findViewById<Button>(R.id.btnOpenMap).setOnClickListener { openExternalMap() }
    }

    override fun onResume() {
        super.onResume()
        mapWebView.onResume()
        registerGnssUi()
    }

    override fun onPause() {
        if (gnssUiRegistered) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
            locationManager.removeUpdates(uiLocationListener)
            gnssUiRegistered = false
        }
        mapWebView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        stopLivePpp()
        super.onDestroy()
    }

    // 实时入口直接订阅 GnssMeasurementsEvent，不读取 TXT/RINEX，不要求先启动 TXT 记录。
    // 同时启动记录可留下原始 TXT 供 PC 回放；两条回调不能互相替代。
    private fun startLivePpp() {
        val products = getExternalFilesDir("GNSS_Products")
        if (products == null) {
            tvPppResult.text = "找不到本地 GNSS_Products 目录"
            return
        }
        val output = getExternalFilesDir("GNSS_Live") ?: File(filesDir, "GNSS_Live")
        val controller = LivePppController(
            this, locationManager, products, output,
            onMessage = {
                tvPppResult.text = it
                if (it.contains("已停止") || it.startsWith("PPP 初始化失败") ||
                    it.startsWith("PPP 产品已过期")) btnLivePpp.text = "启动实时 PPP"
            },
            onResult = { result ->
                tvPppResult.text = if (result.q == 0) {
                    "GPST  %d / %.3f\nQ  0\n卫星数  %d\nlatitude  —\nlongitude  —\nheight  —\nprocessing  %.1f ms".format(
                        Locale.US, result.week, result.tow, result.satellites,
                        result.processingMs
                    )
                } else {
                    "GPST  %d / %.3f\nQ  %d\n卫星数  %d\nlatitude  %.9f°\nlongitude  %.9f°\nheight  %.3f m\nprocessing  %.1f ms".format(
                        Locale.US, result.week, result.tow, result.q,
                        result.satellites, result.latitude, result.longitude,
                        result.height, result.processingMs
                    )
                }
            }
        )
        if (controller.start()) {
            livePppController = controller
            btnLivePpp.text = "停止实时 PPP"
        }
    }

    private fun stopLivePpp() {
        livePppController?.stop()
        livePppController = null
        if (::btnLivePpp.isInitialized) btnLivePpp.text = "启动实时 PPP"
    }

    @SuppressLint("MissingPermission")
    private fun registerGnssUi() {
        if (gnssUiRegistered || !::locationManager.isInitialized ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, null)
            locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, null)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, uiLocationListener
            )
            gnssUiRegistered = true
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.let(::updateDeviceLocation)
        } catch (e: Exception) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback)
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
            locationManager.removeUpdates(uiLocationListener)
            Log.w(TAG, "GNSS UI callback unavailable", e)
            tvHeaderStatus.text = "GNSS 状态暂不可用"
        }
    }

    // 此处是 Android 系统定位坐标和 accuracy，不是 LivePppResult，也不是实测误差真值。
    private fun updateDeviceLocation(location: Location) {
        lastLocation = location
        tvDevicePosition.text = "纬度  %.6f°\n经度  %.6f°\n高度  %.1f m\n水平精度  ±%.1f m".format(
            Locale.US, location.latitude, location.longitude, location.altitude,
            location.accuracy
        )
        tvMapStatus.text = "设备位置  %.6f°, %.6f° · 精度 ±%.1f m".format(
            Locale.US, location.latitude, location.longitude, location.accuracy
        )
        if (tabNavigation.selectedTabPosition == 3 && lastMapPosition == null) refreshMap()
    }

    @SuppressLint("MissingPermission")
    private fun refreshMap(force: Boolean = false) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            tvMapStatus.text = "请先授予定位权限，再查看地图。"
            return
        }
        val location = lastLocation ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        if (location == null) {
            tvMapStatus.text = "正在等待 GPS 定位，请到室外后刷新。"
            return
        }
        val position = location.latitude to location.longitude
        if (!force && lastMapPosition == position) return
        lastMapPosition = position
        val lat = location.latitude
        val lon = location.longitude
        val bbox = "%.5f,%.5f,%.5f,%.5f".format(
            Locale.US, lon - 0.006, lat - 0.004, lon + 0.006, lat + 0.004
        )
        val url = "https://www.openstreetmap.org/export/embed.html?bbox=" +
            android.net.Uri.encode(bbox) + "&layer=mapnik&marker=" +
            "%.6f".format(Locale.US, lat) + "%2C" + "%.6f".format(Locale.US, lon)
        mapWebView.loadUrl(url)
    }

    private fun openExternalMap() {
        val location = lastLocation
        if (location == null) {
            showToast("暂无设备位置，请先等待 GPS 定位")
            return
        }
        val lat = location.latitude
        val lon = location.longitude
        val uri = android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            showToast("手机上未找到可打开的地图应用")
        }
    }

    /** 启动 TXT 记录；屏幕常亮只作用于当前窗口，不等于后台定位服务或硬件 WakeLock。 */
    private fun startGnssRecording() {
        // Logger 请求定位/完整跟踪，实际回调频率、载波连续性仍需检查文件。
        if (gnssLogger.startRecording()) {
            chronometer.base = SystemClock.elapsedRealtime()
            chronometer.start()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStatus.text = "🟢 静默采集中 (数据纯后台写入本地)..."
            tvCaptureMessage.text = "正在记录原始观测数据…"
            updateRecordButtons(true)
        } else {
            Toast.makeText(this, "启动采集失败，请检查定位开关或权限", Toast.LENGTH_SHORT).show()
        }
    }

    // stopRecording 要等待队列排空，先在 IO 线程完成写盘，再在主线程更新“已保存”。
    private fun stopGnssRecording() {
        tvStatus.text = "🔄 正在完成原始数据写入..."
        lifecycleScope.launch {
            val savedFile = withContext(Dispatchers.IO) { gnssLogger.stopRecording() }
            chronometer.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStatus.text = "🔴 采集已停止"
            updateRecordButtons(false)

            if (savedFile != null) {
                tvFilePath.text = "已保存: ${savedFile.name}"
                tvCaptureMessage.text = if (gnssLogger.droppedMeasurementCount > 0) {
                    "采集已保存；丢失 ${gnssLogger.droppedMeasurementCount} 条观测，建议重新采集。"
                } else "采集已保存，可转换为 RINEX。"
            }
        }
    }

    /** 选择目录内文件名日期最新的非空 Raw TXT，离线生成 OBS；不是读取当前实时内存观测。 */
    private fun convertRawToRinex() {
        /*
         * currentSaveFile only survives while this Activity/logger instance is
         * alive. After restarting the app it is null even though previous raw
         * files are safely stored on disk. Select the newest timestamped
         * capture from the common GNSS_Products directory instead.
         */
        val inputFile = findNewestRawObservationFile()
        if (inputFile == null) {
            Toast.makeText(this, "未找到 RawData_*.txt 采集文件", Toast.LENGTH_SHORT).show()
            return
        }

        btnConvert.isEnabled = false
        tvStatus.text = "🔄 正在向 RINEX 格式转换..."
        tvCaptureMessage.text = "正在转换 ${inputFile.name}…"

        // 🌟 核心修复：从原始文件名中提取精确的“采集开始时间”，而不是用文件结束修改时间
        // 假设原始文件名为: RawData_20260724_111812.txt
        val fileName = inputFile.name
        // 以下 UTC 仅用于生成输出文件名；文件内观测历元由 GnssClock 转为 GPST。
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))

        try {
            // 2. 截取文件名中的时间字符串部分，例如 "20260725_111812"
            val timeString = fileName.substringAfter("RawData_").substringBefore(".txt")
            val sdf = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)

            // ★ 核心 1：明确告知解析器，RawData 文件名是你手机在本地时区生成的
            sdf.timeZone = java.util.TimeZone.getDefault()

            // 解析出来的 date 本身就是没有时区概念的“绝对时间戳”
            val date = sdf.parse(timeString)
            if (date != null) {
                // ★ 核心 2：直接塞进 UTC 日历！系统会自动完美对齐，绝不能手动去减 Offset！
                cal.time = date
            } else {
                cal.timeInMillis = inputFile.lastModified() // 解析失败的保底方案
            }
        } catch (e: Exception) {
            cal.timeInMillis = inputFile.lastModified() // 解析失败的保底方案
        }

        // 提取命名用 UTC 年/年积日/时/分；系统时间或文件名错误仍会影响名字，不影响 Clock 转换公式。
        val year = cal.get(Calendar.YEAR)
        val doy = cal.get(Calendar.DAY_OF_YEAR)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val min = cal.get(Calendar.MINUTE)

        // 命名字段：站名_来源_年+年积日+时分_未知时长(00U)_标称间隔(01S)_混合观测(MO)。
        // 01S 是命名标识，不证明文件没有丢历元；实际产品覆盖应按文件内 GPST 历元核对。
        val outputFileName = String.format(
            java.util.Locale.US,
            "GEOL00CHN_R_%04d%03d%02d%02d_00U_01S_MO.rnx",
            year, doy, hour, min
        )
        // ✅ 正确的代码 (Activity 写法)
        val outputFile = File(getExternalFilesDir("GNSS_Products"), outputFileName)

        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                // 当前 Logger 输出固定 54 列；导入外部 TXT 必须另核对列顺序/缺失字段，不能只看扩展名。
                RinexConverter.convert(inputFile, outputFile)
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                btnConvert.isEnabled = true
                if (success) {
                    val qc = RinexConverter.lastStats
                    tvStatus.text = "✅ 转换成功！"
                    tvFilePath.text = "RINEX文件: ${outputFile.name}"
                    tvCaptureMessage.text = "转换成功：码 ${qc.codeAccepted}、相位 ${qc.phaseAccepted}" +
                        if (qc.phaseAccepted == 0) "；无可用相位，不能用于高精度 PPP。"
                        else "；可继续准备精密产品。"
                } else {
                    tvStatus.text = "❌ 转换失败，请检查原始数据"
                    tvCaptureMessage.text = "RINEX 转换失败，请检查原始数据。"
                }
            }
        }
    }

    /**
     * 文件名 RawData_yyyyMMdd_HHmmss.txt 的固定宽度日期可按字符串排序，重启应用也能选择。
     * 只扫描 GNSS_Products 第一层，不按最后修改时间排序，不限定必须是今天。
     * 跨时区/手动改系统时钟可能影响“最新”顺序；当前没有任意历史 TXT 文件选择窗口。
     */
    private fun findNewestRawObservationFile(): File? {
        val dir = getExternalFilesDir("GNSS_Products") ?: return null
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && rawFileNamePattern.matches(it.name) && it.length() > 0L }
            ?.maxByOrNull { it.name.uppercase(Locale.US) }
    }

    private fun startFtpDownload() {
        btnDownload.isEnabled = false
        updateDownloadStage("正在准备下载")
        updateDownloadDate(0)

        val localDir = getExternalFilesDir("GNSS_Products")
        if (localDir == null || (!localDir.exists() && !localDir.mkdirs())) {
            tvDownloadStage.text = "无法创建下载目录"
            tvDownloadPercent.text = "失败"
            tvDownloadFile.text = "请检查设备存储空间后重试"
            downloadProgressBar.isIndeterminate = false
            btnDownload.isEnabled = true
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val ftpServer = "igs.gnsswhu.cn"
            var preciseProductsReady = false // 专门标记精密产品是否找齐
            var biasReady = false
            var vmfReady = false
            var orographyReady = false
            var ionoReady = false
            var atxReady = false
            var preciseFailure = ""
            try {
                BundledProducts.ensure(applicationContext, localDir!!, ::appendLog)
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    downloadProgressBar.isIndeterminate = false
                    tvDownloadStage.text = "静态产品准备失败"
                    tvDownloadPercent.text = "失败"
                    tvDownloadFile.text = error.message ?: "请检查本地 ATX/格网高程文件"
                    btnDownload.isEnabled = true
                }
                return@launch
            }
            // 保持原有日期与回退规则；只撤销“本地已下载则跳过”的判断。
            for (daysAgo in 0..3) {
                updateDownloadDate(daysAgo)
                val timeParams = GpsTimeUtil.getIgsV3TimeParams(daysAgo)
                val yearStr = timeParams.year.toString()
                val doyStr = timeParams.doy

                appendLog("\n--- 检索回退 $daysAgo 天 ($yearStr 年, 第 $doyStr 天) 的精密数据 ---")

                val productCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                productCal.add(Calendar.DAY_OF_YEAR, -daysAgo - 1)
                val productTimeParams = GpsTimeUtil.getIgsV3TimeParams(daysAgo + 1)
                val productStartDate = String.format(
                    Locale.US, "%04d%03d",
                    productCal.get(Calendar.YEAR),
                    productCal.get(Calendar.DAY_OF_YEAR)
                )
                val mgexFolder = "/pub/gnss/products/mgex/${productTimeParams.week}"
                val biasFolder = "/pub/whu/phasebias/$yearStr/bias/"

                appendLog("👉 访问 WHU MGEX NRT 目录: $mgexFolder")
                appendLog("👉 目标 NRT 起算日: $productStartDate (覆盖 $yearStr/$doyStr)")
                appendLog("👉 访问偏差目录: $biasFolder")

                updateDownloadStage("正在获取精密轨道 · 第 ${daysAgo + 1} 次检索")
                val sp3Ok = FtpDownloader.downloadWhuNrtProduct(
                    ftpServer, mgexFolder, productStartDate, "ORB.SP3", localDir!!,
                    onTransferProgress = ::updateTransferProgress
                ) { msg ->
                    appendLog(msg)
                    if (msg.startsWith("❌") || msg.startsWith("⚠️")) preciseFailure = "SP3：$msg"
                }

                updateDownloadStage("正在获取精密钟差 · 第 ${daysAgo + 1} 次检索")
                val clkOk = FtpDownloader.downloadWhuNrtProduct(
                    ftpServer, mgexFolder, productStartDate, "CLK.CLK", localDir,
                    onTransferProgress = ::updateTransferProgress
                ) { msg ->
                    appendLog(msg)
                    if (msg.startsWith("❌") || msg.startsWith("⚠️")) preciseFailure = "CLK：$msg"
                }

                if (sp3Ok && clkOk) {
                    appendLog("\n👉 核心数据已就位！开始获取对应日期大气模型...")
                    updateDownloadStage("正在获取相位偏差")
                    biasReady = FtpDownloader.downloadLatestProduct(
                        ftpServer, biasFolder, yearStr, doyStr, "BIA", localDir,
                        onTransferProgress = ::updateTransferProgress
                    ) { msg -> appendLog(msg) }

                    val targetCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    targetCal.add(Calendar.DAY_OF_YEAR, -daysAgo)

                    // 从 Git 忽略的 vmf3.local.properties 生成，不再把个人凭据写入版本库。
                    // 未配置时为空，保持原有“跳过 VMF3 下载”逻辑；APK 内仍可提取，非生产密钥保护方案。
                    val vmfUsername = BuildConfig.VMF3_USERNAME
                    val vmfPassword = BuildConfig.VMF3_PASSWORD

                    if (vmfUsername.isBlank() || vmfPassword.isBlank()) {
                        appendLog("⚠️ 未配置 VMF3 账号密码，跳过对流层预报下载。")
                    } else {
                        updateDownloadStage("正在获取对流层格网")
                        vmfReady = FtpDownloader.downloadVmf3ForecastProduct(
                            year = targetCal.get(Calendar.YEAR),
                            month = targetCal.get(Calendar.MONTH) + 1,
                            day = targetCal.get(Calendar.DAY_OF_MONTH),
                            username = vmfUsername,
                            password = vmfPassword,
                            savePath = localDir,
                            onTransferProgress = ::updateTransferProgress
                        ) { msg -> appendLog(msg) }

                        if (!vmfReady) {
                            appendLog("⚠️ VMF3 对流层预报获取失败。")
                        } else {
                            updateDownloadStage("正在获取格网高程")
                            orographyReady = FtpDownloader.downloadVmf3Orography(
                                localDir, onTransferProgress = ::updateTransferProgress
                            ) { msg -> appendLog(msg) }
                            if (!orographyReady) {
                                appendLog("⚠️ VMF3 格网高程获取失败，将不进行测站高度改正。")
                            }
                        }
                    }

                    updateDownloadStage("正在获取电离层文件")
                    ionoReady = FtpDownloader.downloadIonexProduct(
                        targetCal.get(Calendar.YEAR),
                        targetCal.get(Calendar.MONTH) + 1,
                        targetCal.get(Calendar.DAY_OF_MONTH),
                        localDir,
                        onTransferProgress = ::updateTransferProgress
                    ) { msg -> appendLog(msg) }

                    if (!ionoReady) {
                        appendLog("⚠️ 电离层文件获取失败，算法将自动降级使用广播星历 Klobuchar 模型。")
                    }

                    preciseProductsReady = true
                    appendLog("🎯 第 $doyStr 天核心精密数据与大气模型找齐，停止回退！")
                    break
                } else {
                    appendLog("⚠️ $daysAgo 天前的精密产品未找齐，继续往前倒推...")
                }
            }

            var navOk = false
            if (preciseProductsReady) {
                appendLog("\n👉 开始获取公共文件 (ATX 与 广播星历)...")

                updateDownloadStage("正在获取天线改正文件")
                atxReady = FtpDownloader.downloadAtxProduct(
                    localDir!!, onTransferProgress = ::updateTransferProgress
                ) { msg -> appendLog(msg) }
                if (!atxReady) appendLog("⚠️ ATX 天线文件获取失败。")

                // 广播星历始终尝试拉取当天的
                val todayTimeParams = GpsTimeUtil.getIgsV3TimeParams(0)
                val todayYearStr = String.format(Locale.US, "%04d", todayTimeParams.year)
                val todayDoyStr = String.format(Locale.US, "%03d", todayTimeParams.doy.toInt())
                val bkgFtpServer = "igs-ftp.bkg.bund.de"
                val bkgFolder = "/IGS/BRDC/$todayYearStr/$todayDoyStr/"

                appendLog("👉 启动跨国通道：直连 $bkgFtpServer 获取【当天】广播星历...")
                updateDownloadStage("正在获取今日广播星历")
                navOk = FtpDownloader.downloadNavProduct(
                    bkgFtpServer, bkgFolder, localDir,
                    onTransferProgress = ::updateTransferProgress
                ) { msg -> appendLog(msg) }

                if (!navOk) appendLog("⚠️ 当天广播星历获取失败。")
            }

            // ==========================================
            // 阶段 3：UI 线程更新最终结果
            // ==========================================
            withContext(Dispatchers.Main) {
                downloadProgressBar.isIndeterminate = false
                downloadProgressBar.progress = if (preciseProductsReady) 100 else 0
                tvDownloadStage.text = if (preciseProductsReady) "下载完成" else "核心文件缺失"
                tvDownloadPercent.text = if (preciseProductsReady) "完成" else "失败"
                val missing = mutableListOf<String>()
                if (!biasReady) missing += "偏差"
                if (!vmfReady) missing += "对流层"
                if (!orographyReady) missing += "格网高程"
                if (!ionoReady) missing += "电离层"
                if (!atxReady) missing += "天线文件"
                if (!navOk) missing += "今日广播星历"
                tvDownloadFile.text = when {
                    !preciseProductsReady -> preciseFailure.ifBlank {
                        "未找到匹配的精密轨道或钟差，请检查网络后重试"
                    }
                    missing.isEmpty() -> "所有精密产品已就绪"
                    else -> "已获取轨道和钟差；缺少：${missing.joinToString("、")}"
                }
                if (preciseProductsReady) {
                    if (navOk) {
                        appendLog("\n🎉 弹药库装填完毕！SP3 + CLK + IONEX + NAV 全部就位。\n文件路径: ${localDir?.absolutePath}")
                    } else {
                        // 精密星历下到了，但广播星历没下到，依然算是核心成功
                        appendLog("\n⚠️ 核心精密产品已就位，但广播星历缺失，部分算法可能受限。\n文件路径: ${localDir?.absolutePath}")
                    }
                } else {
                    appendLog("\n❌ 连续回退 3 天均未找齐核心精密数据 (SP3/CLK)，请检查网络或服务器状态。")
                }
                btnDownload.isEnabled = true
            }
        }
    }

    private fun appendLog(msg: String) {
        Log.i("FtpDownloader", msg)
        runOnUiThread { if (::tvDownloadFile.isInitialized) tvDownloadFile.text = msg }
    }

    private fun updateDownloadDate(daysAgo: Int) {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.add(Calendar.DAY_OF_YEAR, -daysAgo)
        val date = String.format(
            Locale.US, "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        runOnUiThread { tvDownloadDate.text = "目标 $date UTC" }
    }

    private fun updateDownloadStage(stage: String) {
        runOnUiThread {
            tvDownloadStage.text = stage
            tvDownloadPercent.text = "检索中"
            tvDownloadFile.text = "正在查找服务器上的文件…"
            downloadProgressBar.isIndeterminate = true
        }
    }

    private fun updateTransferProgress(fileName: String, downloaded: Long, total: Long) {
        runOnUiThread {
            tvDownloadFile.text = if (total > 0L) {
                "$fileName  ·  ${formatDownloadBytes(downloaded)} / ${formatDownloadBytes(total)}"
            } else {
                "$fileName  ·  已下载 ${formatDownloadBytes(downloaded)}"
            }
            if (total > 0L) {
                val percent = (downloaded * 100L / total).coerceIn(0L, 100L).toInt()
                downloadProgressBar.isIndeterminate = false
                downloadProgressBar.progress = percent
                tvDownloadPercent.text = "$percent%"
            } else {
                downloadProgressBar.isIndeterminate = true
                tvDownloadPercent.text = "下载中"
            }
        }
    }

    private fun formatDownloadBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun updateRecordButtons(isRecording: Boolean) {
        btnStart.isEnabled = !isRecording
        btnStop.isEnabled = isRecording
    }

    private fun startPppProcessing() {
        val dir = getExternalFilesDir("GNSS_Products")
        if (dir == null || !dir.exists()) {
            showToast("未找到工作目录，请先进行数据准备！")
            return
        }

        btnRunPpp.isEnabled = false
        tvPppResult.text = "🔍 正在扫描工作目录..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                BundledProducts.ensure(applicationContext, dir)
                val inputs = PostPppProducts.resolve(dir)
                val outputFile = File(dir, "PPP_Result_${System.currentTimeMillis()}.pos")
                val traceFile = File(outputFile.absolutePath + ".trace")
                withContext(Dispatchers.Main) {
                    tvPppResult.text = "使用 PC 修改版内核后处理：\n" +
                        "OBS  ${inputs.obs.name}\nNAV  ${inputs.nav.name}\n" +
                        "SP3  ${inputs.sp3.name}\nCLK  ${inputs.clk.name}\n" +
                        "BIA  ${inputs.bia.name}\nIONEX  ${inputs.ionex.name}\n" +
                        "VMF3  ${inputs.vmfLeft.name} + ${inputs.vmfRight.name}\n" +
                        "正在解算…"
                }

                val args = inputs.nativeArgs(outputFile)

                val startTime = System.currentTimeMillis()
                val resultCode = RtkEngine.runPpp(args)
                val costTime = System.currentTimeMillis() - startTime

                withContext(Dispatchers.Main) {
                    if (resultCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                        tvPppResult.text = "后处理文件已生成，耗时: ${costTime}ms\n"
                        readAndShowCoordinates(outputFile)
                    } else {
                        val cause = when (resultCode) {
                            -1 -> "JNI 参数或路径过长"
                            -2 -> "输入产品缺失或为空"
                            -3 -> "VMF3 文件对读取失败"
                            -4 -> "RTKLIB postpos 解算失败"
                            else -> "未知错误"
                        }
                        var crashReason = "引擎返回码: $resultCode（$cause）\n耗时: ${costTime}ms\n"
                        if (traceFile.exists() && traceFile.length() > 0) {
                            crashReason += "\n📜 底层 Trace 报错分析：\n-----------------\n${traceFile.readText().takeLast(2000)}"
                        } else {
                            crashReason += "\n❌ 未发现 Trace 文件。"
                        }

                        tvPppResult.text = "解算失败，请看弹窗报告！"
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("🚨 引擎罢工诊断报告")
                            .setMessage(crashReason)
                            .setCancelable(false)
                            .setPositiveButton("关闭") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    btnRunPpp.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvPppResult.text = "❌ 发生异常: ${e.message}"
                    btnRunPpp.isEnabled = true
                }
            }
        }
    }

    private fun readAndShowCoordinates(posFile: File) {
        try {
            var total = 0
            var q6 = 0
            var last: List<String>? = null
            posFile.forEachLine { line ->
                if (!line.startsWith("%") && line.isNotBlank()) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 6 && parts[5].toIntOrNull() != null) {
                        total++
                        if (parts[5] == "6") q6++
                        last = parts
                    }
                }
            }
            val resultStr = last?.let { parts ->
                "历元 $total，Q6 $q6\n末历元：纬度 ${parts[2]}，经度 ${parts[3]}，" +
                    "高程 ${parts[4]} m，Q=${parts[5]}"
            } ?: "结果文件没有可解析的定位历元；请查看 .pos.trace"
            tvPppResult.append("\n$resultStr")
            showToast(resultStr, Toast.LENGTH_LONG)
        } catch (e: Exception) {
            Log.e("PPP_Engine", "坐标解析失败", e)
        }
    }

    private fun showToast(msg: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, msg, duration).show()
    }
}
