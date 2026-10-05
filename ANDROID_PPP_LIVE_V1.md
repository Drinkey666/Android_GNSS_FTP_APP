# Android GnssMeasurement → JNI → RTKLIB PPP 原型

## 当前状态

- 已新增 `gnss_jni.cpp`、`LivePppNative.kt`、`LivePppController.kt`、`LivePppProducts.kt`，并接到 POS 页“启动实时 PPP”按钮；另有“RINEX 后处理 PPP（PC 内核）”入口。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 已通过，产物为 `app/build/outputs/apk/debug/app-debug.apk`；新实时库构建为 arm64-v8a、armeabi-v7a、x86、x86_64。
- 实机运行与“PC replay vs Android live”的数值对照**尚未完成**：当前 ADB 设备状态是 `unauthorized`，也没有同一次采集的 Android live 观测导出。不能把编译成功写成实机 PPP 已成功或字段一致。
- `rtklib_engine`/`runPpp` 事后解算入口现已切换到相同 PC 内核快照加 PC `postpos.c`；详见 `ANDROID_PPP_POST_PC_SYNC.md`。新 `gnss_ppp_live` 仍是独立 `.so`，不调用后处理引擎。

## 核心边界

Android 工程内旧 `rtklib/ppp.c` 与 PC 验证版并不相同，因此新实时库使用 `app/src/main/cpp/ppp_live/rtklib/` 下的**未改写源文件快照**，没有修改原有或 PC 的 `ppp.c`、`rtklib.h`、`gnss_adapter.c`、`gnss_signal_policy.c`。四个文件与 PC 原件 SHA-256 相同：

| 文件 | SHA-256 |
| --- | --- |
| `ppp.c` | `A13D6181C8E9BC27FE56034D3585FECBA34AF351D961691DBA77223C2D7B59FB` |
| `rtklib.h` | `DCF7419B45A78FDCD2E7093E72979C9AA30C574E22AF1191B0B31D42F5BDE279` |
| `gnss_adapter.c` | `781D35D4BDB770A4FC17DA65237BF6FB058C02B1EE3BCC184EC3F94F151D1B55` |
| `gnss_signal_policy.c` | `019F90F46C658D28FE872EEC8B3FB9A2501B95BB2A6D8C5AB932C7AB4328A8AE` |

JNI 只有一个长期存活的 `rtk_t/nav_t` 实例。`nativeInit(paths)` 加载手机本地 NAV、SP3、CLK、BIA、IONEX、两期 VMF3、格网高程、ATX 并执行一次 `rtkinit()`；`nativeProcessEpoch(clock, measurements[])` 直接读取 `GnssClock/GnssMeasurement`，调用**原样** adapter 的 `ppp-safe`，一个可用事件至多调用一次 `rtkpos()`；`nativeRelease()` 执行 `rtkfree()` 和产品内存释放。没有生成 RINEX OBS，没有用 `rinex.c` 解析实时观测。`rinex.c` 仅作为 RINEX **NAV 产品**的现有读取器链接。新实时库不包含 `stream.c`、`rtcm.c` 或 SSR/NTRIP。

JNI 解算参数逐项复制 PC replay 的 `configure_ppp()`，没有另设手机专用权重。ADR uncertainty 默认 1.0 m；ADR reset/slip 仍由 adapter 保留相位并置 LLI。实时回调用后台 `HandlerThread`；Android 12+ 请求 full-tracking。页面仅显示 GPST、Q、卫星数、经纬高和耗时，ECEF/XYZ std 随 JNI 返回供后续诊断，不挤入最小测试界面。

## 在手机上运行

1. 安装 Debug APK，授予精确定位权限。实时 PPP 输入需要 Android 10+ 提供的 `GnssMeasurement.CodeType`。
2. 用 App 的现有下载功能把**覆盖当前 GNSS 时间**的 NAV、SP3、CLK、BIA、IONEX、相邻两期 VMF3 放入 App 的 `GNSS_Products` 目录。`igs20.atx` 和 `orography_ell_5x5` 已作为静态资源随 APK 提供，首次下载或实时 PPP 启动时复制进该目录，之后直接使用，不访问网络。`.gz` 格式 NAV/SP3/CLK/BIA/IONEX 在首次启动时解压一次；缺文件或 SP3/CLK 不覆盖当前历元会报错，不会用 RINEX OBS 后备。
3. 在“信号与定位”页点击“启动实时 PPP”。首次事件加载产品，随后每个 `GnssMeasurementsEvent` 更新解状态；点击“停止实时 PPP”释放滤波器和产品。两期 VMF3 仅覆盖其间时段，第一版未做不中断滤波的产品热更新。
4. `PPP_LIVE` Logcat 标签逐历元记录 GPST、raw 数、`obsd_t` 卫星数、phase/code 数、Q 和处理耗时；`PPP_OBS` 标签记录每个 sat/signal 的 P/L/D/CN0/LLI/code。App 同时在 `GNSS_Live/ppp_live_obs_<time>.csv` 保存与 PC `--obs-dump` 同列同精度的观测。

## 同一次采集的字段对照（待实机数据）

在手机上**同时**开启现有原始 TXT 采集和实时 PPP。采集一段后导出 `RawData_*.txt` 与 `GNSS_Live/ppp_live_obs_*.csv`，用 PC 已验证的 `gnss_replay.exe` 在 `ppp-safe` 下处理这份 TXT：

```powershell
& 'E:\GNSS\rtklib_app\rtklib_Project2\gnss_replay\x64\Release\gnss_replay.exe' --source txt --txt '同次采集的RawData.txt' --mode fast --obs-dump 'pc_obs.csv' --nav '对应NAV' --sp3 '对应SP3' --clk '对应CLK' --bia '对应BIA' --ionex '对应IONEX' --vmf0 '前期VMF3' --vmf6 '后期VMF3' --orog 'orography_ell_5x5' --atx 'igs20.atx'
python 'E:\android\test\FTPGet\tools\compare_live_obs.py' 'pc_obs.csv' 'ppp_live_obs_*.csv'
```

第二个命令需要将通配符替换为实际文件名。脚本按 week/TOW（毫秒）、sat、signal code、slot 匹配，输出 P/L/D/CN0 的均值、RMS、最大差及 SNR/LLI/code 不一致数。脚本已用同一 PC 文件自检，**但这不构成 Android/PC 实测一致性证明**。若原始 TXT 录制与实时 PPP 回调的采样时刻不完全相同，先检查共同历元数与两边独有的信号数。

第一版目标是稳定的实时输入与状态连续性，不在此阶段调整 PPP 精度。要把它用于长时间连续定位，还需产品滚动更新且不重置 `rtk_t`、后台前台服务和实际手机功耗/耗时测量。

## 重复点击“获取产品”时

SP3、CLK、BIA、NAV 仍需查询远端目录以确认哪一个文件是当前可用的最新版本；若该文件与本地同名且字节数一致，就跳过实际下载。IONEX 优先选 P0D，只有不可用才回退 P1D–P4D；本地 `.gz` 必须能完整读到 CRC 才跳过。VMF3 按目标日期/时次检查格网历元和 2592 个 5°×5°格点，完整文件跳过；四期都齐全时也不需要网络账号。ATX 与格网高程不再有网络下载路径，已有完整本地文件不会被覆盖。下载界面会显示跳过/复用信息。
