# Android GNSS 工程中文注释与数据链路说明

版本日期：2026-10-05。工程：`E:\android\test\FTPGet`。

本文根据当前本地代码编写，主要解释采集、TXT 格式、RINEX 转换、实时 JNI 输入和界面调度，不展开 PPP 滤波算法。**本次只修改注释，不修改采集/转换规则、信号策略、产品模型或 PPP 参数。** 文中的“当前行为”不等于建议永远采用该行为。

## 1. 推荐阅读顺序

1. `MainActivity.kt`：知道每个按钮进入哪个模块。
2. `GnssDataLogger.kt`：知道原始数据如何进入 TXT，以及每个字段的单位。
3. 本文第 4～8 节：理解接收机时钟、卫星时标、P/L/D 和质量标志。
4. `RinexConverter.kt`：顺着 `convert → parseLine → identifySignals → processEpochs → preprocessRinexEpochs → writeRinexFile` 阅读。
5. `LivePppController.kt → LivePppNative.kt → gnss_jni.cpp`：理解不经过 RINEX 的实时输入。
6. 本文第 12 节：了解两条入口目前的差异，不要仅凭 Q6 数量判定等价。

Kotlin 源码位于 `app/src/main/java/com/example/ftpget/`，原生源码位于 `app/src/main/cpp/`。本文函数名可以直接在 Android Studio 全局搜索定位。

## 2. 项目模块地图

| 模块 | 职责 | 不应误解为 |
|---|---|---|
| `MainActivity.kt` | 权限、按钮、界面、协程调度、调用各业务模块 | GNSS 测量模型或 PPP 真值来源 |
| `GnssDataLogger.kt` | 将公共 Clock 和每条 measurement 写成 Raw CSV | 已经转成 P/L/D 的 OBS 文件 |
| `RinexConverter.kt` | 离线解析 Raw、识别信号、组历元、预处理、写 RINEX OBS | 实时 adapter 的 Kotlin 翻译版 |
| `LivePppController.kt` | 订阅实时事件、工作线程、会话生命周期、观测 CSV | 后台定位服务或滤波器公式 |
| `LivePppNative.kt` | JNI 声明和返回数组字段映射 | 产品下载模块 |
| `gnss_jni.cpp` | 从 Java 对象取字段、检查时序/覆盖、调用 adapter/核心、返回结果 | TXT 解析器 |
| `ppp_live/rtklib/gnss_raw.h` | 不依赖 Android Java 的时钟/测量 C 结构 | RTKLIB `obsd_t` |
| `ppp_live/rtklib/gnss_adapter.c/.h` | 原始结构转 `obsd_t[]`、多频合并、单位/标志转换 | 文件下载器或 PPP 精度优化器 |
| `ppp_live/rtklib/gnss_signal_policy.c/.h` | 当前正式实时 `ppp-safe` 信号准入和优先级 | 所有 RINEX 可写信号的全集 |
| `FtpDownloader.kt` | FTP/HTTP、下载进度、临时文件发布、产品文件校验辅助 | 原始数据采集器 |
| `GpsTimeUtil.kt` | 按系统 UTC 日期构造下载年/年积日/周目录参数 | GnssClock 到精确 GPST 的转换 |
| `FileUtil.kt` | gzip 解压 | RINEX 格式转换或解算 |
| `BundledProducts.kt` | 从 assets 放置 ATX/VMF 格网高程静态文件 | 每次重新联网下载静态产品 |
| `LivePppProducts.kt` | 按首事件时间选本地实时产品/VMF 文件对 | 会话运行中动态换产品 |
| `PostPppProducts.kt` | 按 OBS 实际时段选择后处理产品 | 按今天日期处理所有历史 OBS |
| `RtkEngine.kt` / `rtk_jni.c` | 后处理原生入口 | GnssMeasurement 实时入口 |
| `GnssDataTableView.kt` | 表格显示卫星/测量行 | 对采集数据限流 |
| `GnssVisualViews.kt` | 天空图、信号柱图、显示对象 | PPP 观测是否实际采用的判断 |
| `activity_main.xml` / `res/values` | 控件布局、资源、文字/主题 | 程序处理流程本身 |
| `CMakeLists.txt` / `verify_ppp_profile.cmake` | 原生库编译、核对 PC 核心快照 | PPP 参数设置界面 |

当前两个原生库都使用 **`ppp_live/rtklib` 核心**。另一个 `cpp/rtklib` 目录的存在，不意味着修改它就会改变当前 APK。

共享核心文件有 PC 快照校验，本次没有给 `ppp.c`、`rtklib.h`、adapter、signal policy 等快照文件改注释，以免破坏 PC/Android 同源校验；其字段和接口在本文解释，JNI 外围已添加注释。

## 3. 三条数据流程及操作方法

### 3.1 原始数据采集

```text
“开始采集” → GnssDataLogger.startRecording()
GNSS event = 一个公共 Clock + 多条卫星×信号 measurement
→ 每条 measurement 写一行 54 列 Raw → 有界 Channel → IO 协程 → TXT
“停止采集” → 注销回调 → 关闭 Channel → 等待排空/flush/close
```

建议顺序：授予精确定位权限、开启系统定位、室外稳定放置手机、开始采集、停止采集、确认已保存，再转换文件。`startRecording()` 返回 true 只表示回调注册成功，不保证立即有数据或有有效载波。

