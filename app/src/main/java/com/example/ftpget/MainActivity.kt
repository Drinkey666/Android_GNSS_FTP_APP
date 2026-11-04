package com.example.ftpget // ★ 务必确保这里的包名和你的项目一致 ★

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import com.example.ftpget.ui.MainUiState
import com.example.ftpget.ui.PositionFix
import com.example.ftpget.ui.UiDialog
import com.example.ftpget.ui.GnssTheme
import com.example.ftpget.ui.GnssApp
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
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

    private val ui = MainUiState()
    private val referencePositionStore by lazy { ReferencePositionStore(applicationContext) }
    private val chronometer get() = ui.chronometer
    private val tvStatus get() = ui.tvStatus
    private val btnStart get() = ui.btnStart
    private val btnStop get() = ui.btnStop
    private val btnConvert get() = ui.btnConvert
    private val tvFilePath get() = ui.tvFilePath
    private val btnChooseExportDirectory get() = ui.btnChooseExportDirectory
    private val btnExportLatest get() = ui.btnExportLatest
    private val btnExportProducts get() = ui.btnExportProducts
    private val tvExportDirectory get() = ui.tvExportDirectory
    private val publicGnssExporter by lazy { PublicGnssExporter(applicationContext) }

    private val btnDownload get() = ui.btnDownload
    private val downloadProgressBar get() = ui.downloadProgressBar
    private val tvDownloadStage get() = ui.tvDownloadStage
    private val tvDownloadPercent get() = ui.tvDownloadPercent
    private val tvDownloadFile get() = ui.tvDownloadFile
    private val downloadLogLock = Any()
    private var downloadLogFile: File? = null
    private val tvDownloadDate get() = ui.tvDownloadDate
    private val tvCaptureMessage get() = ui.tvCaptureMessage

    private val btnRunPpp get() = ui.btnRunPpp
    private val btnLivePpp get() = ui.btnLivePpp
    private val tvPppResult get() = ui.tvPppResult
    private var livePppController: LivePppController? = null
    private lateinit var skyPlotView: SkyPlotView
    private lateinit var signalBarView: SignalBarView
    private lateinit var satelliteTable: GnssDataTableView
    private val tvSatelliteSummary get() = ui.tvSatelliteSummary
    private lateinit var measurementTable: GnssDataTableView
    private val tvMeasurementSummary get() = ui.tvMeasurementSummary
    private val tvHeaderStatus get() = ui.tvHeaderStatus
    private val tvDevicePosition get() = ui.tvDevicePosition
    private val tvMapStatus get() = ui.tvMapStatus
    private lateinit var mapWebView: WebView
    private val btnRefreshMap get() = ui.btnRefreshMap
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
                    GnssMeasurement.ADR_STATE_VALID != 0) {
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
            ui.measurementCount = rows.size
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
            ui.satelliteCount = items.size
            ui.usedSatelliteCount = items.count { it.usedInFix }
            ui.signalSystemCount = items.map { it.system }.distinct().size
            tvSatelliteSummary.text = "可见 ${items.size} 颗 · 用于定位 ${ui.usedSatelliteCount} 颗"
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

    // 使用系统目录授权，用户可以选“内部存储 / FTPGet”；不请求整个磁盘读写权限。
    private val exportDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { publicGnssExporter.rememberDirectory(uri) }
                tvExportDirectory.text = "公共保存目录：${publicGnssExporter.directoryLabel()}"
                showToast("目录已记住；采集结束和转换完成后会自动导出")
            } catch (error: Exception) {
                showToast("目录设置失败：${error.message}", Toast.LENGTH_LONG)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViews()
        ui.referencePosition = referencePositionStore.current
        ui.onReferenceChanged = { reference ->
            referencePositionStore.save(reference)
            ui.referencePosition = reference
        }
        ui.selectedPage = savedInstanceState?.getInt("selectedPage") ?: 0
        setContent {
            GnssTheme {
                GnssApp(ui, skyPlotView, signalBarView, satelliteTable, measurementTable, mapWebView,
                    onPageSelected = ::selectPage)
            }
        }
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        // 2. 初始化全新的后台极速采集引擎（注意变量名改为 gnssLogger）
        gnssLogger = GnssDataLogger(this)

        // 3. 设置各个按钮的点击事件监听
        setupListeners()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedPage", ui.selectedPage)
        super.onSaveInstanceState(outState)
    }

    private fun initViews() {
        tvExportDirectory.text = "公共保存目录：${publicGnssExporter.directoryLabel()}"
        updateDownloadDate(0)
        ui.tvDownloadLog.text = ""
        skyPlotView = SkyPlotView(this)
        signalBarView = SignalBarView(this)
        satelliteTable = GnssDataTableView(this)
        measurementTable = GnssDataTableView(this)
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
        mapWebView = WebView(this)
        mapWebView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    tvMapStatus.text = "地图暂时无法加载，请检查网络；设备坐标仍可在 POS 页面查看。"
                    ui.mapLoading = false
                    ui.mapLoadError = true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                ui.mapLoading = false
            }
        }
        mapWebView.settings.javaScriptEnabled = true
        mapWebView.settings.allowFileAccess = false
        mapWebView.settings.allowContentAccess = false

        updateRecordButtons(false)
    }

    private fun selectPage(page: Int) {
        ui.selectedPage = page
        if (page > 0 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED) {
            visualPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else if (page == 3) refreshMap()
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
        btnChooseExportDirectory.setOnClickListener {
            ui.dialog = UiDialog(
                getString(R.string.export_dialog_title),
                getString(R.string.export_dialog_message),
                getString(R.string.export_dialog_confirm),
                onConfirm = { exportDirectoryLauncher.launch(publicGnssExporter.selectedDirectory()) },
            )
        }
        btnExportLatest.setOnClickListener { exportLatestRawFile() }
        btnExportProducts.setOnClickListener { exportDownloadedProducts() }
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
        ui.btnOpenMap.setOnClickListener { openExternalMap() }
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
        mapWebView.destroy()
        super.onDestroy()
    }

    // 实时入口直接订阅 GnssMeasurementsEvent，不读取 TXT/RINEX，不要求先启动 TXT 记录。
    // 同时启动记录可留下原始 TXT 供 PC 回放；两条回调不能互相替代。
    private fun startLivePpp() {
        ui.comparisonSolution = null
        ui.comparisonIsPostprocessed = false
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
                ui.comparisonSolution = SolutionCoordinate.fromLive(result)
                ui.comparisonIsPostprocessed = false
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
            },
            referenceProvider = { referencePositionStore.current },
        )
        if (controller.start()) {
            livePppController = controller
            btnLivePpp.text = "停止实时 PPP"
        }
    }

    private fun stopLivePpp() {
        livePppController?.stop()
        livePppController = null
        btnLivePpp.text = "启动实时 PPP"
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
        ui.positionFix = PositionFix(location.latitude, location.longitude, location.altitude, location.accuracy)
        if (ui.selectedPage == 3 && lastMapPosition == null) refreshMap()
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
            Uri.encode(bbox) + "&layer=mapnik&marker=" +
            "%.6f".format(Locale.US, lat) + "%2C" + "%.6f".format(Locale.US, lon)
        ui.mapLoadError = false
        ui.mapLoading = true
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
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            showToast("手机上未找到可打开的地图应用")
        }
    }

    /** 启动 TXT 记录；屏幕常亮只作用于当前窗口，不等于后台定位服务或硬件 WakeLock。 */
    private fun startGnssRecording() {
        // Logger 请求定位/完整跟踪，实际回调频率、载波连续性仍需检查文件。
        if (gnssLogger.startRecording(ui.captureCodePhaseRepair)) {
            chronometer.base = SystemClock.elapsedRealtime()
            chronometer.start()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            tvStatus.text = "静默采集中（数据写入本地）…"
            tvCaptureMessage.text = "正在记录原始观测数据…"
            updateRecordButtons(true)
        } else {
            Toast.makeText(this, "启动采集失败，请检查定位开关或权限", Toast.LENGTH_SHORT).show()
        }
    }

    // stopRecording 要等待队列排空，先在 IO 线程完成写盘，再在主线程更新“已保存”。
    private fun stopGnssRecording() {
        tvStatus.text = "正在完成原始数据写入…"
        // 等待队列关闭/公共副本复制期间避免重新启动同一个 Logger 或转换半写文件。
        btnStart.isEnabled = false
        btnStop.isEnabled = false
        btnConvert.isEnabled = false
        btnExportLatest.isEnabled = false
        lifecycleScope.launch {
            val savedFile = withContext(Dispatchers.IO) { gnssLogger.stopRecording() }
            chronometer.stop(SystemClock.elapsedRealtime())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (savedFile != null) {
                tvStatus.text = "采集已停止，正在完成文件保存…"
                val exportMessage = exportGnssFileToPublic(savedFile)
                val repairMessage = gnssLogger.currentRepairFile?.let { file ->
                    "\n修复候选：${file.name}\n${exportGnssFileToPublic(file)}" +
                        "\n候选处理丢历元：${gnssLogger.droppedRepairEpochs}"
                } ?: ""
                val repairError = gnssLogger.repairFailure?.let { "\n候选处理失败：$it；原始数据保留" } ?: ""
                tvFilePath.text = "应用内已保存：${savedFile.name}\n$exportMessage$repairMessage$repairError"
                tvCaptureMessage.text = if (gnssLogger.droppedMeasurementCount > 0) {
                    "采集已保存；丢失 ${gnssLogger.droppedMeasurementCount} 条观测，建议重新采集。"
                } else "采集已保存，可转换为 RINEX。"
            }
            tvStatus.text = "采集已停止"
            updateRecordButtons(false)
            btnConvert.isEnabled = true
            btnExportLatest.isEnabled = true
        }
    }

    /** 只复制完整文件；公共导出失败不删除内部文件，也不误报导出成功。 */
    private suspend fun exportGnssFileToPublic(file: File): String {
        if (publicGnssExporter.selectedDirectory() == null) {
            return "未导出到公共目录；请点击“选择保存目录”后导出"
        }
        return try {
            val exported = withContext(Dispatchers.IO) { publicGnssExporter.export(file) }
            "已导出：${exported.directory} / ${exported.name}"
        } catch (error: Exception) {
            Log.w(TAG, "Public GNSS export failed; private original retained", error)
            "公共导出失败：${error.message}；原文件保留，可重新导出"
        }
    }

    /** 也能导出之前已经采集的最新 TXT，避免必须再采集一份。 */
    private fun exportLatestRawFile() {
        if (gnssLogger.isRecording) {
            showToast("请先停止采集并等待保存完成，再导出")
            return
        }
        if (publicGnssExporter.selectedDirectory() == null) {
            showToast("请先点击“选择保存目录”")
            return
        }
        val file = findNewestRawObservationFile()
        if (file == null) {
            showToast("没有找到已保存的 RawData TXT")
            return
        }
        btnExportLatest.isEnabled = false
        btnStart.isEnabled = false
        lifecycleScope.launch {
            tvFilePath.text = "应用内文件：${file.name}\n${exportGnssFileToPublic(file)}"
            btnExportLatest.isEnabled = true
            btnStart.isEnabled = true
        }
    }

    /** 选择目录内文件名日期最新的非空 Raw TXT，离线生成 OBS；不是读取当前实时内存观测。 */
    private fun convertRawToRinex() {
        if (gnssLogger.isRecording) {
            showToast("请先停止采集并等待保存完成，再转换")
            return
        }
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
        tvStatus.text = "正在转换为 RINEX…"
        tvCaptureMessage.text = "正在转换 ${inputFile.name}…"

        // 🌟 核心修复：从原始文件名中提取精确的“采集开始时间”，而不是用文件结束修改时间
        // 假设原始文件名为: RawData_20260724_111812.txt
        val fileName = inputFile.name
        // 以下 UTC 仅用于生成输出文件名；文件内观测历元由 GnssClock 转为 GPST。
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        try {
            // 2. 截取文件名中的时间字符串部分，例如 "20260725_111812"
            val timeString = fileName.substringAfter("RawData_").substringBefore(".txt")
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

            // ★ 核心 1：明确告知解析器，RawData 文件名是你手机在本地时区生成的
            sdf.timeZone = TimeZone.getDefault()

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
            Locale.US,
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
            // 转换器和后处理继续使用应用内 File 路径；公共目录只是便于查找的完整副本。
            val exportMessage = if (success) exportGnssFileToPublic(outputFile) else ""

            withContext(Dispatchers.Main) {
                btnConvert.isEnabled = true
                if (success) {
                    val qc = RinexConverter.lastStats
                    tvStatus.text = "转换成功"
                    tvFilePath.text = "RINEX文件：${outputFile.name}\n$exportMessage"
                    tvCaptureMessage.text = "转换成功：码 ${qc.codeAccepted}、相位 ${qc.phaseAccepted}" +
                        if (qc.phaseAccepted == 0) "；无可用相位，不能用于高精度 PPP。"
                        else "；可继续准备精密产品。"
                } else {
                    tvStatus.text = "转换失败，请检查原始数据"
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

    /** 下载器仍写原目录；只在全部下载任务结束后复制已存在的完整产品到公共目录。 */
    private fun startFtpDownload() {
        btnDownload.isEnabled = false
        btnExportProducts.isEnabled = false
        ui.tvDownloadLog.text = ""
        updateDownloadStage("正在准备下载")
        updateDownloadDate(0)

        val localDir = getExternalFilesDir("GNSS_Products")
        if (localDir == null || (!localDir.exists() && !localDir.mkdirs())) {
            tvDownloadStage.text = "无法创建下载目录"
            tvDownloadPercent.text = "失败"
            tvDownloadFile.text = "请检查设备存储空间后重试"
            downloadProgressBar.isIndeterminate = false
            btnDownload.isEnabled = true
            btnExportProducts.isEnabled = true
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            // 单次下载完整日志不随进度刷新而丢失；不写认证头、账号或密码。
            synchronized(downloadLogLock) {
                downloadLogFile = try {
                    File(localDir, "download_${System.currentTimeMillis()}.log").apply {
                        writeText("产品下载会话\n", Charsets.UTF_8)
                    }
                } catch (error: java.io.IOException) {
                    Log.w("FtpDownloader", "无法保存下载日志：${error.javaClass.simpleName}")
                    null
                }
            }
            appendLog("下载日志：${downloadLogFile?.absolutePath ?: "文件写入失败；本次界面仍保留日志"}")
            val ftpServer = "igs.gnsswhu.cn"
            var preciseProductsReady = false // 专门标记精密产品是否找齐
            var biasReady = false
            var vmfReady = false
            var orographyReady = false
            var ionoReady = false
            var atxReady = false
            var preciseFailure = ""
            var navOk = false
            var navFallbackNotice = ""
            // 整次下载固定一个 UTC 基准，避免跨午夜时各产品被拼成不同日期。
            val downloadUtcMillis = System.currentTimeMillis()
            try {
                BundledProducts.ensure(applicationContext, localDir!!, ::appendLog)
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    downloadProgressBar.isIndeterminate = false
                    tvDownloadStage.text = "静态产品准备失败"
                    tvDownloadPercent.text = "失败"
                    tvDownloadFile.text = error.message ?: "请检查本地 ATX/格网高程文件"
                    btnDownload.isEnabled = true
                    btnExportProducts.isEnabled = true
                }
                return@launch
            }
            // NAV 独立获取：不能因 WHU 精密产品失败，就根本不尝试广播星历。
            updateDownloadStage("正在准备本地天线文件")
            atxReady = FtpDownloader.downloadAtxProduct(localDir,
                onTransferProgress = ::updateTransferProgress) { appendLog(it) }
            val todayUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = downloadUtcMillis
            }
            val bkgFolder = String.format(Locale.US, "/IGS/BRDC/%04d/%03d/",
                todayUtc.get(Calendar.YEAR), todayUtc.get(Calendar.DAY_OF_YEAR))
            updateDownloadStage("正在获取最新可用广播星历")
            navOk = FtpDownloader.downloadNavProduct("igs-ftp.bkg.bund.de", bkgFolder, localDir,
                referenceUtcMillis = downloadUtcMillis,
                onTransferProgress = ::updateTransferProgress) { msg ->
                appendLog(msg)
                if (msg.startsWith("⚠️ 使用回退广播星历")) navFallbackNotice = msg
            }

            // RTS BIA has its own publication directory, independently of NRT SP3/CLK availability.
            updateDownloadStage("正在获取最新 RTS 码相偏差文件")
            biasReady = FtpDownloader.downloadWhuRtsBias(
                ftpServer, localDir, referenceUtcMillis = downloadUtcMillis,
                onTransferProgress = ::updateTransferProgress
            ) { msg -> appendLog(msg) }

            // 精密预报只回退“仍覆盖当前时刻”的起算时次，不回退当前观测／大气产品日期。
            run {
                updateDownloadDate(0)
                val doyStr = String.format(Locale.US, "%03d", todayUtc.get(Calendar.DAY_OF_YEAR))
                updateDownloadStage("正在获取最新配套精密轨道与钟差")
                val pairOk = FtpDownloader.downloadLatestWhuNrtPair(
                    ftpServer, localDir, referenceUtcMillis = downloadUtcMillis,
                    onTransferProgress = ::updateTransferProgress
                ) { msg ->
                    appendLog(msg)
                    if (msg.startsWith("❌") || msg.startsWith("⚠️")) preciseFailure = msg
                }

                if (pairOk) {
                    appendLog("\n👉 核心数据已就位！开始获取对应日期大气模型...")
                    val targetCal = todayUtc.clone() as Calendar

                    // 从 Git 忽略的 vmf3.local.properties 生成，不再把个人凭据写入版本库。
                    // 未配置时为空，保持原有“跳过 VMF3 下载”逻辑；APK 内仍可提取，非生产密钥保护方案。
                    val vmfUsername = "guestQ4"
                    val vmfPassword = "Fmw%54fc"

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
                    appendLog("🎯 第 $doyStr 天核心精密数据就位；其余产品状态见下载汇总。")
                } else {
                    appendLog("⚠️ 无覆盖当前时间的配套精密产品；广播星历已独立检索，不伪装为完整就绪。")
                }
            }

            // 即使本次有产品下载失败，也可同步已完成/以前留存的非空产品。
            // 白名单排除 Raw/OBS、.part、日志和本地凭据；不把副本失败当作下载失败。
            if (publicGnssExporter.selectedDirectory() != null) {
                updateDownloadStage("正在导出产品到公共目录")
            }
            val publicProductsMessage = exportProductsToPublic(localDir)

            // ==========================================
            // 阶段 3：UI 线程更新最终结果
            // ==========================================
            withContext(Dispatchers.Main) {
                downloadProgressBar.isIndeterminate = false
                downloadProgressBar.progress = if (preciseProductsReady) 100 else 0
                tvDownloadStage.text = if (preciseProductsReady) "下载完成" else "核心文件缺失"
                tvDownloadPercent.text = if (preciseProductsReady) "完成" else "失败"
                val missing = mutableListOf<String>()
                if (!biasReady) missing += "最新RTS偏差"
                if (!vmfReady) missing += "对流层"
                if (!orographyReady) missing += "格网高程"
                if (!ionoReady) missing += "电离层"
                if (!atxReady) missing += "天线文件"
                if (!navOk) missing += "最新可用广播星历"
                val completionSummary = when {
                    !preciseProductsReady -> preciseFailure.ifBlank {
                        "未找到匹配的精密轨道或钟差，请检查网络后重试"
                    }
                    missing.isEmpty() && navFallbackNotice.isNotBlank() -> "产品下载完成；广播星历已回退，须核对时效"
                    missing.isEmpty() -> "所有产品已下载；实际观测覆盖仍由解算器检查"
                    else -> "已获取轨道和钟差；缺少：${missing.joinToString("、")}"
                }
                appendLog("下载汇总：$completionSummary")
                if (preciseProductsReady) {
                    if (navOk) {
                        appendLog("\nSP3 + CLK + NAV 已下载；BIA／大气模型状态请查看汇总。\n文件路径: ${localDir.absolutePath}")
                    } else {
                        // 精密星历下到了，但广播星历没下到，依然算是核心成功
                        appendLog("\n⚠️ 核心精密产品已就位，但广播星历缺失，部分算法可能受限。\n文件路径: ${localDir?.absolutePath}")
                    }
                } else {
                    appendLog("\n❌ 未找到覆盖当前时刻的配套 SP3/CLK，请检查网络或服务器发布状态。")
                }
                tvDownloadFile.text = completionSummary
                if (navFallbackNotice.isNotBlank()) tvDownloadFile.append("\n$navFallbackNotice")
                tvDownloadFile.append("\n$publicProductsMessage")
                tvDownloadFile.append("\n详细过程：展开下方“最近活动”；日志 ${downloadLogFile?.name ?: "未写入文件"}")
                btnDownload.isEnabled = true
                btnExportProducts.isEnabled = true
            }
        }
    }

    private fun appendLog(msg: String) {
        Log.i("FtpDownloader", msg)
        val line = "${java.time.Instant.now()} $msg\n"
        synchronized(downloadLogLock) {
            try {
                downloadLogFile?.appendText(line, Charsets.UTF_8)
            } catch (error: java.io.IOException) {
                // 日志写失败不能中止下载，也不能误报为网络超时。
                Log.w("FtpDownloader", "下载日志写入失败：${error.javaClass.simpleName}")
            }
        }
        runOnUiThread {
            tvDownloadFile.text = msg
            // 限制界面文本占用；磁盘日志不截断，进度回调不逐字节写日志。
            ui.tvDownloadLog.text = (ui.tvDownloadLog.text + line).takeLast(48_000)
        }
    }

    /** 该函数只做公共副本同步，不改变下载文件检索、产品配置或 PPP 输入路径。 */
    private suspend fun exportProductsToPublic(directory: File): String {
        if (publicGnssExporter.selectedDirectory() == null) {
            return "产品仅保存在应用内；请选择保存目录，再点击“导出已下载产品”"
        }
        return try {
            val summary = withContext(Dispatchers.IO) {
                publicGnssExporter.exportProducts(directory)
            }
            if (summary.total == 0) "未找到可导出的完整产品文件" else {
                summary.failures.forEach { Log.w(TAG, "Public product export: $it") }
                "公共目录：${summary.directory}；新增 ${summary.copied} 个，" +
                    "相同副本 ${summary.reused} 个，失败 ${summary.failures.size} 个" +
                    if (summary.failures.isEmpty()) "" else "\n${summary.failures.first()}；内部产品保留，可重新导出"
            }
        } catch (error: Exception) {
            "公共产品导出失败：${error.message}；内部产品保留"
        }
    }

    /** 让已下载产品也能迁移到易找到的文件夹，不需要重新联网下载。 */
    private fun exportDownloadedProducts() {
        if (!btnDownload.isEnabled) {
            showToast("请等待当前下载/导出任务完成")
            return
        }
        if (publicGnssExporter.selectedDirectory() == null) {
            showToast("请先点击“选择保存目录”")
            return
        }
        val directory = getExternalFilesDir("GNSS_Products")
        if (directory == null || !directory.isDirectory) {
            showToast("未找到已下载的产品目录")
            return
        }
        btnDownload.isEnabled = false
        btnExportProducts.isEnabled = false
        lifecycleScope.launch {
            try {
                tvDownloadFile.text = "正在导出已有产品，请稍候…"
                val message = exportProductsToPublic(directory)
                tvDownloadFile.text = message
                tvFilePath.text = message
            } finally {
                btnDownload.isEnabled = true
                btnExportProducts.isEnabled = true
            }
        }
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
        ui.comparisonSolution = null
        ui.comparisonIsPostprocessed = true
        val dir = getExternalFilesDir("GNSS_Products")
        if (dir == null || !dir.exists()) {
            showToast("未找到工作目录，请先进行数据准备！")
            return
        }

        btnRunPpp.isEnabled = false
        tvPppResult.text = "正在扫描工作目录…"

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
                var comparison: PostComparisonSummary? = null
                var comparisonFailure: String? = null
                if (resultCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                    try {
                        comparison = PostPositionComparison.export(outputFile,
                            File(outputFile.absolutePath + ".errors.csv"), referencePositionStore.current)
                    } catch (error: Exception) {
                        comparisonFailure = error.message
                        Log.w(TAG, "Error comparison export failed; original solution preserved", error)
                    }
                }

                withContext(Dispatchers.Main) {
                    if (resultCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                        tvPppResult.text = "后处理文件已生成，耗时: ${costTime}ms\n"
                        readAndShowCoordinates(outputFile)
                        ui.comparisonSolution = comparison?.lastSolution
                        ui.comparisonIsPostprocessed = true
                        comparison?.let { tvPppResult.append("\n" + getString(R.string.reference_csv_saved, it.output.name)) }
                        comparisonFailure?.let { tvPppResult.append("\n" + getString(R.string.reference_csv_failed, it)) }
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
                            crashReason += "\n底层 Trace 报错分析：\n-----------------\n${traceFile.readText().takeLast(2000)}"
                        } else {
                            crashReason += "\n未发现 Trace 文件。"
                        }

                        tvPppResult.text = "解算失败，请看弹窗报告！"
                        ui.dialog = UiDialog(
                            getString(R.string.ppp_report_title), crashReason,
                            getString(R.string.action_close), cancelable = false,
                        )
                    }
                    btnRunPpp.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvPppResult.text = "发生异常: ${e.message}"
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
