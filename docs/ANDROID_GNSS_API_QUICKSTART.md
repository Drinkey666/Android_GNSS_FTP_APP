# Android GNSS 原始数据采集学习手册

基于 FTPGet 工程的快速入门　2026年10月5日

## 1 先认识程序拿到的是什么

这份手册帮助你读懂当前工程如何向 Android 申请 GNSS 数据、保存 TXT，以及把这些数据转换为 RINEX 或交给 JNI。按页顺序学习，再在 Android Studio 中用“查找符号”定位同名函数。不需要先掌握 PPP 滤波公式。

**首先记住：手机显示的定位坐标，与供你自己解算的原始观测，是不同的数据。** Android API 是系统提供的接口；你注册回调后，系统在新数据到来时调用你的函数。应用没有直接控制卫星或芯片的测量过程。[1][2]

| 对象 | 拿到什么 | 当前工程在哪里使用 |
| --- | --- | --- |
| Location | 系统算好的经纬度等 | 地图及系统定位展示；不是 PPP 真值 |
| GnssStatus | 可见卫星、仰角、方位角、信号强度 | 天空图；usedInFix 指系统定位使用情况 |
| GnssClock | 同一事件共用的接收机时钟 | 为原始观测建立 GNSS 时间 |
| GnssMeasurement | 一颗卫星的一个信号的测量 | 生成伪距、载波、多普勒、信号标识 |
| GnssMeasurementsEvent | Clock 加 measurements 集合 | 一批原始输入，通常视作一个历元 |

### 两条输入链路

```text
Android GNSS 芯片 → 系统回调 → Clock + measurements
  ├─ GnssDataLogger → Raw TXT → RinexConverter → RINEX OBS
  └─ LivePppController → JNI → gnss_adapter → obsd_t[]
```

TXT 用于保留原始证据、重新转换和电脑回放。实时 JNI 链路直接传对象，不先写 TXT，也不经过 RINEX OBS。你可以同时采集 TXT 和启动实时功能，但实时功能并不靠不断读取 TXT 工作。

### 在工程中先打开这些文件

本机工程根目录为 `E:\android\test\FTPGet`。Kotlin 文件位于 `app/src/main/java/com/example/ftpget/`。

1. `MainActivity.kt`：按钮、权限申请、启动和停止的入口。
2. `GnssDataLogger.kt`：本手册最重要的采集类。
3. `RinexConverter.kt`：TXT 读入、观测计算及 RINEX 写出。
4. `LivePppController.kt`、`LivePppNative.kt`：实时调度和 JNI 声明。
5. `app/src/main/cpp/gnss_jni.cpp`：把 Java/Kotlin 对象字段复制为 C 结构。

**本页自测：** 同一颗 GPS 卫星同时输出 L1 和 L5，会产生两条 measurement，但不是两颗卫星。最终应合并为同一个卫星观测对象的不同信号槽。

<!-- page -->

## 2 读懂采集代码需要的 Kotlin 语法

先掌握下面这些写法，就可以顺着采集主流程阅读。Kotlin 的 `clock.timeNanos` 对应 Java API 的 `clock.getTimeNanos()`；看到属性访问时，不要以为它是你自己定义的全局变量。

| 写法 | 初学者应理解的含义 | 工程中的用途 |
| --- | --- | --- |
| val / var | val 不可重新赋值；var 可以 | val clock 与 var isRecording |
| fun f(): Boolean | 定义函数，返回 true/false | startRecording 返回注册结果 |
| File? | 可以为 null 的 File | 没有成功保存时不一定有文件 |
| writer?.write(text) | 不为 null 才调用 | 安全访问，但不证明写盘成功 |
| value ?: false | 左边为 null 时取 false | 处理可空的注册返回值 |
| object : Callback() | 创建实现回调的匿名对象 | 接收系统后续推送的数据 |
| override fun ... | 实现或改写父类接口方法 | 系统调用指定的回调方法 |
| suspend / launch | 挂起函数／启动协程 | 等待写盘结束／执行异步任务 |

### 逐行读一个真实的回调片段

下面摘自 `GnssDataLogger.measurementCallback`。它依赖类中已有变量，不是可单独粘贴运行的完整应用。

```kotlin
override fun onGnssMeasurementsReceived(
    eventArgs: GnssMeasurementsEvent
) {
    if (!isRecording) return
    val clock = eventArgs.clock
    for (measurement in eventArgs.measurements) {
        val csvLine = formatMeasurementToCsv(clock, measurement)
        if (dataChannel.trySend(csvLine).isFailure) {
            droppedMeasurements.incrementAndGet()
        }
    }
}
```