### 3.2 离线转换和后处理

```text
“转换” → 找 GNSS_Products 第一层中文件名日期最新的非空 RawData_*.txt
→ RinexConverter → RINEX OBS
“后处理” → PostPppProducts 选 OBS/产品 → RtkEngine → 原生后处理入口
```

转换不要求文件属于当天，重启应用后也能找历史文件；当前没有任意历史 TXT 的文件选择窗口。“最新”按固定宽度文件名排序，不按修改时间。跨时区、手动调手机系统时间、重命名会影响选择。

转换按钮当前未明确禁止读取仍在写入的采集文件，因此请先停止并等待保存完成。相同开始时间生成同名 OBS 时会覆盖旧内容，请先备份需要保留的对照文件。

### 3.3 真正的实时事件入口

```text
“启动实时 PPP” → LivePppController 工作线程订阅 event
首事件：按 GNSS 时间选产品 → nativeInit（本会话一次）
每事件：nativeProcessEpoch(clock, measurements[])
→ android_clock_t / android_meas_t[] → gnss_adapter（ppp-safe）
→ obsd_t[] → 至多调用一次 rtkpos → 返回结果 → 主线程显示
```

**不需要先点“开始采集”才能实时运行。** 实时入口不读 TXT，也不读 RINEX OBS；同时开始 TXT 记录可保存原始数据以便事后回放对照。结束实时会话会释放原生状态，再启动是新会话，不是每秒重新初始化。

### 3.4 文件位置

`getExternalFilesDir("GNSS_Products")` 通常对应：

```text
/storage/emulated/0/Android/data/com.example.ftpget/files/GNSS_Products/
  RawData_yyyyMMdd_HHmmss.txt
  GEOL00CHN_R_YYYYDDDHHMM_00U_01S_MO.rnx
  NAV / SP3 / CLK / BIA / IONEX / VMF3 / ATX / orography 文件
  PPP_Result_<系统毫秒时间戳>.pos（后处理）
  PPP_Result_<系统毫秒时间戳>.pos.trace
```

实时转换后观测另存到 `getExternalFilesDir("GNSS_Live")` 中的 `ppp_live_obs_<时间戳>.csv`。这是应用专属目录，卸载可能清除；新版 Android 的文件管理器访问限制不代表文件没有写出。

## 4. Raw TXT 全部 54 列字段字典

下标是 **0 起始**：`tokens[20]` 指第 21 列。每行是一条信号，不是一颗卫星的全部频点。

`#` 行为注释/设备/表头信息；`Raw` 开头才是测量行。多数 optional 字段缺失留空。31～34 当前用 `-1` 哨兵，但偏差本身可为负，不能泛化为“所有负偏差都无效”。38～53 均是预留空列。

