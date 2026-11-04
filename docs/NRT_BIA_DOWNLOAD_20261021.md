# NRT BIA 下载策略（2026-10-21）

用户已暂停实时 PPP 能力检查，本次仅修改 Android 产品下载。

## 行为

- SP3/CLK 下载成功后，返回它们实际所在的 `/pub/gnss/products/mgex/<GPS周>` 目录。
- BIA 只读取上述同一个目录，不再访问 `/pub/whu/phasebias/<年>/bias/`。
- 严格匹配 `WUM0MGXNRT_*_OSB.BIA` 或 `.BIA.gz`，按文件名产品时次选最新项；同一时次优先压缩副本。
- 排除 RTS、FIN、目录项、不合法日期、零持续时间和 `.part`。跨周时跟随实际下载成功的 SP3/CLK 目录。
- 没有 NRT BIA、目录不可用或传输失败时明确报告失败，不回退 RTS。保留网络重连、大小/CRC 校验和临时文件发布规则。
- 本地保存位置不变。已有 RTS 文件没有删除；本次不修改解算器的产品选择入口及正式算法。

“同目录”不等于必须同一发布小时：NRT OSB 与轨道/钟差可能发布节奏不同。
选出最新文件也不等于内部 OSB 记录覆盖当前观测。本次不扩展有效期、不外推；仍由解算器检查内部有效性。

## 文件

- `MainActivity.kt`：从轨道/钟差成功回调取得远程目录，调用 NRT 专用 BIA 下载器；界面明确显示 NRT。
- `FtpDownloader.kt`：增加配套目录回调；以 `downloadWhuNrtBias()` 替代原泛用 BIA 下载入口。
- `NrtBiasPolicy.kt`：NRT BIA 文件名校验与排序。
- `NrtBiasDownloadTest.kt`：6 项新增测试。
- `DownloadTimeoutRecoveryTest.kt`：原 BIA 超时/重连测试改用实际 NRT 下载路径。
- `NavHttpsPublicSmokeTest.kt`：公网测试改为 SP3/CLK 成功后在同目录下载 NRT BIA，删除旧 RTS 下载测试。

## 本地验证

命令：`gradlew.bat :app:assembleDebug :app:testDebugUnitTest --offline --console=plain`

- 构建通过。
- JVM 111 项：108 通过、3 跳过、0 失败。跳过的是未显式启用的外部数据/公网测试。
- 6 项新增测试覆盖 NRT/RTS 混合目录、仅 RTS 时拒绝、失败保留旧文件且不换 RTS、明文/压缩与目录项、非法名称、周日回退后保持同一 GPS 周目录。
- 原 BIA 传输超时、目录读取超时、三次重试耗尽测试仍通过。
- APK 中四 ABI 的 8 份原生解算库与已验证 B_VERIFIED 版本 SHA256 完全一致。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。本次未安装到手机。
- 证据：`tools/nrt_bia_build.log`、`tools/nrt_bia_checks.json`。

没有修改 R、Q、抗差方法、C/C++ PPP 核心、GNSS 采集或参考坐标，也没有提交/推送。
