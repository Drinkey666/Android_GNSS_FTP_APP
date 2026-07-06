package com.example.ftpget // ★ 务必确保这里的包名和你的项目一致 ★

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.Chronometer
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity_PPP"

    // 替换为全新的 RINEX 直出引擎
    private lateinit var rinexLogger: RinexLogger
    private lateinit var locationManager: LocationManager
    private var isRecording = false

    private lateinit var chronometer: Chronometer
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnConvert: Button
    private lateinit var tvFilePath: TextView

    private lateinit var btnDownload: Button
    private lateinit var tvLog: TextView

    private lateinit var btnRunPpp: Button
    private lateinit var tvPppResult: TextView

    // 系统底层 GNSS 历元数据实时回调
    private val gnssMeasurementsCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            if (isRecording) {
                // 直接将系统历元喂给 RINEX 引擎，实时计算伪距并落盘
                rinexLogger.processGnssMeasurements(event)
            }
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startGnssRecording()
        } else {
            Toast.makeText(this, "必须授予位置权限才能采集卫星数据！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()

        // 初始化底层硬件管理器与 RINEX 引擎
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        rinexLogger = RinexLogger(this)

        // 配置 RINEX 头文件信息（可根据你的需求自定义）
        val settings = RinexLogger.HeaderSettings().apply {
            stationName = "MYPHONE"
            markerName = "Android_PPP"
            observer = "SWJTU"
        }
        rinexLogger.applyHeaderSettings(settings)

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
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        btnRunPpp = findViewById(R.id.btnRunPpp)
        tvPppResult = findViewById(R.id.tvPppResult)

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
        btnDownload.setOnClickListener { startFtpDownload() }
        btnRunPpp.setOnClickListener { startPppProcessing() }
    }

    @SuppressLint("MissingPermission")
    private fun startGnssRecording() {
        try {
            val dir = getExternalFilesDir("GNSS_Products")
            if (dir != null && !dir.exists()) dir.mkdirs()

            if (dir != null) {
                // 启动引擎，开始创建 RINEX 临时文件
                rinexLogger.startNewLog(dir, "MYPHONE", Date())

                // 向系统注册高频底层数据回调
                locationManager.registerGnssMeasurementsCallback(gnssMeasurementsCallback, null)
                isRecording = true

                chronometer.base = SystemClock.elapsedRealtime()
                chronometer.start()
                tvStatus.text = "🟢 实时 RINEX 直出采集中..."
                tvLog.text = "> 伪距与载波相位计算开启，正在按秒实时写入标准 RINEX 文件...\n"
                updateRecordButtons(true)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "启动采集失败，请检查定位权限！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopGnssRecording() {
        if (isRecording) {
            // 注销系统底层回调，释放硬件
            locationManager.unregisterGnssMeasurementsCallback(gnssMeasurementsCallback)
            // 通知引擎停止，合并数据并封装标准 RINEX 头部
            rinexLogger.stopLog()
            isRecording = false
        }

        chronometer.stop()
        tvStatus.text = "🔴 采集已停止"
        updateRecordButtons(false)

        val rnxFile = rinexLogger.getFile()
        if (rnxFile != null && rnxFile.exists()) {
            tvFilePath.text = "直出文件: ${rnxFile.name}"
            tvLog.append("> 观测文件已完美落盘：${rnxFile.name}。全程直出，无需转换步骤！\n")
        }
    }

    private fun convertRawToRinex() {
        // 由于架构升级为边采边算 RINEX 直出，旧的转换步骤已废弃
        btnConvert.isEnabled = false
        tvStatus.text = "✅ 无需转换！"
        Toast.makeText(this, "数据已在底层实时生成为标准 RINEX，无需再次转换！", Toast.LENGTH_LONG).show()
        tvLog.append("> 提示：实时采集架构已免去格式转换步骤，请直接下载星历进行解算。\n")
    }

    private fun startFtpDownload() {
        btnDownload.isEnabled = false
        tvLog.text = "> 启动终极智能下载引擎...\n"

        val localDir = getExternalFilesDir("GNSS_Products")
        if (localDir != null && !localDir.exists()) localDir.mkdirs()

        lifecycleScope.launch(Dispatchers.IO) {
            val ftpServer = "igs.gnsswhu.cn"
            var success = false

            for (daysAgo in 0..3) {
                val timeParams = GpsTimeUtil.getIgsV3TimeParams(daysAgo)
                val week = timeParams.week
                val yearStr = timeParams.year.toString()
                val doyStr = timeParams.doy

                appendLog("\n--- 检索回退 $daysAgo 天 ($yearStr 年, 第 $doyStr 天) 的全套数据 ---")

                // 🌟 核心修改：精准路径分配
                // 1. SP3 和 CLK 的存放路径
                val orbitFolder = "/pub/whu/phasebias/$yearStr/orbit"
                val ClockFolder = "/pub/whu/phasebias/$yearStr/clock"
                // 2. BIA (OSB) 的专用存放路径
                val biasFolder = "/pub/whu/phasebias/$yearStr/bias/"

                appendLog("👉 访问轨道目录: $orbitFolder")
                appendLog("👉 访问偏差目录: $biasFolder")
                appendLog("👉 访问偏差目录: $ClockFolder")

                // 下载轨道 (SP3)
                val sp3Ok = FtpDownloader.downloadLatestProduct(
                    ftpServer, orbitFolder, yearStr, doyStr, "SP3", localDir!!
                ) { msg -> appendLog(msg) }

                // 下载钟差 (CLK)
                val clkOk = FtpDownloader.downloadLatestProduct(
                    ftpServer, ClockFolder, yearStr, doyStr, "CLK", localDir!!
                ) { msg -> appendLog(msg) }

                // 下载偏差 (BIA/OSB) - 使用你指定的精确路径
                val osbOk = FtpDownloader.downloadLatestProduct(
                    ftpServer, biasFolder, yearStr, doyStr, "BIA", localDir!!
                ) { msg -> appendLog(msg) }

                if (sp3Ok && clkOk) {
                    val todayTimeParams = GpsTimeUtil.getIgsV3TimeParams(0)
                    val todayYearStr = String.format(Locale.US, "%04d", todayTimeParams.year)
                    val todayDoyStr = String.format(Locale.US, "%03d", todayTimeParams.doy.toInt())

                    val bkgFtpServer = "igs-ftp.bkg.bund.de"
                    val bkgFolder = "/IGS/BRDC/$todayYearStr/$todayDoyStr/"

                    appendLog("\n👉 精密轨道已就位！启动跨国通道：直连 $bkgFtpServer 获取【当天】星历...")

                    val atxOk = FtpDownloader.downloadAtxProduct(localDir) { msg -> appendLog(msg) }
                    if (!atxOk) appendLog("⚠️ ATX 天线文件获取失败，可能影响解算精度。")

                    val navOk = FtpDownloader.downloadNavProduct(
                        bkgFtpServer, bkgFolder, localDir
                    ) { msg -> appendLog(msg) }

                    if (navOk) {
                        appendLog("🎯 跨国夺宝成功！全套 GNSS 解算数据准备完毕！")
                        success = true
                        break
                    }
                } else {
                    appendLog("⚠️ $daysAgo 天前的精密产品未找齐，继续往前倒推...")
                }
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    appendLog("\n🎉 弹药库装填完毕！SP3 + CLK + NAV 全部就位。\n文件路径: ${localDir?.absolutePath}")
                } else {
                    appendLog("\n❌ 连续回退 3 天查找均未找齐成套数据，请检查网络权限或 FTP 服务器状态。")
                }
                btnDownload.isEnabled = true
            }
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread { tvLog.append("$msg\n") }
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
                // 🔥 兼容修改：让引擎同时认得出 .rnx 以及传统的 .26o / .24o 后缀观测文件
                val obsFile = dir.listFiles { f ->
                    f.name.endsWith(".rnx", ignoreCase = true) ||
                            f.name.endsWith(".26o", ignoreCase = true) ||
                            f.name.endsWith(".24o", ignoreCase = true)
                }?.maxByOrNull { it.lastModified() }

                val sp3Gz = dir.listFiles { f -> f.name.contains("SP3", ignoreCase = true) && f.name.endsWith(".gz", ignoreCase = true) }?.maxByOrNull { it.name }
                val clkGz = dir.listFiles { f -> f.name.contains("CLK", ignoreCase = true) && f.name.endsWith(".gz", ignoreCase = true) }?.maxByOrNull { it.name }
                val navGz = dir.listFiles { f -> f.name.endsWith("MN.rnx.gz", ignoreCase = true) }?.maxByOrNull { it.lastModified() }

                if (obsFile == null || sp3Gz == null || clkGz == null || navGz == null) {
                    withContext(Dispatchers.Main) {
                        tvPppResult.text = "❌ 弹药不足！缺少观测文件(.rnx/.o)或压缩格式星历文件。"
                        btnRunPpp.isEnabled = true
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { tvPppResult.text = "📦 正在解压精密产品，请稍候..." }

                val sp3File = File(dir, sp3Gz.name.replace(".gz", "", ignoreCase = true))
                val clkFile = File(dir, clkGz.name.replace(".gz", "", ignoreCase = true))
                val navFile = File(dir, navGz.name.replace(".gz", "", ignoreCase = true))

                FileUtil.unGzip(sp3Gz, sp3File)
                FileUtil.unGzip(clkGz, clkFile)
                FileUtil.unGzip(navGz, navFile)

                val outputFile = File(dir, "PPP_Result_${System.currentTimeMillis()}.pos")
                val traceFile = File(outputFile.absolutePath + ".trace")
                val confFile = generatePppConf(dir)

                val filesToCheck = listOf(confFile, obsFile, navFile, sp3File, clkFile)
                val logBuilder = java.lang.StringBuilder("🩺 JNI 传参文件体检清单：\n")
                var allHealthy = true

                for (f in filesToCheck) {
                    if (!f.exists() || f.length() == 0L) {
                        logBuilder.append("❌ 空壳/丢失: ${f.name}\n")
                        allHealthy = false
                    } else {
                        logBuilder.append("✅ 正常 (${f.length()} B) -> ${f.name}\n")
                    }
                }

                withContext(Dispatchers.Main) { tvPppResult.text = logBuilder.toString() }

                if (!allHealthy) {
                    withContext(Dispatchers.Main) {
                        tvPppResult.append("\n⚠️ 发现致命空文件，已拦截 JNI 调用！请检查网络或解压逻辑。")
                        btnRunPpp.isEnabled = true
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) { tvPppResult.append("\n🚀 引擎轰鸣中，Trace 追踪已开启...") }

                val args = arrayOf(
                    "rnx2rtkp",
                    "-k", confFile.absolutePath,
                    "-x", "5",
                    "-y", "2",
                    "-o", outputFile.absolutePath,
                    obsFile.absolutePath,
                    navFile.absolutePath,
                    sp3File.absolutePath,
                    clkFile.absolutePath
                )

                val startTime = System.currentTimeMillis()
                val resultCode = RtkEngine.runPpp(args)
                val costTime = System.currentTimeMillis() - startTime

                withContext(Dispatchers.Main) {
                    if (resultCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                        tvPppResult.text = "✅ 解算大成功！耗时: ${costTime}ms\n"
                        readAndShowCoordinates(outputFile)
                    } else {
                        var crashReason = "引擎返回码: $resultCode\n耗时: ${costTime}ms\n"
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

    private fun generatePppConf(dir: File): File {
        val confFile = File(dir, "ppp_config_${System.currentTimeMillis()}.conf")
        val atxFile = dir.listFiles { f -> f.name.endsWith(".atx", ignoreCase = true) }?.firstOrNull()
        val atxPath = atxFile?.absolutePath ?: ""
        val confContent = """
        pos1-posmode=ppp-static
        pos1-frequency=l1+l2+l5
        pos1-soltype=forward
        pos1-elmask=15
        pos1-dynamics=on
        pos1-tidecorr=off
        pos1-ionoopt=dual-freq
        pos1-tropopt=est-ztdgrad
        pos1-sateph=precise
        pos1-posopt1=on
        pos1-posopt2=off
        pos1-posopt3=on
        pos1-posopt4=off
        pos1-posopt5=on
        pos1-posopt6=off
        pos1-navsys=63
        pos1-snrmask_r=off
        pos1-snrmask_b=off
        pos2-armode=off
        pos2-gloarmode=off
        pos2-bdsarmode=off
        pos2-arfilter=off
        out-solformat=llh
        out-outhead=on
        out-outopt=on
        out-timesys=gpst
        out-timeform=hms
        out-timendec=3
        out-degform=deg
        out-height=ellipsoidal
        stats-eratio1=300
        stats-eratio2=300
        stats-eratio5=300
        stats-eratio6=300
        stats-errphase=0.01
        stats-errphaseel=0.00
        stats-snrmax=45.0
        stats-errphasesnr=0.01
        stats-stdiono=0.03
        stats-stdtrop=0.3
        stats-prnaccelh=3
        stats-prnaccelv=1
        stats-clkstab=5e-12
        file-satantfile=$atxPath
        """.trimIndent()
        confFile.writeText(confContent)
        if (confFile.length() == 0L) throw Exception("配置写入失败！")
        return confFile
    }

    private fun readAndShowCoordinates(posFile: File) {
        try {
            var firstCoordinateFound = false
            posFile.forEachLine { line ->
                if (!line.startsWith("%") && line.trim().isNotEmpty() && !firstCoordinateFound) {
                    val parts = line.trim().split("\\s+".toRegex())
                    if (parts.size >= 5) {
                        val lat = parts[2]
                        val lon = parts[3]
                        val height = parts[4]
                        val q = if (parts.size >= 6) parts[5] else "?"
                        val resultStr = "🎯 算出坐标啦！\n纬度: $lat\n经度: $lon\n高程: $height m\n解状态(Q): $q"
                        tvPppResult.append("\n\n$resultStr")
                        showToast(resultStr, Toast.LENGTH_LONG)
                        firstCoordinateFound = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PPP_Engine", "坐标解析失败", e)
        }
    }

    private fun showToast(msg: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, msg, duration).show()
    }
}