`return` 提前结束当前函数。`for` 遍历本次事件的每条信号测量。同一事件共用一份 clock，而每条 measurement 有自己的卫星号、频率和状态。`trySend` 尝试把字符串放进写盘队列，不等待磁盘；失败时增加丢行计数。

### 位掩码与 lambda

```kotlin
val valid = (adrState and 1) != 0
val reset = (adrState and 2) != 0
val slip  = (adrState and 4) != 0
```

`and` 是按位与，不是两个数字相等。例如 ADR 状态为 3，表示 VALID 与 RESET 同时存在；`adrState == 1` 会漏掉这种组合。`or` 用来组合多个标志。[3]

`{ it.run() }` 是 lambda，即传入的一小段函数。这里 it 是 Runnable，它会立即在执行器被调用的线程上运行；这段写法本身并没有创建后台线程。`withContext(Dispatchers.IO)` 才明确切换到适合文件操作的协程调度环境。

<!-- page -->

## 3 从点击开始到安全停止

### 申请与注册

当前入口是 `MainActivity.startGnssRecording()`。Activity 先检查并申请精确定位权限，再调用 `gnssLogger.startRecording()`。Manifest 声明权限不等于用户已授权；`@SuppressLint("MissingPermission")` 也只是关闭检查提示。[6]

Logger 的顺序是：建目录和 TXT → 写表头 → 启动写盘协程 → 请求定位更新 → 注册原始观测回调。只有注册返回成功，`isRecording` 才设为 true；true 不保证已收到有效载波。

下面是当前 Android 12 及以上的注册方式，省略了权限检查、异常处理和旧系统分支。

```kotlin
val request = GnssMeasurementRequest.Builder()
    .setFullTracking(true)
    .build()
val registered = locationManager.registerGnssMeasurementsCallback(
    request, { it.run() }, measurementCallback
)
```

`setFullTracking(true)` 请求完整跟踪，有助于减少占空比影响；是否支持取决于设备，不能理解成“所有频段都可用”或“绝无周跳”。当前日志里的“全频段满血”文字不构成能力验证。[1][5]

Logger 还调用 `requestLocationUpdates(GPS_PROVIDER, 1000L, 0.0f, listener)`。四个参数依次是定位提供者、请求的最小间隔毫秒、最小位移米、监听器。1000L 不会强制原始 GNSS 回调精确每秒一次。[4]

### 回调线程与写盘线程

```text
GNSS 回调：遍历测量 → 生成 CSV → trySend 入队
IO 协程：从 Channel 取行 → 写入 BufferedWriter
主线程：响应按钮、更新界面
```

队列容量 10000 的单位是“测量行”，不是历元。队列满或关闭时 trySend 会失败。当前 droppedMeasurementCount 仅统计入队失败，不能发现芯片没有上报的历元。界面展示截取部分测量，也不代表 TXT 只保存了这些测量。

### 停止为什么需要等待

`stopRecording()` 注销回调及定位监听 → 关闭 Channel → `writeJob?.join()` 等待已入队数据写完 → flush/close → 返回文件。Activity 等这一步完成后才导出或转换，防止读取半写文件。

**操作习惯：** 室外打开定位、授予精确定位、开始采集；结束时点击停止并等待保存完成。当前常亮屏幕不是 WakeLock，也不是前台服务，不保证所有机型锁屏或后台连续采集。

<!-- page -->

## 4 时钟字段如何变成伪距

### 不要混用三种时间

文件名的日期来自手机系统时间，仅便于找文件。GNSS 观测时间由 GnssClock 恢复。开机后单调时间用于计时和延迟分析，不能拿它与卫星发射时间相减。

下面是便于理解的计算关系，不是替代当前转换器的实现。先做大整数差，再处理精细 Bias，避免直接把约 10¹⁸ 的纳秒时间转为 Double 后丢失精度。[2]

```kotlin
val wholeGpsNs: Long = clock.timeNanos - clock.fullBiasNanos
// 同一时间基准上的精细时刻还需扣 Bias，测量时刻需加 TimeOffset。
// 当前代码另有跨周、跨日及钟跳处理，不能只保留这一行。
```

