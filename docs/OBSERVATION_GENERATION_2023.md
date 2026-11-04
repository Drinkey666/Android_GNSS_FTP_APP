详见 E:/GNSS/PPP_only/docs/ANDROID_OBSERVATION_GENERATION_2023_REPORT.md

# Android GNSS 观测生成一致性修复（2026-10-30）

本轮已修改 PC Raw、Android JNI 与 Kotlin RINEX 的实际生产源码，并完成同输入离线验证；不是继续调整 PPP。旧正式 PC EXE 保留，新 PC EXE 独立构建，APK 已构建但没有安装或进行修改后的室外真机采集。

## 1. 依据与范围

核对 [Zangenehnejad 等（2023）§2.1–2.3、式2–4](https://www.mdpi.com/1424-8220/23/2/777) 的接收时刻、ADR 与伪距率换算，以及 [Android GnssMeasurement 官方字段定义](https://developer.android.com/reference/android/location/GnssMeasurement)。论文全文站点有访问限制，使用官方可检索正文/PDF内容核对相关公式，没有声称本地下载完整 PDF。

本次采用用户明确指定的固定钟段方案。上一轮 per-system Rx/Tx 判别候选不是其完全等价实现，因此没有叠加旧50 μs公共修正、Doppler对齐或趋势拟合。旧候选及其反例保留为历史证据，不能作为本次默认入口。未调整 R/Q/P0/GIM/抗差或 Joseph；没有重跑旧 PPP 精度消融。旧 CaptureCodePhaseRepair/CodePhaseCandidate 仍是默认关闭的独立预测实验，PPP/RINEX不读取，本次没有将其当作规范观测生成或重复叠加。

## 2. 确认的问题与修复

1. 旧50 μs混合钟参考会漏过256 ns偏差更新，使P产生76.746869 m参考台阶，而ADR未作对应变换。PC与JNI共用新的 `gnss_clock_reference.h`，Kotlin使用同约定；连续钟段固定首个有效FullBias和Bias。
2. JNI之前可把VALID+RESET/SLIP的ADR当作相位样本；离线转换又采用不同的ADR不确定度上限。现均不输出RESET/SLIP当期的连续L，保留pending reset直到恢复。离线ADR上限对齐既有Native的1.0 m，是入口一致性修复，不是改变滤波R。
3. 半周状态原输出不完整。有效但半周未解决的相位写LLI bit1（数值2），状态转换/重置写bit0；两者可以同时为3。单星ADR事件仅作用于该信号，不重设共享钟。
4. 缺失/坏格式PRR、ADR及其不确定度原可能被填成0而变为有效数据。PC/Kotlin解析器改为缺失/非有限，真实0 Doppler仍可输出，符号不取绝对值。TimeNanos和FullBias严格按64位整数读取。
5. RINEX旧100 ms合并会忽略实际TimeOffset；固定5槽又可能与动态信号头不符。现按真实接收时间分组，只按RINEX 7位秒小数舍入至100 ns；不整秒对齐、不重采样。7信号/28观测类型的头与正文均有回归覆盖。
6. GLONASS生成按FDMA实际频道k计算波长，G1/G2、UTC+3 h与GPS−UTC均显式处理；无效/过大/NaN频率在整数频道转换前拒绝。这没有启用正式PPP里原先关闭的GLONASS开关。
7. Raw辅助 `utcTimeMillis` 确实漏加GPS→Unix的315964800000 ms。已修正未来日志该辅助列；API原字段2–53、54列格式及历史Raw原件不改。辅助UTC元数据使用当期API估计，不参与固定参考的P/L/D或RINEX历元时间构造。
8. 将旧离线code−D/MW/经验预处理隔离为显式 `applyLegacyQc=true`；默认转换只生成观测及转发API失锁证据，不暗中改变P或增加经验检测。PPP内现有质量控制仍保持。
9. 同一信号/采样时刻的重复记录只选择一整条观测，使用与通用Native入口一致的排序并保留首个同分项；未选行明确标记DUPLICATE_NOT_SELECTED，避免混用不同源行的P/L/D或让NaN相位获得有效相位加分。

## 3. 实际实现位置

- `clock`：`E:\GNSS\PPP_only\src\gnss_clock_reference.h:41`
- `integer_time`：`E:\GNSS\PPP_only\src\gnss_clock_reference.h:27`
- `adapter`：`E:\GNSS\PPP_only\src\gnss_adapter.c:128`
- `parser`：`E:\GNSS\PPP_only\src\gnss_replay.c:236`
- `jni`：`E:\android\test\FTPGet\app\src\main\cpp\gnss_jni.cpp:148`
- `kclock`：`E:\android\test\FTPGet\app\src\main\java\com\example\ftpget\ObservationClock.kt:25`
- `rinex`：`E:\android\test\FTPGet\app\src\main\java\com\example\ftpget\RinexConverter.kt:238`
- `logger`：`E:\android\test\FTPGet\app\src\main\java\com\example\ftpget\GnssDataLogger.kt:264`

PC改动：`src/gnss_raw.h`、`gnss_adapter.h/.c`、`gnss_replay.c`，新增 `gnss_clock_reference.h`。
Android改动：`gnss_jni.cpp`、`ObservationClock.kt`（新增）、`RinexConverter.kt`、`GnssDataLogger.kt`；活动 `cpp/ppp_live/rtklib` 同步4个观测文件，更新两份SHA清单和CMake检查数32→33。另更新/新增FullBias及ObservationGeneration单测。其余29份Android内核文件SHA保持，原52份PC保护文件中48份保持，仅上述4份旧观测文件有授权修改；旧冻结清单不改写。

## 4. 时间、钟段和单位约定

```
tRx_ns = (TimeNanos - FullBias_ref) - Bias_ref + TimeOffsetNanos
P_m = c * wrap(tRx_in_constellation - ReceivedSvTimeNanos)
lambda_m = c / f_actual_signal
L_cycle = ADR_m / lambda_m
D_Hz = -PRR_mps / lambda_m
```

大整数先相减，Bias/TimeOffset分为整数纳秒和小数纳秒；显式检测溢出/NaN，不把无效值改成0。GPS/GAL/QZSS周边界、BDT=GPST−14 s、GLO日边界及UTC+3 h分别处理；优先有效API闰秒，缺失时用日期表，历史时段不能一律18 s。

每次新会话/文件创建独立钟上下文；HCDC改变、硬件TimeNanos回退或显式确认事件建立新钟段。缺少新参考时保持pending，不能沿用上一段。缺失当期FullBias可以沿用**同一已建立钟段**的参考。采样间断本身和单星ADR重置均不重设钟参考。归一化标记避免provider与adapter重复应用。文件按记录顺序处理，不把乱序排序后伪装成连续观测。

Android频率字段来自Float，不把1575420000f的二进制误差当作物理载频漂移。根据星座、频段、CodeType识别实际信号，再使用其物理名义频率；GLONASS使用实际FDMA频道，而非GPS波长。C/N0按RTKLIB的0.001 dB-Hz存储单位核验，不做35×0.001这类二次换算。

RINEX保留GPST；100 ns量化误差最多50 ns，原接收纳秒和分数保留在审计sidecar；未用Doppler传播P/L到整秒。Native `obsd_t`一颗卫星只有一个时间，若同星多信号TimeOffset不同，明确返回 `OBS_TIME_CONFLICT` 而不伪造对齐；Kotlin可输出不同实际历元。本轮没有扩展PPP为异步多时标滤波器。

## 5. 回归、构建与同输入验证

- 新Native：OBS_GENERATION_2023: passed=51 failed=0。
- 既有回归：phase1 45、phase2 26、physical_atmosphere 58、rapppid_estimator 47、covariance_fix 39，共215项全部通过。
- JVM：193项，186通过，7项可选网络/外部环境测试跳过，0失败/错误；本次四Raw回放与FullBias专项均实际运行，没有跳过。
- PC Release x64全量独立Rebuild成功；Android四ABI Debug原生构建与assembleDebug成功。原EXE仍为 `063f833581648fb1835a5413e8036c491690a39972eddeeae09f1997b27f49e5`；新EXE `validation/observation_generation_2023_v1/pc_build/PPP_only.exe`。
- APK：`E:\android\test\FTPGet\app\build\outputs\apk\debug\app-debug.apk`，SHA256 `c5b570ffbd391c29a7e0daf32931623efcc0e3f7edc9a36ff6ec1f26c956ff58`；**未安装**。
- 四份原始Raw SHA与前轮保存值一致：4270个回调、308324行；297210行受支持转换的原字段/接收时刻/固定参考/ADR/PRR逐项核验。

|输入|回调|全部审计Raw行|Native共同身份行|RINEX数值核验|LLI差异|
|---|---:|---:|---:|---:|---:|
|K70_early|709|49768|38447|125314|142|
|K70_late|660|44750|36244|112405|68|
|K70_oct21|1341|106990|86346|255536|358|
|K70_oct25|1560|106816|83009|254616|76|

共244046条共同身份行P精确一致，L最大差约9.1e−13 cycle；D先按Native float表示量化后差≤5.1e−10 Hz。RINEX共747871项核验，差均在3位小数舍入界限内；未发现无法对应源行的Native记录。

LLI的644处差异均限定于BDS 1P/2I竞争同一Native频率槽的卫星：Native只维护被选槽的弧历史并在信号切换重启，RINEX分别保留实际信号独立历史。已单列计数，**没有声称Native与全信号RINEX拥有相同行集合/相同LLI序列**。正式PPP-safe筛选表没有扩大。

## 6. 修改前后：转换引入的参考台阶

修改前使用备份的实际C adapter、解析器和归一化函数重新生成同四份输入；不是用新实现假装旧版，也不是重跑PPP。共同支持行D全部不变；共同保留的L数值不变，L差异只来自明确RESET/SLIP样本不再冒充有效相位。新增GLO生成行另计，不把集合扩大算作精度改善。

在同信号、同连续弧、相邻约1 s、P/L均存在且无LLI失锁的共同样本上，256 ns事件的 `ΔP−λΔL`：

|事件|共同连续样本|修改前中位数m|修改后中位数m|
|---|---:|---:|---:|
|K70_early|23|-76.867887060|-0.121017809|
|K70_oct21|35|-76.797279570|-0.050410327|

样本23/35由本次Native共同集合及严格弧条件定义，不能直接与历史更宽集合25/38混作相同统计。没有要求P−L本身为0或恒定；电离层、码噪声、多路径及状态变化仍可造成差分。

## 7. 剩余异常和适用限制

**100 ms Tx参考冲突仍未被该固定公式解决。** K70_late第3期GPS/GAL发送时标改变而BDS并非同样改变，严格固定参考仍有11条 `CODE_TIME_OUT_OF_RANGE`，不输出负伪距但保留原Raw、相位/率及明确原因。未重设共享钟来迎合单星数据，也没有叠加上一轮按星座推断的Tx修复；不能声称本轮修掉了该100 ms事件的全部码丢失。

**原始ADR与PRR仍有不一致证据。** 连续同信号弧计算 `ΔADR−(PRR_prev+PRR_now)/2*Δt`，并证明与输出L/D回算结果≤1e−6 m一致：

|输入|信号|连续对数|差值中位数m|绝对值P95 m|
|---|---|---:|---:|---:|
|K70_early|GPS 1C|2616|-0.0095|0.2856|
|K70_early|Galileo 7Q|3918|-289.3915|304.3998|
|K70_late|GPS 1C|2620|-0.0043|0.2813|
|K70_late|Galileo 7Q|3501|-330.4267|332.6279|
|K70_oct21|GPS 1C|5442|-0.0097|0.2814|
|K70_oct21|Galileo 7Q|6513|-206.7506|228.1594|
|K70_oct25|GPS 1C|5238|-0.0233|0.2751|
|K70_oct25|Galileo 7Q|3834|204.6408|225.8044|

GAL7Q原字段已有百米量级差异；例如早段源行42→88，ADR增量766.409697 m，PRR梯形积分987.933913 m，差−221.524216 m。该差异经转换数值保持，不是本次换算额外生成。当前证据不能唯一分配到API参考/接收机实现等来源；本轮未修改D、改频率、扣趋势或把Doppler当无条件真值。积分近似、采样和钟参考也影响该诊断，不能用一个无来源阈值判定所有差异为粗差。

RTKLIB以精确L=0表示缺失，Native对此单独记 `phase_zero_sentinel/OBS_PHASE_REPRESENTATION`；RINEX有显式presence，合法零相位和零D可写。Native D=0同样受旧数据结构的缺失哨兵限制；没有为解决这一表示限制改写整个PPP结构。近零但非零的相位不再被人为1e−4 m阈值吞掉。

未宣称旧Raw转换成RINEX就会消除所有手机米级PPP偏差；尚无修改后室外采集验证，也没有用真值回调参数。单输入内部闭合成功不等于观测具有独立物理真值。

## 8. 日志、复现和回退

- `validation/observation_generation_2023_v1/*kotlin.csv.gz`：源行、原Time/F/B、参考F/B、钟段、接收时刻、信号、频率、P/L/D/LLI及逐行原因。
- 同目录 `*native.csv.gz`、`*before.csv.gz`、`*.rnx`（或压缩同名 `.gz`）、`validation_results.json`、`before_after_results.json`、`delivery_summary.json`、构建及测试日志。
- Native trace：`OBS_CLOCK_REFERENCE`记录锚点，`OBS_INVALID_CODE`（trace≥3）、`OBS_TIME_CONFLICT`和`OBS_PHASE_REPRESENTATION`明确失败/表示限制。Kotlin头部标记 `OBSGEN FIXED_CLOCK_2023`。
- 原始文件与产品没有改写。修改前文件/SHA见 `before.json` 和 `before/`；不要覆盖其他未提交工作；未提交/推送。旧prefix测试的输出已迁入本轮目录，旧全Raw基线另由备份实际代码保留，避免再覆盖历史审计输出。

复现入口（项目根目录；JAVA_HOME须指向Android Studio jbr）：

```
python tests/sync_observation_generation.py
cmd /c validation/observation_generation_2023_v1/build_pc.cmd
cmd /c validation/observation_generation_2023_v1/build_native_tests.cmd
validation/observation_generation_2023_v1/native_tests.exe
python tests/run_observation_generation_core_tests.py
```

Android执行 `gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain`。真实回放需设置 `OBSERVATION_GENERATION_OUTPUT=E:\GNSS\PPP_only\validation\observation_generation_2023_v1`；FullBias三份既有小fixture使用 `FULLBIAS_AUDIT_DIR=E:\GNSS\PPP_only\validation\fullbias_reference_v1`。随后执行 `build_probe.cmd`、`build_probe_before.cmd`，`python tests/validate_observation_generation.py`、`python tests/compare_observation_generation_before.py`、`python tests/report_observation_generation_2023.py`。解析器header是当前/备份 `gnss_replay.c` 中 `split_csv` 至 `normalize_clock` 前的原样提取，文件一并保存；修改解析器后须刷新提取，不能拿旧探针冒充新源码。

下一步范围：先用已生成APK进行允许的室外原始采集验证，比较相同会话的参考锚点与观测sidecar；100 ms发送时标冲突与GAL7Q原字段不一致作为明确剩余证据保留。本轮不据此修改PPP物理模型、R/Q或抗差参数。
