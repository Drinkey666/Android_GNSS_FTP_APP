package com.example.ftpget

import android.location.GnssClock
import android.location.GnssMeasurement

/**
 * Kotlin↔C++ JNI 边界；库名对应 CMake 的 gnss_ppp_live，方法名/包名须与 C++ 导出函数一致。
 * 一个原生实例持有会话状态和已加载产品，实时观测无需经过 rinex.c 的 OBS 读取。
 */
object LivePppNative {
    init { System.loadLibrary("gnss_ppp_live") }

    // 固定九路径顺序：NAV、SP3、CLK、BIA、IONEX、VMF3左期、右期、格网椭球高程、ATX。
    // 下标顺序是 ABI 契约，不能按文件扩展名随意重新排序；true 表示初始化成功。
    external fun nativeInit(productPaths: Array<String>): Boolean
    // clock 是全事件公共时钟；measurements 是卫星×信号集合，不是“一颗卫星一个对象”。
    // null 表示此事件处理失败/被跳过，先读取 nativeLastError；非 null 数组固定 18 个字段。
    external fun nativeProcessEpoch(
        clock: GnssClock,
        measurements: Array<GnssMeasurement>
    ): DoubleArray?
    external fun nativeRelease() // 停止会话时释放原生状态/产品；不是每历元调用。
    external fun nativeLastError(): String
    external fun nativeObsRows(): String // 最近成功历元的输入观测 CSV，列见工程说明；不是结果坐标。
}

/** 返回 DTO；std 是滤波器估计的坐标标准差，不是相对实测真值的实际误差。 */
data class LivePppResult(
    val week: Int, // [0] 完整 GPS 周数，不是广播消息截断周数。
    val tow: Double, // [1] GPS 周内秒(s)，GPST。
    val q: Int, // [2] RTKLIB sol.stat；0无解/5单点/6浮点PPP，不是百分比精度。
    val satellites: Int, // [3] sol.ns，解算结果报告的有效卫星数。
    val latitude: Double, // [4] 纬度(°)，不是弧度。
    val longitude: Double, // [5] 经度(°)。
    val height: Double, // [6] 椭球高(m)，不是海拔/正常高。
    val x: Double, // [7] 地心地固 ECEF X(m)。
    val y: Double, // [8] ECEF Y(m)。
    val z: Double, // [9] ECEF Z(m)。
    val stdX: Double, // [10] ECEF X 标准差(m)，不是 ENU 的东向标准差。
    val stdY: Double, // [11] ECEF Y 标准差(m)。
    val stdZ: Double, // [12] ECEF Z 标准差(m)，不是单独的高程标准差。
    val processingMs: Double, // [13] JNI 内字段读取/转换/解算耗时(ms)，不含 worker 排队/界面/CSV写盘。
    val rawCount: Int, // [14] 此事件原始 measurement 数，含多个频点。
    val observedSatellites: Int, // [15] adapter 生成的 obsd_t 数，同星多频已合并。
    val phaseCount: Int, // [16] adapter 输出非零 L 的信号数，不等于 PPP 最终接受数。
    val codeCount: Int // [17] adapter 输出非零 P 的信号数。
) {
    companion object {
        // 位置映射须和 gnss_jni.cpp output[] 同步；长度验证只能发现字段数不同，不能发现顺序写错。
        fun fromNative(v: DoubleArray): LivePppResult {
            require(v.size == 18) { "Native PPP result length ${v.size}" }
            return LivePppResult(
                v[0].toInt(), v[1], v[2].toInt(), v[3].toInt(),
                v[4], v[5], v[6], v[7], v[8], v[9],
                v[10], v[11], v[12], v[13],
                v[14].toInt(), v[15].toInt(), v[16].toInt(), v[17].toInt()
            )
        }
    }
}
