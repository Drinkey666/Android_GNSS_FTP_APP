# Android PPP 当前统一配置

2026-09-30：实时 JNI 和 RINEX 后处理 JNI 均使用共享 `smartphone_ppp_configure()`，配置版本 `pc-baseline-20260930-v1`。

- 静态浮点 PPP；GPS + Galileo + BeiDou，GLONASS 不默认启用。
- 编译 NFREQ=4 / opt.nf=4；ENAGLO 编译，ENAQZS 不编译，与 PC 卫星编号布局一致。
- DOPPWARM=0；GALE5BPHASE=0；BDSCODEVAR=1。
- 原 IONEX、VMF3、噪声和抗差参数直接沿用当前 PC。
- TXT ppp-safe 和 ADR 1.0 m 门限保持不变；本次不新增 TXT B1C。
- 两个库从 `app/src/main/cpp/ppp_live/rtklib` 编译，不使用旧 `app/src/main/cpp/rtklib`。
- PC 参数唯一编辑源为 PC 项目 `src/smartphone_ppp_config.c/.h`；Android 为经校验快照。
- 每次构建会校验 29 个快照文件。PC 再更新后须运行 PC 项目 `tools/sync_smartphone_ppp.ps1 -AndroidRoot <本项目> -UpdateAndroid`，不能只手动改一端；脚本会备份旧文件。
- Android 4 种 ABI 构建和 8 项 JVM 单元测试通过；尚未在手机上完成新的 PPP 数值回归。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。

完整 PC 对照与验证报告：`E:/GNSS/rtklib_app/rtklib_Project2/PPP_CONFIG_UNIFICATION.md`。
本次 Android 原核心备份：`tools/config_backup_20260930`。
旧的 ANDROID_PPP_LIVE_V1.md / ANDROID_PPP_POST_PC_SYNC.md 中的历史“三频、10秒预热”等参数不再是当前默认，以本报告和共享配置源码为准。