| 下标 | 字段 | 单位/含义 | 当前用途或限制 |
|---:|---|---|---|
| 0 | Raw | 行类型 | 固定 `Raw` |
| 1 | utcTimeMillis | 历史辅助时间 | 当前没有加 GPS→Unix 起点差，不是 Unix UTC ms；转换器不读 |
| 2 | TimeNanos | ns；硬件接收机时钟读数 | 结合 FullBias/Bias 得接收时标 |
| 3 | LeapSecond | s；GPST−UTC 整秒差 | 原始保存；Kotlin 转换当前未动态用此列 |
| 4 | TimeUncertaintyNanos | ns；TimeNanos 1σ | 保存，不直接作 PPP 权 |
| 5 | FullBiasNanos | ns；相对 GPST 的大整数偏移 | GPST 重建必需；不要先转浮点 |
| 6 | BiasNanos | ns；精细偏移，可含小数 | GPST/传播时间修正 |
| 7 | BiasUncertaintyNanos | ns；Bias 1σ | 保存 |
| 8 | DriftNanosPerSecond | ns/s；钟漂 | 保存，不等于伪距率 |
| 9 | DriftUncertaintyNanosPerSecond | ns/s；钟漂 1σ | 保存 |
| 10 | HardwareClockDiscontinuityCount | 计数器 | 变化时须重新判断载波连续性 |
| 11 | Svid | 星座内卫星号 | 和 constellation 联合识别 |
| 12 | TimeOffsetNanos | ns；信号测量相对 Clock 的偏移 | 加入该信号接收时间 |
| 13 | State | 跟踪/码锁/时标位掩码 | P 有效性判定 |
| 14 | ReceivedSvTimeNanos | ns；信号卫星发射时标 | 与同时间系统的接收时标相减 |
| 15 | ReceivedSvTimeUncertaintyNanos | ns；发射时标 1σ | 当前 P 门限 500 ns |
| 16 | Cn0DbHz | dB-Hz；天线端 C/N0 | RINEX S / RTKLIB SNR 来源 |
| 17 | PseudorangeRateMetersPerSecond | m/s；伪距变化率 | 转 D=−PRR/λ |
| 18 | PseudorangeRateUncertaintyMetersPerSecond | m/s；PRR 1σ | 当前 D 门限 10 m/s |
| 19 | AccumulatedDeltaRangeState | ADR 位掩码 | VALID/RESET/SLIP/HALF |
| 20 | AccumulatedDeltaRangeMeters | m；累计距离 ADR | 转 L=ADR/λ |
| 21 | AccumulatedDeltaRangeUncertaintyMeters | m；ADR 1σ | 离线/实时门限不同，见第 12 节 |
| 22 | CarrierFrequencyHz | Hz；载频 | 识别频段/计算波长 |
| 23 | CarrierCycles | cycle；旧完整载波周数 | 已弃用接口；当前 L 不取此列 |
| 24 | CarrierPhase | cycle；旧小数相位 | 已弃用接口；不是 ADR 米数 |
| 25 | CarrierPhaseUncertainty | cycle；旧相位 1σ | 当前不用 |
| 26 | MultipathIndicator | 0未知/1检测到/2未检测到 | 保存，不代表多路径误差大小 |
| 27 | SnrInDb | dB；信噪比 | 不应替代 C/N0(dB-Hz) |
| 28 | ConstellationType | Android 枚举 | 1 GPS/3 GLO/4 QZS/5 BDS/6 GAL |
| 29 | AgcDb | dB；自动增益控制 | 保存；不是定位权重 |
| 30 | BasebandCn0DbHz | dB-Hz；基带 C/N0 | 保存；当前观测 S 用第 16 列 |
| 31 | FullInterSignalBiasNanos | ns；完整信号间偏差 | 含接收机/卫星贡献；当前不直接改 P/L |
| 32 | FullInterSignalBiasUncertaintyNanos | ns；上述偏差 1σ | 缺失写 `-1` |
| 33 | SatelliteInterSignalBiasNanos | ns；卫星信号间偏差 | 不是外部 OSB 文件字段 |
| 34 | SatelliteInterSignalBiasUncertaintyNanos | ns；上述偏差 1σ | 缺失写 `-1` |
| 35 | CodeType | 信号属性，例如 C/I/Q/P/X | 联合星座+载频生成 1C/5Q/2I 等 |
| 36 | ChipsetElapsedRealtimeNanos | ns；单调开机时间 | Clock 有则取 Clock，否则取序列化时 SystemClock；不能算 P |
| 37 | IsFullTracking | 当前空 | 未获取可信实际状态，不能把请求成功写成跟踪成功 |
| 38 | SvPositionEcefXMeters | m；卫星 ECEF X | 预留空列 |
| 39 | SvPositionEcefYMeters | m；卫星 ECEF Y | 预留空列 |
| 40 | SvPositionEcefZMeters | m；卫星 ECEF Z | 预留空列 |
| 41 | SvVelocityEcefXMetersPerSecond | m/s；卫星 ECEF 速度 X | 预留空列 |
| 42 | SvVelocityEcefYMetersPerSecond | m/s；卫星 ECEF 速度 Y | 预留空列 |
| 43 | SvVelocityEcefZMetersPerSecond | m/s；卫星 ECEF 速度 Z | 预留空列 |
| 44 | SvClockBiasMeters | m；卫星钟差距离量 | 预留空列 |
| 45 | SvClockDriftMetersPerSecond | m/s；卫星钟漂距离率 | 预留空列 |
| 46 | KlobucharAlpha0 | α0；振幅常数项(s) | 预留空列，不是 IONEX |
| 47 | KlobucharAlpha1 | α1；振幅一次项(s/半周) | 预留空列 |
| 48 | KlobucharAlpha2 | α2；振幅二次项(s/半周²) | 预留空列 |
| 49 | KlobucharAlpha3 | α3；振幅三次项(s/半周³) | 预留空列 |
| 50 | KlobucharBeta0 | β0；周期常数项(s) | 预留空列，不是 PPP 电离层状态 |
| 51 | KlobucharBeta1 | β1；周期一次项(s/半周) | 预留空列 |
| 52 | KlobucharBeta2 | β2；周期二次项(s/半周²) | 预留空列 |
| 53 | KlobucharBeta3 | β3；周期三次项(s/半周³) | 预留空列 |

Klobuchar α/β 分别用于延迟振幅/周期多项式，按磁纬半周单位的幂次使用；各阶量纲不能统一写成米。当前采集器不填这些系数，所以不能依据空列判断导航产品缺失。

## 5. 时钟和伪距：最容易混淆的部分

### 5.1 四种不同的“时间”

| 时间 | 来源 | 正确用途 |
|---|---|---|
| 手机系统日期/Unix UTC | `Date()`、系统日历 | 文件名、下载日期；不能替代观测时标 |
| 硬件 TimeNanos | `GnssClock` | 联合偏差重建 GPST |
| GPST 历元 | Time−FullBias−Bias（再考虑公共修正） | 观测 `time`、产品匹配、周/周内秒 |
| monotonic/elapsedRealtime | 单调时钟 | 耗时、回放调度；不能与卫星发射时间求伪距 |

接收机公共时间近似为：

```text
t_GPS_ns = TimeNanos - FullBiasNanos - BiasNanos - clock_correction_nanos
某信号接收时刻 = t_GPS_ns + TimeOffsetNanos
真正 Unix UTC_ms = 315964800000 + t_GPS_ns/1e6 - LeapSecond*1000
```

`FullBiasNanos` 是接收机硬件时标与 GPST 的大偏移，不是最终 PPP 估计的接收机钟差。不要把两者混为同一个参数。绝对时间先用 64 位整数计算，再处理 Bias 小数，避免纳秒精度被很大的浮点绝对值吃掉。

