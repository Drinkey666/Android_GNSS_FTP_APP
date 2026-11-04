# 最新 RTS BIA 下载（2026-10-25）

本次按用户最新要求，将 Android 下载按钮的 BIA 来源改为 RTS；覆盖 10/21 的 NRT-only 下载要求。SP3/CLK 继续下载原有 NRT 配套产品，不修改 PPP 数学模型、原生库、R/Q、抗差及产品有效期检查。

## 下载及文件选择

- 下载目录：`ftp://igs.gnsswhu.cn/pub/whu/phasebias/<年>/bias/`，年份按下载会话固定 UTC 时间加当前 18 秒 GPST 偏移确定。
- 严格匹配 `WUM0MGXRTS_YYYYDDDHHMM_01D_05M_OSB.BIA[.gz]`。按合法产品起算日期排序，不使用 FTP 文件修改时间；同一时次优先 `.gz`。排除 NRT/FIN、未来时次、非法日期、路径、目录项和 `.part`。
- 当前年目录无候选或不存在时检查上一年，便于跨年；没有 RTS 时明确失败，不下载 NRT 作为回退。该目录回退不延长记录有效期。
- BIA 独立于 SP3/CLK 下载；轨钟暂不可取得时，仍尝试获取 RTS BIA。
- RTS 日文件会增量更新，因此同名本地文件存在时也重新获取。沿用三次超时重连、新建 FTP 会话、大小和 gzip CRC 检查、`.part` 验证后替换；失败保留旧文件并清理本次 `.part`。
- 实时入口选择最新合法 RTS 时次，避免旧 NRT 被重新复制后因修改时间更新而遮蔽 RTS。解压仍沿用已有规则，更新后的 gzip 会重新解压。
- 既有 NRT 文件不删除。历史 RINEX 后处理产品选择规则未改；旧 NRT 下载 helper 与其回归测试保留，正式下载按钮不再调用。

下载完成只表示传输/压缩完整性通过，不能代表全部卫星信号的码/相位 OSB 均覆盖当前观测。内部覆盖仍由现有核心检查，不外推有效期。本轮不认证 RTS BIA 与 NRT SP3/CLK 的完整钟差、天线参考兼容性，不通过替换文件自动改变这些约定。

## 修改文件

- `app/src/main/java/com/example/ftpget/RtsBiasPolicy.kt`：RTS 名称、产品日期及年度目录规则。
- `app/src/main/java/com/example/ftpget/FtpDownloader.kt`：新增 `downloadWhuRtsBias()`，复用已验证传输和重连机制。
- `app/src/main/java/com/example/ftpget/MainActivity.kt`：正式下载按钮改为调用 RTS，更新阶段与缺失提示。
- `app/src/main/java/com/example/ftpget/LivePppProducts.kt`：新增 `resolveBiasFile()`，实时选择 RTS 产品。
- `app/src/test/java/com/example/ftpget/RtsBiasDownloadTest.kt`：13 项离线测试及 1 项显式启用公网测试。
- `AGENTS.md`：最新接续要求；保留此前历史说明。

## 验证

构建及离线测试：

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

- 构建成功。126 项 JVM 测试：121 通过、5 跳过、0 失败/错误；跳过的网络/外部条件测试未冒称通过。
- RTS 离线 13 项全部通过：混合目录只选 RTS、无 RTS 明确失败、同名日文件刷新、失败/坏 gzip 保留旧文件、明文/压缩和目录项、非法/未来时次、GPST 跨年回退、LIST/RETR 超时重连、重试耗尽、实时选择不受 NRT 修改时间影响、缺 RTS 明确失败、更新 gzip 重新解压。
- APK 中四 ABI 共 12 份原生 `.so`（含运行库）与本轮修改前 SHA256 完全一致；91 个现有原生源码/构建文件 SHA256 一致。没有重复 PPP 数据回放，不能称现场实时能力已验收。

真实公网测试：

```powershell
$env:GNSS_PUBLIC_RTS_BIA_SMOKE='1'
.\gradlew.bat :app:testDebugUnitTest --tests 'com.example.ftpget.RtsBiasDownloadTest.publicLatestRtsDownload' --offline --console=plain
```

这里 `--offline` 只限制 Gradle 依赖获取；显式启用的测试本身仍会连接公网 FTP。

- 1 项真实公网测试通过，实际使用本次新增的 Kotlin 下载器，非 Python 替代验证。
- 从 `/pub/whu/phasebias/2026/bias` 下载 `WUM0MGXRTS_20262980000_01D_05M_OSB.BIA.gz`，92731 字节；大小及 gzip CRC 通过。
- 公网下载使用 JUnit 临时目录，未给观测目录增加产品副本。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。本轮未安装到手机；手机运行旧 APK 时不会自动获得本次代码。

证据：`tools/rts_bia_build_20261025.log`、`tools/rts_bia_public_smoke_20261025.log`、`tools/rts_bia_before_20261025.json`、`tools/rts_bia_checks_20261025.json`。完整测试计数已在运行单独公网测试前保存，之后 Gradle 的当前 XML 报告只对应单独公网测试。

保留现有未提交工作，未提交或推送。需要回退下载策略时，仅恢复本次 RTS 入口/选择修改；不得用 git reset/clean 覆盖既有 PPP 和界面工作。
