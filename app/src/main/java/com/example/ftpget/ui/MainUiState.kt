package com.example.ftpget.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.ftpget.ReferencePosition
import com.example.ftpget.SolutionCoordinate

/** Observable presentation values for the existing Activity's business callbacks. */
class UiText(initial: String = "") {
    var text by mutableStateOf(initial)
    fun append(value: String) { text += value }
}

class UiAction(initialText: String = "") {
    var text by mutableStateOf(initialText)
    var isEnabled by mutableStateOf(true)
    private var listener: (() -> Unit)? = null
    fun setOnClickListener(action: () -> Unit) { listener = action }
    fun click() { if (isEnabled) listener?.invoke() }
}

class UiProgress {
    var progress by mutableIntStateOf(0)
    var isIndeterminate by mutableStateOf(false)
}

class UiChronometer {
    var base by mutableStateOf(0L)
    var isRunning by mutableStateOf(false)
    var stoppedElapsed by mutableStateOf(0L)
    fun start() { isRunning = true }
    fun stop(now: Long) {
        stoppedElapsed = (now - base).coerceAtLeast(0L)
        isRunning = false
    }
}

data class PositionFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val accuracyMeters: Float,
)

data class UiDialog(
    val title: String,
    val message: String,
    val confirmText: String,
    val onConfirm: () -> Unit = {},
    val cancelable: Boolean = true,
)

class MainUiState {
    var captureCodePhaseRepair by mutableStateOf(false) // opt-in; resets OFF on fresh Activity

    var selectedPage by mutableIntStateOf(0)
    var measurementCount by mutableIntStateOf(0)
    var satelliteCount by mutableIntStateOf(0)
    var usedSatelliteCount by mutableIntStateOf(0)
    var signalSystemCount by mutableIntStateOf(0)
    var positionFix by mutableStateOf<PositionFix?>(null)
    var referencePosition by mutableStateOf<ReferencePosition?>(null)
    var comparisonSolution by mutableStateOf<SolutionCoordinate?>(null)
    var comparisonIsPostprocessed by mutableStateOf(false)
    var onReferenceChanged: (ReferencePosition?) -> Unit = {}
    var dialog by mutableStateOf<UiDialog?>(null)
    var mapLoading by mutableStateOf(false)
    var mapLoadError by mutableStateOf(false)

    val chronometer = UiChronometer()
    val tvStatus = UiText("等待采集…")
    val btnStart = UiAction("开始采集")
    val btnStop = UiAction("停止采集")
    val btnConvert = UiAction("转换为 RINEX")
    val tvFilePath = UiText("采集文件将显示在这里")
    val btnChooseExportDirectory = UiAction("选择保存目录")
    val btnExportLatest = UiAction("导出最近采集的 TXT")
    val btnExportProducts = UiAction("导出已下载产品")
    val tvExportDirectory = UiText("公共保存目录：未选择")
    val btnDownload = UiAction("开始下载")
    val downloadProgressBar = UiProgress()
    val tvDownloadStage = UiText("等待下载")
    val tvDownloadPercent = UiText("—")
    val tvDownloadFile = UiText("下载时将显示当前文件与已传输大小")
    // 与瞬时传输进度分开，避免后续文件/最终汇总覆盖真正的失败原因。
    val tvDownloadLog = UiText()
    val tvDownloadDate = UiText("目标日期 —")
    val tvCaptureMessage = UiText("等待采集或转换操作")
    val btnRunPpp = UiAction("RINEX 后处理 PPP（PC 内核）")
    val btnLivePpp = UiAction("启动实时 PPP")
    val tvPppResult = UiText("等待启动实时 PPP；仅显示 GPST、Q、卫星数、坐标和处理耗时。")
    val tvSatelliteSummary = UiText("等待卫星状态…")
    val tvMeasurementSummary = UiText("等待原始观测…")
    val tvHeaderStatus = UiText("等待 GNSS 信号")
    val tvDevicePosition = UiText("等待手机定位…")
    val tvMapStatus = UiText("等待设备定位。点击下方按钮刷新地图。")
    val btnRefreshMap = UiAction("定位到当前位置")
    val btnOpenMap = UiAction("在手机地图中打开")
}