### 5.2 星座时间系统

`ReceivedSvTimeNanos` 不是完整 Unix 时间；按信号的星座/跟踪状态解释。当前离线转换支持明确的周内时或 GLO 日内时，并拒绝时标仍不明确的码观测。

| 星座 | 当前转换做法 |
|---|---|
| GPS / Galileo / QZSS | 接收机 GPST 取周内时，与已明确的发射周内时比较 |
| BeiDou | 将接收机周内时转为 BDT，使用 GPST−14 s |
| GLONASS | 用 UTC+3 h 的日内时，当前闰秒固定为 18 s |

GPS/GAL/BDS 的跨周时差超过半周就加减一整周；GLO 超过半日就加减一整日。它们是时标回卷处理，不是对大伪距误差的任意修正。

```text
传播时间 Δt = 对齐后的接收时标 - 发射时标
P(m) = 299792458 × Δt(s)
```

当前要求 Δt 在 0.001～0.200 s；这只是原始时标合理性检查，不是“PPP 允许 60,000 km 残差”。星历、卫星钟差等后续改正不在采集器中完成。

### 5.3 静默钟跳

离线转换和 JNI 都有公共时钟历史：当 `FullBias` 隐式重定时而 HCDC 未增加，且相邻 Clock 间隔在 0～5 s、大跳变至少 50 μs 时，累计公共 `clock_correction_nanos`。整事件信号使用相同修正，并标记时钟不连续。

HCDC 真变化仍按不连续处理。这里不是逐星滤除异常，也不是把 FullBias 固定为第一次测量值。Raw TXT 仍保存原始 Clock，便于审计。

### 5.4 UTC 命名不等于 UTC 观测

`MainActivity` 把本地文件名时间解析为日期，再用 UTC 生成 RINEX 文件名；实际 RINEX `TIME OF FIRST OBS` 标成 GPS，观测体也是 GPST。`getRinexTime()` 使用 UTC 时区的 Calendar 只是防止本地时区介入，并没有把 GPST 变成 UTC。

TXT 的历史 `utcTimeMillis` 辅助列缺少 GPS→Unix 起点偏移，**不要直接传给 Date()**；当前转换器不读此列，因此本次不修改它以免改变格式行为。

## 6. 相位、多普勒和载噪比

波长 `λ=c/f`，f 单位 Hz，λ 单位 m/cycle。以 1575.42 MHz 为例，λ 约 0.1903 m/cycle。

```text
L(cycle) = AccumulatedDeltaRangeMeters / λ
D(Hz) = -PseudorangeRateMetersPerSecond / λ
核对关系：L×λ = ADR；-D×λ = Android PRR
```

ADR 是累计的载波相关距离，不是绝对伪距；有未知初始模糊度，不能要求 `P≈L×λ`。ADR 的绝对值大不一定异常，要看连续性、状态和变化量。

旧 `CarrierCycles`/`CarrierPhase` 是不同 API 字段，当前不参与 L 的构造。不要将 `ADR + CarrierPhase` 混合，也不要自行翻转 L 的符号。

RINEX S 写天线端 C/N0(dB-Hz)。RTKLIB `obs.SNR[]` 使用项目定义的 `SNR_UNIT` 量化，dump 时乘回 `SNR_UNIT`；不能直接把底层整数当成 dB-Hz，也不能替换为 `SnrInDb`。

P、L、D 独立检查：有相位并不保证发射时标有效；没有 P 也不必丢掉合法 L/D。**观测缺失、失锁标志、滤波拒绝是三件不同的事。**

## 7. State、ADR State 和 LLI

### 7.1 跟踪 State（TXT[13]）

| 位值 | 名称 | 含义 |
|---:|---|---|
| 1 | CODE_LOCK | 码锁定 |
| 8 | TOW_DECODED | 卫星周内时已解码 |
| 16384 | TOW_KNOWN | 卫星周内时已知 |
| 64 | GLO_STRING_SYNC | GLO 字符串同步 |
| 128 | GLO_TOD_DECODED | GLO 日内时已解码 |
| 32768 | GLO_TOD_KNOWN | GLO 日内时已知 |

多个状态可以同时存在，例如 9=1 OR 8；判断应写 `(state and mask)!=0`，不是 `state==mask`。当前 GPS/GAL/BDS/QZS 的 P 需要 CODE_LOCK 且 TOW 已解码/已知；GLO 采用自己的 STRING_SYNC+TOD 条件。

### 7.2 ADR State（TXT[19]）

| 位值 | 名称 | 含义 |
|---:|---|---|
| 1 | VALID | ADR 当前值有效 |
| 2 | RESET | ADR 有重置 |
| 4 | CYCLE_SLIP | 检测到载波周跳 |
| 8 | HALF_CYCLE_RESOLVED | 半周问题已解决 |
| 16 | HALF_CYCLE_REPORTED | 有半周状态报告 |

“半周未解决”在当前代码中判断为 REPORTED 有、RESOLVED 无。VALID 与 RESET/SLIP 可以同时置位，因此不能把整个数值仅判断为 1。

### 7.3 输出 LLI

RTKLIB/RINEX 的 `LLI_SLIP=1` 通知载波弧段失锁/重新初始化；`LLI_HALFC=2` 表示半周相关标志，不是 Android RESET=2。两个系统的位值不同，须转换，不能直接复制 `adrState` 到 LLI。