| TXT 列号 | 字段与单位 | 在输入链路中的用途 |
| --- | --- | --- |
| 2、5、6 | TimeNanos、FullBiasNanos、BiasNanos；ns | 从硬件钟读数恢复 GPST |
| 10 | HardwareClockDiscontinuityCount；计数 | 变化意味着钟不连续，重新判断连续弧段 |
| 12 | TimeOffsetNanos；ns | 单条测量相对公共 Clock 的偏移 |
| 13 | State；位掩码 | 检查码锁定、TOW 已知或已解码 |
| 14、15 | ReceivedSvTimeNanos 与其 1σ；ns | 发射时标及其可信度，参与伪距计算 |
| 3 | LeapSecond；s | GPST 与 UTC 转换，不是每个伪距都扣一次 |
| 36 | ChipsetElapsedRealtimeNanos；ns | 事件时间关联；缺失时记录序列化时刻 |

### 用一个数值例子理解 P

假设某个 GPS 信号接收周内时为 100000.070 s，发射周内时为 100000.000 s，相关细项已正确处理。传播时间为 0.070 s。取光速 299792458 m/s，则伪距 P 为 **20985472.06 m**。这是教学数值，不是本项目的实测记录。

伪距含接收机钟差、大气等影响，并不等于卫星与手机的纯几何距离。1 ns 对应约 0.30 m，错误扣除 1 秒会产生约 3 亿米的错误。

GPS/Galileo 与 BeiDou 发射时间不能不加区分地相减。当前代码对 BeiDou 使用 BDT = GPST − 14 s，再处理跨周；GLONASS 离线转换另有日内时处理。State 决定发射时标是否已足够完整。[3]

**当前格式注意：** 第 1 列虽叫 utcTimeMillis，却没有加入 GPS 起点到 Unix 起点的毫秒差，不是 Unix UTC 时间戳。现有转换读第 2、5、6 列，不依靠第 1 列。不要按字段名字猜时间基准。

<!-- page -->

## 5 载波 多普勒和信号标识

| 原始字段及 TXT 列号 | 单位 | 转换后的字段 | 它用于什么 |
| --- | --- | --- | --- |
| ADR，20 | m | L；cycle | 高精度载波观测，需维护连续性 |
| ADR State，19 | 位掩码 | L 有效性及 LLI | 重置、周跳、半周状态 |
| ADR uncertainty，21 | m | 输入有效性检查 | 芯片给出的 1σ 估计，不是真实误差 |
| PseudorangeRate，17 | m/s | D；Hz | 距离变化率、连续性检查 |
| Rate uncertainty，18 | m/s | D 有效性检查 | 不是载波相位标准差 |
| Cn0DbHz，16 | dB-Hz | RINEX S／obs.SNR | 信号质量指标，不等于保证无多路径 |
| 频率，22；CodeType，35 | Hz；属性字符 | 1C、5Q、2I 等 code | 识别具体信号及频率槽 |
| Svid，11；星座，28 | 编号 | RTKLIB sat | 星座与卫星号共同确定卫星 |

### 从米转换为周和赫兹

下面是教学计算，frequencyHz 应使用转换层确认的信号频率；正式链路还会检查有效性和信号策略。

```kotlin
val wavelength = 299792458.0 / frequencyHz
val phaseCycles = adrMeters / wavelength
val dopplerHz = -pseudorangeRateMps / wavelength
```

GPS L1 的波长约 0.190294 m。ADR 为 19.029367 m 时，L 约 100 周。伪距率为 +100 m/s 时，D 约 −525.5 Hz；检查 `-wavelength * dopplerHz` 应恢复 +100 m/s。ADR 是累计距离，有未知起点，不能把它当作 P 的绝对距离。[3]

ADR 常用位为 VALID=1、RESET=2、SLIP=4、HALF_CYCLE_RESOLVED=8、HALF_CYCLE_REPORTED=16。当前 JNI 对有效 ADR 的 RESET/SLIP 保留 L 并设置 LLI_SLIP；半周问题映射到 LLI_HALFC。Android 的 SLIP 数值 4 不是 RTKLIB LLI_SLIP 的数值 1，不能原样复制状态整数。

### 为什么只看频率或卫星号都不够

Android 星座编号为 GPS=1、GLONASS=3、QZSS=4、BDS=5、Galileo=6；这些编号不是 RTKLIB 的 SYS_* 位掩码。GPS 的 Svid=10 与 Galileo 的 Svid=10 也不是同一颗卫星。

CodeType=Q 只表示跟踪属性，还要结合星座与频率才能得到 5Q 或 7Q。当前 Android ppp-safe 接纳 GPS/GAL 的 1C、5Q，BDS 的 2I、7I、5P；识别出信号不代表一定接纳。BDS 1P、5Q、GAL 7Q 仍可记录在 TXT，但不进入这份实时策略。

