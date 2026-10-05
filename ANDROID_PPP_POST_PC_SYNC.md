# Android RINEX 后处理与 PC RTKLIB 同步

## 现状

“RINEX 后处理 PPP（PC 内核）”现编译 `ppp_live/rtklib` 中的 PC 源码快照，包括 `ppp.c`、`rtkpos.c`、`rinex.c`、`postpos.c`、`rtcm*.c` 等。实时 PPP 仍使用独立的 `gnss_ppp_live` 动态库，两条入口互不复用滤波状态。原先 Android 目录 `cpp/rtklib` 里的旧内核不再参与 `rtklib_engine` 构建；这些旧文件未被覆盖。

PC `rnx2rtkp.c` 的 `main()` 固定了 Windows 文件路径并在结束时等待键盘输入，不能原样作为 Android 入口。`rtk_jni.c` 仅替代该入口：从 App 传入 OBS、NAV、SP3、CLK、BIA、IONEX、两期 VMF3、orography、ATX 和输出路径，设置与 PC `rnx2rtkp.c` 相同的 `prcopt_t`/`solopt_t` 参数，再调用**原样的 PC `postpos.c`**。旧的 Android 配置文件（双频无电离层组合、不同权重、无 BIA/IONEX 接入等）已不再生成或使用。

## 文件选择

App 只把 `_MO.rnx` 或传统 `.26o` 等识别为 RINEX OBS，避免把 NAV `.rnx` 误当观测。按 OBS 头中的 `TIME OF FIRST OBS` 和实际历元确定观测区间，再选择文件名声明覆盖该区间的 NAV、SP3、CLK、BIA、IONEX，以及前后包围观测时间的 VMF3 格网。`.gz` 产品按需解压；内置 ATX/orography 原样复用。所选产品名会显示在解算前的界面。

当前后处理仍只加载一对 VMF3，因此若观测跨越两期格网的 6 小时边界，会明确拒绝；需要分段处理或日后实现动态换片，不会静默地让后半段退回内部对流层模型。RINEX GPS 时间转 UTC 用 2026 年适用的 18 秒差；若将来闰秒变化，需更新该时间换算。产品文件名覆盖区间只是预检查，最终还须从 trace/结果确认产品在每历元实际生效。

## 验证范围

- Android Debug APK 编译与 JVM 单元测试通过，包含观测/产品时段匹配测试。
- PC 源码快照与 PC 项目文件哈希一致；Android 桥接入口与 PC 参数逐项核对。
- 尚未在授权设备上用同一组 RINEX 和产品跑 Android/PC 的逐历元结果对照。不能因此宣称两端定位坐标或 Q6 已实测一致。

若 PC 项目之后再次修改 `ppp.c` 等文件，Android 的快照不会自动更新，必须重新同步、编译并复测。