实时 adapter：有效 L 保留，即便 RESET/SLIP 同时存在，也通过 LLI_SLIP 通知核心；半周未解决置 LLI_HALFC。

离线转换：RESET/SLIP 当历元不写 L，记 pending，下一次有效 L 补失锁；半周状态变化才置失锁，不每历元写半周位。这是现有入口差异，不是本次增加的规则。

## 8. 离线转换器的参数和预处理

| 参数/条件 | 当前值 | 含义/动作 |
|---|---:|---|
| 发射时标 uncertainty 上限 | 500 ns | 超出则不生成 P |
| PRR uncertainty 上限 | 10 m/s | 超出则不生成 D |
| 离线 ADR uncertainty 上限 | 0.50 m | 超出则不生成 L |
| 传播时间 | 0.001～0.200 s | 不合理时差不生成 P |
| 历元切分 | 接收时间变化 >100 ms | 同历元信号合并；不是强制四舍五入到整秒 |
| 相邻历元检测范围 | 0.2～5.0 s | 超出则跳过该差分检测 |
| 公共项最少数量 | 3 条/系统 | 不足则跳过公共中位数检测 |
| 相位-多普勒跳变 | 0.50 m | 去系统公共中位数后超限置 LLI_SLIP，保留 L |
| 码-多普勒跳变 | 30 m | 去公共中位数后超限将 P 清零 |
| MW 双频跳变 | 5 m | 相邻 MW 超限将两个信号置失锁 |
| 近零阈值 | 0.0001 | 按 P/L/D/S 各自数值单位使用，不能都解释成米 |
| 信号名义频率识别容差 | 10 kHz | 不是观测精度或产品误差 |
| MAX_FRQ | 5 | 每系统最多存五种信号身份，不是 RTKLIB NFREQ |

相位-多普勒创新量为：

```text
innovation_L_m = λ × [L_current - L_previous + 0.5×(D_previous+D_current)×dt]
```

码预测为 `P_previous - λ×平均D×dt`，比较当前 P 与预测 P 的差。仅用于检测，**没有用预测 P 替换原始 P，也没有在转换器内做伪距平滑**。

MW 检测要求不同载频、两信号都带 P/L，采用信号数组第 0 项与后续项配对。手机码噪声会影响此组合；此处只是已有保守检查，不代表每个 5 m 伪距偏差都被删除。

`processEpochs` 按文件顺序处理，不预先排序。数组顺序来自信号名排序（频段字符、属性字符），不是强制物理频率顺序。同信号重复行后赋值可能覆盖前值；超过五种信号身份的数组下标会被跳过，需要特别留意多属性文件。

### 转换统计如何理解

`rawMeasurements` 是成功解析的 Raw 行数，不含所有坏行；`codeAccepted/phaseAccepted/dopplerAccepted` 是初次通过检查的赋值次数，不保证等于写出 OBS 的最终数量。预处理删 P、重复覆盖、无 P/L 卫星整行不输出都会产生差异。

`unsupportedSignals` 不是所有未支持信号的完整分类：部分未识别信号落在 `invalidTracking`，部分槽超限直接跳过。`adrStateSlip`、`dopplerSlipDetected`、`mwSlipDetected` 来源不同、可能重叠，不能相加当作独立周跳次数。

## 9. 信号映射：识别、写出、进入 PPP 分开看

### 9.1 当前 Kotlin RINEX 转换支持的身份

| 星座 | 频段/载频 | CodeType | 输出信号 |
|---|---|---|---|
| GPS | L1 1575.42 MHz | C/S/L/X/P/W/Y/M/N | 1C 等 |
| GPS | L5 1176.45 MHz | I/Q/X | 5I/5Q/5X |
| Galileo | E1 1575.42 MHz | A/B/C/X/Z | 1A 等 |
| Galileo | E5a 1176.45 MHz | I/Q/X | 5I/5Q/5X |
| Galileo | E5b 1207.14 MHz | I/Q/X | 7I/7Q/7X |
| BeiDou | B1I 1561.098 MHz | I/Q/X | 2I/2Q/2X |
| BeiDou | B1C 1575.42 MHz | D/P/X | 1D/1P/1X |
| BeiDou | B2a 1176.45 MHz | D/P/Q/X | 5D/5P/5Q/5X |
| BeiDou | B2I 对应频段 1207.14 MHz | I/Q/X | 7I/7Q/7X |
| GLONASS | G1 1602+k×0.5625 MHz | C/P | 1C/1P |
| GLONASS | G2 1246+k×0.4375 MHz | C/P | 2C/2P |
| QZSS | L1 1575.42 MHz | C/S/L/X/Z/B | 1C 等 |
| QZSS | L5 1176.45 MHz | I/Q/X/D/P/Z | 5I 等 |

GLO 通过报告频率估算频道 k 并写 `GLONASS SLOT / FRQ #`；当前代码还拒绝 svid>80 的 GLO 测量。QZSS 编号>192 时减 192。上述是代码实际识别集合，不保证硬件能提供全部属性，也不保证每种产品都支持。

`1C` 是信号身份；`C1C` 是此信号的码观测、`L1C` 是相位、`D1C` 是多普勒、`S1C` 是 C/N0。BDS `2I` 中的 2 是 RINEX 频段编号，不表示“北斗第二个频率一定是 B2”。