其他列的用途：23—25 是旧载波接口，不用于当前 L；26 多路径指示、27 SNR、29 AGC、30 基带 C/N0 留作诊断。31—34 是 Android 信号间偏差，当前未直接用于 P/L 改正；它们不等于外部 BIA 产品。37—53 当前为未填充或预留列。

<!-- page -->

## 6 TXT 是怎样转换成 RINEX 的

当前按钮进入 `MainActivity.convertRawToRinex()`，先拒绝采集中转换，再选择应用内文件名日期最新的非空 RawData TXT。不要求文件来自今天。文件名生成用本地时间转 UTC，文件内实际观测历元则由 GNSS 时钟形成 GPST。

### 按顺序追踪转换函数

```text
convert(inputFile, outputFile)
  → parseLine：按固定列号读 TXT，建立 GnssSat
  → identifySignals：识别星座、频率及 CodeType
  → processEpochs：分历元、同星合并，分别计算 P/L/D/S
  → preprocessRinexEpochs：检查跳变与连续性
  → writeRinexFile：写 RINEX 3.05 头和观测记录
```

Raw TXT 是一行一个信号，RINEX 同一历元按卫星组织多个观测类型。`C1C` 是 1C 信号的伪距，`L1C` 是载波，`D1C` 是多普勒，`S1C` 是信号强度；名称首字母表示测量类型，后两位表示信号身份。

`parseLine()` 的 2、5、6、20 等数字是从 0 开始的 CSV 列号。缺失字段必须保留空位置，不能删列。当前 safeLong/safeDouble 对空值回退为 0，这会失去部分“缺失”和“真实零”的区别，因此检查时应同时看原始行和有效性条件。

### 当前两条转换链路并不完全相同

| 比较项 | Kotlin 离线 RINEX 转换 | 实时 JNI 加 adapter |
| --- | --- | --- |
| 数据来源 | 已保存 TXT 文件 | event.clock 和 measurement 数组 |
| ADR 不确定度门限 | 当前 0.50 m | 当前 1.0 m |
| ADR RESET／SLIP | 当历元不写 L，下个有效 L 标记失锁 | 有效时保留当历元 L，设置 LLI |
| 半周处理 | 关注半周状态变化并标记失锁 | 按 adapter 规则映射半周 LLI |
| 信号范围 | 能识别和写出的范围较广 | 按固定 ppp-safe 策略选择 |
| 结果去向 | RINEX OBS 文件 | obsd_t[]，不产生 RINEX OBS |

离线预处理还检查 L 与 Doppler 的变化、伪距跳变及 MW 组合，必要时标记周跳或删除异常 P；它不是简单复制，也不是当前的伪距平滑器。这些是现有实现说明，不是建议你现在改门限。

**因此，L 数量不同不一定是“采集漏了”。** 先判断 Raw 是否存在，再看信号识别、ADR 有效性、门限和频槽选择。与电脑对照时按同一 epoch、sat、code 匹配，比较 P、L×波长、−D×波长、C/N0 和 LLI，不能只比较文件行数。

<!-- page -->

## 7 JNI 如何接收 Android 原始对象

JNI 是 Kotlin/Java 调用 C/C++ 的桥梁。你需要理解数据怎样通过桥梁，不必在这里学习解算公式。`LivePppNative.kt` 先用 `System.loadLibrary("gnss_ppp_live")` 加载原生库，再声明 external 函数。

下面保留当前核心调用形式，省略初始化失败、错误日志和资源释放等处理。

```kotlin
external fun nativeProcessEpoch(
    clock: GnssClock,
    measurements: Array<GnssMeasurement>
): DoubleArray?

val values = LivePppNative.nativeProcessEpoch(
    event.clock, event.measurements.toTypedArray()
)
```

数组里每个元素仍是一条信号测量；`toTypedArray()` 转换集合类型，不计算 P/L/D。返回值可以为 null，表示本次输入跳过或失败，调用方须看 nativeLastError，不能把 null 当成有效坐标。

### 会话只初始化一次

`LivePppController.start()` 建立 HandlerThread。首个事件按 GNSS 时间选产品，并调用 nativeInit；成功后，后续事件跳过初始化。停止时调用 nativeRelease。HandlerThread 的 Looper 串行处理历元；界面更新再通过 runOnUiThread 返回主线程。

```text
每个 event → read_clock + read_measurements
           → android_clock_t + android_meas_t[]
           → gnss_adapter + ppp-safe
           → 同星多频合并为 obsd_t[] → 一次核心定位调用
```

