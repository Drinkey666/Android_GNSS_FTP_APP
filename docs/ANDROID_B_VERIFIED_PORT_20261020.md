# Android 固定 B_VERIFIED 移植与验证

> 后续同日增量：K70现已成功安装最新DMS参考坐标版本，APK已更新。参见`MANUAL_WGS84_REFERENCE_DMS.md`及`tools/manual_reference_checks.json`；下文旧APK哈希与vivo安装失败记录是当时移植快照，不代表最新安装状态。B_VERIFIED源码与8个解算.so未变。

日期：2026-10-20。安卓工程 `E:/android/test/FTPGet`，PC 来源 `E:/GNSS/PPP_only`。

## 已完成范围

1. `app/src/main/cpp/ppp_live/rtklib/` 同步当前 PC 的32个源码/头文件，逐字节SHA256一致；`rtkpos.c`对应PC的`PPP_only/ppp_position.c`，不是已经移除的旧多模式rtkpos。PC正式52保护文件未改。
2. `android_b_verified.h`提供安卓唯一配置入口：`smartphone_ppp_configure` → `smartphone_ppp_select_profile("B_VERIFIED")` → `smartphone_ppp_validate`。实时JNI和RINEX后处理均调用它，不靠Android环境变量，不开放A/旧B/PHYSICAL切换。
3. 保留正式B的连续性、电离层生命周期、参考码模糊度初始化、公共码相参考变化、PPP_COMMON钟差及Joseph/PSD提交保护。GPS/GAL/BDS、nf=4、GLO关、E5b关；不移入BRIDGE3候选、eta、Q自适应或其他独立实验。不改R/Q/P0/GIM及门限。
4. `CMakeLists.txt`两个库共用同一组源码，补齐新息/诊断依赖；移除当前PC未编译的lambda构建条目。原旧源码目录不参与构建，也未删除。
5. 实时路径：`GnssMeasurementsEvent` → Kotlin后台串行回调 → JNI字段读取 →公共钟跳归一化 →原样adapter(ppp-safe, ADR uncertainty既有1m) →一次`rtkpos` →PPP核心。每个成功初始化的会话只创建一次滤波状态；停止释放，不生成/读取RINEX OBS。
6. 归档产品一次加载；逐历元检查SP3/CLK、BIA、IONEX与VMF3覆盖，IONEX用公共`ionex_in_range`统一GPST→UTC。过期会话明确停止，不默认外推。没有SSR/NTRIP改正流。
7. `LivePppController.kt`新增每会话`ppp_b_verified_<timestamp>.solution.csv/.obs.csv/.trace/.profile.txt`，路径为现有App外部私有`GNSS_Live`目录。CSV保留Q0，不把旧坐标展示成有效解；高程列仍为椭球高。

注意：描述中的`baseline=B_LEGACY`表示B算法族，不表示修复未启用。确认本次代码必须同时看到`repairs=B_VERIFIED_v2`与`covariance=JOSEPH_B_MASK`、`clock_seed=PPP_COMMON`。PC与Android完整配置签名均为`f498c398`。

## 构建与验证

|项目|实际结果|
|---|---|
|Debug APK构建|通过，arm64-v8a/armeabi-v7a/x86/x86_64，两个解算库共8个.so|
|JVM测试|75项：71通过、4项跳过、0失败/错误；跳过不计为通过|
|APK内容检查|8个.so均含Joseph B标记；源码32文件哈希一致|
|已连接设备|vivo V2507A，Android API36，ARM64|
|原生数值/配置/生命周期针对性检查|42项通过、0失败；含NaN/Inf拒绝且状态不变、正常Joseph、显著非PSD拒绝、舍入边界、重复历元不提交、释放及新会话首期重现|
|K70晚原始TXT整程|660历元，Q0=1、Q5=3、Q6=656，与PC逐历元相同|
|观测转换|手机原生输出obs CSV与PC原始TXT回放逐字节一致|
|时间、Q、有效/观测卫星数|全部660历元与PC一致|
|最大ECEF分量差|X=1.420034e-6m，Y=8.715317e-6m，Z=9.033829e-6m|
|最大输出椭球高差|1.2e-5m；这是两端数值差，不是高程真值误差|
|全程状态/协方差|660次检查，非有限状态0、尺度PSD失败0|
|临时手机数据|仅本轮独立`/data/local/tmp/ppp_b_verified_<time>`，回收输出后按确切文件名删除；目录已移除|