### 9.2 当前正式实时 ppp-safe

| 系统 | RTKLIB 槽（显示为 1 起始） | 当前允许身份 |
|---|---:|---|
| GPS | 1 / 3 | 1C / 5Q |
| Galileo | 1 / 3 | 1C / 5Q |
| BeiDou | 1 / 2 / 3 | 2I / 7I / 5P |

其余身份即使识别成功也可能被 policy 排除。当前 BDS 1P 不占正式主参考槽；5Q 的排除原因标签是 `NO_BDS_C5Q_RCB`；GAL E5b 被当前正式链路隔离。当前实时 adapter 不提供 GLONASS/QZSS 原始转换入口，Kotlin 能写这些系统不等于实时已启用。

准入策略与产品 OSB 是否实际覆盖是不同维度；不能根据文件扩展名断言某信号有可用 OSB。本次没有改变这些策略，也没有因文档新增信号。

## 10. RINEX 写出细节

- 版本 3.05，混合系统 OBS；文件头标签起于第 61 列。
- `SYS / # / OBS TYPES` 每个身份写 C/L/D/S 四种量，每行至多 13 项，超出续行。
- 头部 `APPROX POSITION XYZ` 是 ECEF 米，不是纬经高；`ANTENNA: DELTA H/E/N` 是高/东/北米。
- `UNKNOWN` 天线型号是占位，不代表已完成手机天线标定。
- 历元 `>` 行用 GPST 日历，卫星数是含有效 P/L 的卫星数。
- 每个观测域 16 字符：14 数值 + 1 LLI + 1 SSI；当前数值三位小数，SSI 空。
- LLI 只写在相位域。缺失观测写空格，不写“0 米”伪装测量。
- 只有 D/S 而无 P/L 的卫星不写出；CN0 本身也不能证明有有效测距观测。

因此 RINEX 与实时 dump 比较时必须考虑三位小数造成的舍入差，不能要求原始 double 文本逐字符一致。共同信号应按历元、卫星、signal code 匹配，分别比较 P(m)、L×λ(m)、−D×λ(m/s)、C/N0 和 LLI。

## 11. JNI 输入、输出和诊断字段

### 11.1 输入 C 结构

`android_clock_t`：`time_nanos`、`full_bias_nanos` 为 int64 ns；`bias_nanos` 为 double ns；`has_full_bias` 为可用性标志；`hardware_clock_discontinuity_count` 为硬件计数；`clock_correction_nanos` 为累计静默跳变修正；`clock_discontinuity` 为当前合成中断标志。

`android_meas_t`：星座、卫星号、信号时间偏移、跟踪 State、卫星发射时间/不确定度、C/N0、PRR/不确定度、ADR 状态/距离/不确定度、载频、CodeType。它们和第 4 节同名字段的单位一致，不包含最终 PPP 坐标。

JNI 方法签名：`J`=Java long、`D`=double、`F`=float、`I`=int、`Z`=boolean。`getCarrierFrequencyHz()` 返回 float，不能误写 `()D`。每次调用的 JNIEnv/局部对象引用不应跨线程保存，代码复制测量值到 C++ vector，再释放局部引用。

单事件 measurement 上限 512，超过会拒绝整个事件，而不是截取前 512 颗卫星。adapter 按 satellite 合并多频，容量由 `MAXOBS` 控制；`obsd_t.code[f]` 是 RTKLIB 内部代码编号，不是 CodeType 的 ASCII 值。

JNI 在转换前检查产品覆盖，并拒绝重复/逆序历元；只有有可用观测的事件才进入一次 rtkpos。接收机状态跨事件保留。

### 11.2 九个产品路径参数

固定顺序是 `NAV, SP3, CLK, BIA, IONEX, VMF-left, VMF-right, orography, ATX`。路径数和顺序是接口契约，不要按字母排序。

NAV 是导航信息；SP3 是精密轨道；CLK 是精密钟差；BIA 是偏差产品；IONEX 是电离层格网；VMF 是对流层格网；orography 是 VMF 格网参考椭球高程，不是另一份湿延迟；ATX 是天线参考信息。本阶段只解释文件职责，不调整其模型。

### 11.3 返回数组固定 18 项

| 下标 | Kotlin 字段 | 含义/单位 |
|---:|---|---|
| 0 | week | 完整 GPS 周 |
| 1 | tow | GPST 周内秒(s) |
| 2 | q | sol.stat；0无解、5单点、6浮点PPP |
| 3 | satellites | 结果有效卫星 sol.ns |
| 4～5 | latitude/longitude | 纬度/经度(°) |
| 6 | height | 椭球高(m)，不是海拔 |
| 7～9 | x/y/z | ECEF 坐标(m) |
| 10～12 | stdX/stdY/stdZ | ECEF 分量标准差(m)，不是 ENU 或真值误差 |
| 13 | processingMs | JNI 处理耗时(ms) |
| 14 | rawCount | 输入 measurement 数 |
| 15 | observedSatellites | adapter 输出 obsd_t 数 |
| 16 | phaseCount | adapter 输出非零 L 信号数 |
| 17 | codeCount | adapter 输出非零 P 信号数 |

`processingMs` 包含 JNI 字段读取、检查、转换、处理，不包括 worker 排队、Kotlin CSV 写盘和界面显示，不能当作完整端到端延迟。`q=6` 不是厘米级精度证明；std 是内部估计，不等于实际误差。