C++ 用 GetMethodID 查找 getter。签名 `()J` 表示返回 long，`()D` 返回 double，`()F` 返回 float，`()Z` 返回 boolean。当前 CarrierFrequencyHz 的 API 返回 float，不能按 double 调用。JNI 复制数值到 C 结构并释放局部引用，不应跨线程保存 JNIEnv 指针或把 Java 局部引用当作永久对象。

### 原始数据在哪里被用到

时钟字段生成 obs.time；星座和 Svid 生成 obs.sat；发射时标生成 P；ADR 生成 L；伪距率生成 D；C/N0 生成 SNR；状态生成 LLI；频率和 CodeType 生成 code 与槽位。同星不同频率合并后，measurement 数通常大于 obsd_t 卫星数。

NAV、SP3、CLK、BIA、IONEX、VMF3、ATX 来自本地产品文件，不是本次 API 回调自动给出的内容。TXT 预留的卫星坐标列为空，也不代表程序完全没有卫星轨道产品。

当前 GNSS_Live 下的 `ppp_live_obs_*.csv` 保存 adapter 输出，可用于数值对照；它不是逐历元坐标文件。phase/code count 是输入数量，不是最终接受数量。processing_ms 是原生调用耗时，不包括排队、UI 或 CSV 写盘。学习本页时只追踪输入与输出，不进入滤波内部。

<!-- page -->

## 8 跟着工程做一次学习实验

### 一次完整操作

1. 先“选择保存目录”，在内部存储第一层建立并授权 FTPGet 子文件夹；打开定位，授予精确定位。
2. 在室外采集 1—2 分钟，只用于理解流程；停止后等待保存和导出完成。
3. 在公共 FTPGet 目录打开 Raw TXT，找一行 Raw，按列号确认时钟、卫星号、星座、ADR 和 CodeType。
4. 点击转换，查看生成的 OBS。找到同一卫星的 C/L/D/S，检查哪些测量缺失及 LLI 是否有标记。
5. 若本地产品覆盖当前时间，可另测实时链路；查看 Android Studio Logcat 的 PPP_EPOCH 与应用内 GNSS_Live 的观测 CSV。此步骤不是精度验证。

公共目录是完整文件的额外副本。应用内原始 TXT、OBS 和产品仍保留，JNI 继续使用原路径。下载产品也可同步到已授权的公共目录；公共文件夹授权是 SAF 文件授权，与定位权限相互独立。尚未选择目录时，采集仍可保存在应用专属目录。[7]

### 三个自测题和答案

**题一：** 一个事件含 G10 的 1C 与 5Q、E10 的 1C。记录多少行，至少涉及几颗卫星？答：3 条测量行、2 颗卫星；两个“10”属于不同星座。

**题二：** ADRState=3 且 ADR 有效数值，当前实时与离线路径如何处理？答：VALID 与 RESET 同时存在；实时保留 L 并置 LLI_SLIP，离线当前不写该历元 L，并让后续有效相位标记失锁。

**题三：** D=−100 Hz，波长约 0.19 m，对应伪距率方向与大小？答：约 +19 m/s。若算出 −19 m/s，应检查负号和单位。

### 遇到问题先查哪一层

没有 Raw：检查权限、定位开关、注册结果及设备能力。Raw 有但没有 L：检查 ADR State、是否零或非有限值、不确定度和信号识别。实时输入缺信号：检查 ppp-safe、合并频槽及 JNI 字段读取。公共目录没文件：区分应用内保存成功与公共导出成功，检查目录授权、空间及导出提示。

完整 54 列字典见同目录 `ANDROID_GNSS_DATA_GUIDE.md`；文件导出说明见 `PUBLIC_GNSS_FILES.md`。建议依次读回调、formatMeasurementToCsv、parseLine、processEpochs，再读 nativeProcessEpoch。阅读日期为 2026年10月5日；后续修改门限或信号策略时，以最新代码为准。

### 官方资料

- [1] [Android 原始 GNSS 测量概览](https://developer.android.com/develop/sensors-and-location/sensors/gnss)
- [2] [GnssClock 接口与时钟定义](https://developer.android.com/reference/android/location/GnssClock)
- [3] [GnssMeasurement 字段与状态定义](https://developer.android.com/reference/android/location/GnssMeasurement)
- [4] [LocationManager 回调注册接口](https://developer.android.com/reference/android/location/LocationManager)
- [5] [GnssMeasurementRequest 完整跟踪请求](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder)
- [6] [Android 定位权限](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [7] [SAF 文件与目录授权](https://developer.android.com/training/data-storage/shared/documents-files)