手机原生回放直接包含生产JNI源码，调用与JNI相同的`initialize_engine/process_raw_epoch`；TXT解析仅存在于独立测试可执行文件。它验证ARM64生产辅助函数和解算内核，不冒充真实Android回调字段读取、JVM/JNI运行或现场新采集验证。APK仪器测试`BVerifiedNativeProfileTest`已编译，尚未执行，原因见下。

`verified_device_replay`只有显式`PPP_BUILD_DEVICE_REPLAY=ON`时构建，正常APK不包含该测试入口；没有为测试新增正式运行模式。现有Windows452项回归本轮未重复运行，不能计入新增通过数；通过冻结哈希确认PC代码未动。

## APK与安装状态

- APK：`app/build/outputs/apk/debug/app-debug.apk`，56,405,352字节。
- SHA256：`a23b18140661fc805170167faa50ab4a590e69d173a5a861c52541ce1dc60b67`。
- 已尝试`adb install -r`保留数据更新。设备拒绝：`INSTALL_FAILED_ABORTED: User rejected permissions`。没有卸载应用或清数据，没有绕过拒绝重复安装；**设备上原App尚未更新**。
- `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`已生成，实际JNI仪器测试等待新版安装成功。

允许手机USB安装或手动安装上述APK后，使用现有“启动实时PPP”入口即可。必须先下载覆盖**当前GNSS时刻**的预报产品；本轮10/18归档产品仅用于回放，不能用于10/20现场解算。固定文件支持不等于产品自动无缝滚动，当前产品到期会停止会话。

## 证据与复现

- `tools/b_verified_package_checks.json`：APK/8库SHA、JVM计数、安装受阻状态。
- `tools/b_verified_build.log`及`b_verified_instrumentation_build.log`：成功构建输出。
- `tools/b_verified_device_latest.json`：本轮原生验证目录索引。
- `tools/b_verified_validation/20261020_161019/`：device_console、receipt、solution.csv及校验压缩的obs/stat/trace。
- `app/src/main/cpp/ppp_live/rtklib/pc_snapshot.json`：32文件精确来源映射和SHA。
- `tools/ppp_sync_backups/b_verified_20261020_155814/`：首次修改前涉及源码与桥接文件备份、原Git脏状态。同步期间发现PC已无lambda.c，初次复制提前结束；原备份保留，最终完整32文件同步及构建哈希已验证。`160100`备份是同步中间态，不替代首次备份。
- `tools/sync_b_verified.py`只读核验；`--sync`才做备份并显式同步。新PC改动需先冻结验证，不能盲目同步旧路径工程。

```powershell
# 构建；JAVA_HOME使用本机Android Studio的jbr
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline --console=plain
# 仅验证源码一致性（不重新解算）
python tools/sync_b_verified.py
# 可选重跑设备原生660历元：先按CMake选项构建设备测试目标，见b_verified_device_build.log
python tools/run_b_verified_device_replay.py
```

## 影响边界

没有更改PC内核、默认参数或已冻结数值修复，没有提交/推送Git。保留Android工程原有未提交的界面/下载修改；本轮只改native构建、内核快照、两个JNI入口及实时记录控制器，未编辑MainActivity/下载模块。迁移验证没有消除启动约16m误差；不能声称换成Android后收敛更快。

剩余必验：安装成功后的JNI仪器测试、同次真实GnssMeasurementsEvent+原始TXT双记录对照，以及当前预报产品的手机现场运行。当前结论是“代码移植、构建和手机原生归档回放通过”，不是“新现场数据已达到某个精度”。