### 11.4 实时 CSV 和日志

`ppp_live_obs_*.csv`：`week,tow,sat,signal,slot,P_m,L_cycle,D_Hz,CN0_dBHz,LLI,code`。

这里 sat 为 Gxx/Exx/Cxx，signal 为 1C/5Q/2I，slot 显示从 1 开始，最后 code 是内部整数编号。CSV 在进入 rtkpos 前生成，是**输入观测诊断**，不代表 PPP 接受/拒绝统计。

Android Studio Logcat 可按以下标签过滤：

- `GnssLogger_ppp`：采集注册/保存失败、写盘完成。
- `RinexConverter`：读取、静默钟跳归一化和转换统计。
- `LivePppController`：实时会话、产品初始化、被跳过的事件。
- `PPP_LIVE`：产品加载、时序/覆盖错误、逐历元 raw/obs_sat/used_sat/phase/code/Q/耗时。
- `PPP_OBS`：逐信号输入观测。

实时控制器当前持久化的是观测 CSV，返回坐标用于界面显示；**不要误以为已经保存了逐历元定位轨迹、ZTD 和完整滤波 trace**。后处理的 .pos/.trace 是另外一条流程。

## 12. 当前两入口差异：不能由注释掩盖

| 项目 | Kotlin TXT→RINEX | Android live→JNI→adapter |
|---|---|---|
| ADR uncertainty | 固定 ≤0.50 m | 初始化设置 ≤1.0 m |
| ADR RESET/SLIP | 当历元不写 L，恢复后补失锁 | 有效 L 保留并置 LLI_SLIP |
| 半周状态 | 变化时置失锁 | 未解决时置 LLI_HALFC |
| 信号范围 | 第 9.1 节广泛识别/写出 | 当前 ppp-safe 小集合 |
| 重复信号选择 | 后续有效赋值可能覆盖 | policy 优先，再观测质量选择 |
| 时间偏移精度 | TimeOffset `.toLong()` 截亚纳秒 | adapter 保留 double 偏移参与差分 |
| 观测文本精度 | 三位小数 | obsd_t 内部双精度/float及 SNR 量化 |
| 预处理 | 转换器有独立 Doppler/MW/码跳检查 | adapter 后由现有核心处理，不能假定规则逐条等价 |
| 缺失字段 | safeXXX 常回退 0 | Clock 检查可用性，部分数值仍默认 0 |

这些差异在本次之前已存在。本次没有偷偷放宽门限、变更相位或启用信号。将来要统一，需要单独设计实验，并按共同观测 P/L/D/code/LLI 和坐标/状态核对，不应仅改常量后宣称精度改善。

## 13. 线程、生命周期及设备限制

采集器 Channel 容量是 10000 **测量行**。trySend 满/关闭则计数，不阻塞 GNSS 回调；尾行 `DroppedMeasurements` 是入队失败数，不能检测芯片自身没有上报的事件，尾行自身 trySend 也可能失败。短时间写盘卡顿有缓冲不等于永不丢数据。

UI 的测量表每秒刷新、最多显示 15 条，只影响显示，不裁剪采集 TXT 或实时事件集合。卫星界面的 `usedInFix` 是 Android 系统导航是否采用，不是 RTKLIB PPP 采用标志。地图/设备坐标来自系统 Location，不是 PPP 结果。

`requestLocationUpdates(GPS_PROVIDER,1000L,0f,listener)` 是定位更新请求：1000 ms 是请求间隔，0 m 是不按位移筛选。它不能保证原始事件严格 1 Hz。`setFullTracking(true)` 是对完整跟踪的请求，不保证所有机型有效，不保证全频段或无周跳。

当前工程是 Activity + callback/线程，不是持有 ForegroundService 的长期后台 GNSS 服务。屏幕常亮不是 WakeLock；锁屏/后台/节电策略可能影响原始数据。`onPause` 注销的是 UI 回调，不能由此推断所有独立采集/实时回调都自动停止；实际测试后应主动点击停止。

实时路径要求 Android 10+（CodeType）；项目 minSdk=28 不代表 Android 9 也能使用实时功能。旧 Carrier 接口和部分 callback 接口编译时可有弃用警告，本次不为消除警告改行为。

## 14. 产品选择与常见排查

实时普通产品按本地修改时间选最新非空文件；文件存在不证明观测时间被覆盖，JNI 检查 SP3/CLK、IONEX、BIA。VMF3 文件名 `VMF3_yyyyMMdd.H00/H06/H12/H18` 用 UTC 解析，首历元 13:00 选 H12/H18；正好 H12 则左=H12、右=H18。

实时产品在初始化时加载，不在每秒重选；长时间会话跨出已加载覆盖范围不能只靠下载新文件继续运行。后处理另按 OBS 实际时段选择，不能用实时“最新修改时间”规则推断历史数据用了哪份产品。

常见现象及首先检查的证据：

| 现象 | 优先查看 |
|---|---|
| 点击采集但 TXT 无 Raw | 权限/定位开关、注册日志、文件字节数、实际 event 是否到来 |
| TXT 大量空 L | ADR_VALID、RESET/SLIP、ADR uncertainty 分布；离线 0.50 m 门限 |
| 只显示 15 条 | UI 显示限制，不要立即判定 TXT 丢观测 |
| 转换到意外文件 | Raw 文件名时区/日期/重命名、目录内最新文件 |
| L 或 D 符号有疑问 | 对照 L×λ=ADR、−D×λ=PRR；不要根据最终高程盲目翻符号 |
| 输出卫星数少于测量条数 | 同星多频合并、信号准入、有效 P/L；这通常不是丢历元 |
| PRODUCT_TIME_ERROR | Clock 重建 GPST、产品内部首末时间；不是“下载成功就一定能解算” |
| EPOCH_ORDER_ERROR | 相邻事件的周/周内秒、Clock 不连续/重复时间 |
| Q6 多但坐标不一致 | 共同信号输入、LLI、额外信号、产品/状态；不只比较 Q6 |
| std 小但真值误差大 | std 是内部估计；核对椭球高/参考点/天线位置及共同链路 |

## 15. 本次注释变更和验证方法

本次添加/完善注释的文件：`GnssDataLogger.kt`、`RinexConverter.kt`、`MainActivity.kt`、`LivePppController.kt`、`LivePppNative.kt`、`LivePppProducts.kt`、`GpsTimeUtil.kt`、`gnss_jni.cpp`。未修改 PPP 核心、adapter 或 policy。

验证采用“本次修改前本地快照”，不是 Git HEAD（工程原本已有未提交修改）：

1. 对新增注释涉及的源码去除注释后比较代码 token，确认逻辑/常量/字符串不变。
2. 对共享核心目录逐文件比较 SHA-256，确认全部未动。
3. 执行 `:app:testDebugUnitTest` 和 `:app:assembleDebug`；不使用不存在的 `:app:testClasses`。

最终检查结果另见同目录 `ANDROID_GNSS_COMMENT_VALIDATION.md`。未连接手机开展新采集测试，编译成功不能替代设备长时稳定性验证。

## 16. 周边模块参数速查

下载模块不改变 Raw 观测。`server` 是 FTP 主机，`remoteDir` 是远端目录（如周目录），`savePath` 是本地目录而非单文件。`productStartDate` 是用于筛选文件名前缀的年+年积日字符串，`productSuffix` 如 `ORB.SP3`/`CLK.CLK`；WHU 函数在匹配集合中按文件名选最新，不能将其理解成直接按观测内部时段自动筛所有产品。

通用偏差下载函数的 `yearStr`/`doyStr` 组成日期字符串，`keyword` 是文件名包含筛选词。VMF 下载的 `year/month/day` 是文件命名日期，函数下载 H00/H06/H12/H18 四期；`username/password` 是认证信息，不应放进日志或版本库。`onProgress(String)` 是状态文字回调，`onTransferProgress(fileName,downloaded,total)` 是字节进度，不是 GNSS 历元进度；UI 应切回主线程更新。FTP 端口 21、二进制/被动模式和超时是网络参数，不是 PPP 参数。

`FileUtil.unGzip(gzipFile,outFile)` 的输入是压缩包，输出是解压文件；成功只证明流解压完成，不证明产品内历元覆盖，且写目标时会覆盖同名文件。`BundledProducts.ensure(context,directory,status)` 只从 assets 放置静态文件：有效已有文件保留，存在但无效的目标会报错而非默默覆盖。

表格参数：`GnssTableColumn.title` 是标题，`widthDp` 是屏幕逻辑宽度（不是物理像素），`gravity` 是文字对齐；`GnssTableRow.values` 是已经格式化的显示字符串，`system` 用于配色，`usedInFix` 是显示强调信息。`SatelliteDisplay` 中 `cn0` 为 dB-Hz，`elevation` 为高度角(°)，`azimuth` 为方位角(°)，`svid` 为星座内编号；这些绘图字段不直接改动 obsd_t 或权重。

下载缓存辅助函数存在，不等于当前每个下载入口都会跳过已有文件；真实行为应查看被调用路径，不根据函数名字推断。本文不修改此前已确定的下载策略。

GitHub 上传准备阶段已将 VMF3 认证信息迁移到 Git 忽略的 `vmf3.local.properties`，构建时生成 BuildConfig 字段，原下载流程保持不变。新电脑配置方法和 APK 安全边界见 [VMF3 本地账号配置](LOCAL_VMF3_CONFIG.md)。这项迁移发生在“仅添加注释”验证之后，不属于此前注释 token 一致性检查。

## 17. 字段定义参考与进一步阅读

实现行为以本地源码为准。API 字段可用性、返回类型和系统请求语义参考官方资料：

- [Android GnssClock API](https://developer.android.com/reference/android/location/GnssClock)
- [Android GnssMeasurement API](https://developer.android.com/reference/android/location/GnssMeasurement)
- [Android GnssMeasurementsEvent API](https://developer.android.com/reference/android/location/GnssMeasurementsEvent)
- [Android GnssMeasurementRequest.Builder API](https://developer.android.com/reference/android/location/GnssMeasurementRequest.Builder)

建议实验时同时留存 Raw TXT、转换 OBS、实时 obs dump、产品名称/时间覆盖、程序版本和日志。阅读顺序可以先“每个字段是什么”，再“如何生成一条观测”，最后“核心是否采用了它”；不要从坐标结果倒推所有原始字段都一定正确。
