# 产品下载 read timed out 修复（2026-10-20）

## 结论与边界

本次修改 Android 工程 `E:\android\test\FTPGet` 的网络传输、失败分类和下载日志。
不修改 RTKLIB C/C++、PPP 参数、产品有效期或 ppp-safe；不恢复“文件存在就跳过下载”。
用户要求自行验证手机，未安装 APK、未做手机采集或手机网络测试。

`read timed out` 表示在规定等待时间内没有收到网络数据，并不等于文件不存在，也不等于 BIA 已过期。
没有原手机失败日志，不能声称已经确认手机上具体哪一个产品/网络导致超时。

## 检查发现

1. 原 FTP 连接/数据超时为 15 秒，未统一设置控制连接读取超时；HTTP 文件读取为 30 秒。
2. 缺少同一文件/目录的暂时性网络故障重试。部分路径会把传输异常混入日期/版本回退。
3. FTP 超时后控制会话可能失步，不能简单继续沿用同一连接。
4. HTTP 在打开输入流时超时，原先已经创建的 `.part` 输出流有未关闭风险。
5. 界面当前文件/进度每次覆盖上一条，最终汇总也会覆盖失败原因，用户无法回看超时文件。

## 实施的修改

### 统一网络策略

新增 `app/src/main/java/com/example/ftpget/DownloadNetworkPolicy.kt`：

| 项目 | 当前设置 |
| --- | --- |
| 连接建立 | 20 秒 |
| FTP 控制回复读取 | 45 秒 |
| FTP 数据 / HTTP 读取空闲 | 90 秒 |
| 暂时性故障 | 最多 3 次尝试（含第一次），等待 1 秒、3 秒 |

读取超时是连续收不到数据的时间，不是整个文件的总下载时限。
重试适用于 timeout、连接中断、EOF、FTP 连接被关闭，以及 HTTP 408/429/5xx。
取消操作不作为网络错误吞掉。认证失败、404、普通磁盘写入失败不按超时反复重试。
配置和 API 已按项目实际 Commons Net 3.9.0 核对，不额外升级依赖或关闭 TLS 证书校验。

### 下载器

修改 `FtpDownloader.kt`：

- FTP NRT 配套轨道钟差、BIA、NAV 等路径接入统一策略；超时后断开并新建 FTP 会话。
- HTTP NAV 目录和文件、IONEX、VMF3 对同一资源先重试，再明确报告失败类型。
- NAV HTTPS 与 FTP 后备分开，避免 FTP 重连再次套一轮 HTTPS 重试。
- 网络重试耗尽不等同于“这一天未发布”；404 仍保留原有适当候选回退。
- HTTP 输出流先进入 `use`，即使输入流打开就超时，也关闭输出句柄。
- 保留 `.part`、大小、gzip CRC、NAV RINEX3 和 VMF3 完整格网校验；校验通过后才替换目标。
- 下载失败清理本次 `.part`，保留已有完整目标文件。保留旧文件不代表它自动符合当前 PPP 时效。
- 日志包含产品、主机/目录、文件名、阶段、尝试次数和错误类别，不记录认证头或密码。

### 可回看的日志

修改 `MainActivity.kt`、`ui/MainUiState.kt`、`ui/LogScreen.kt`：

- 当前文件/百分比仍独立刷新，不用完整日志替代进度。
- “最近活动”展开后能查看和选择复制本次下载日志，不再被下一份产品覆盖。
- 界面保留最近约 48,000 字符；完整日志写入应用 `GNSS_Products/download_<毫秒时间戳>.log`。
- 每次点击下载开始新会话；文件日志写入失败不应中止下载。
- 产品公共导出仍按原白名单执行；本次未新增日志公共导出功能。

## 验证结果

### 电脑公网探测（不是手机网络）

2026-10-20 15:27 左右（北京时间），公开匿名通道探测：

- WHU BIA 下载成功：`WUM0MGXRTS_20262930000_01D_05M_OSB.BIA.gz`，246639 字节，约 1.55 秒。
- BKG 当天 NAV 目录和文件可访问；实际下载并验证 gzip 成功。
- CODE IONEX 站点的一次 Python HTTPS 请求出现 TLS 提前 EOF，约 15.88 秒失败。

上述 CODE 结果只是一次传输异常证据，不能据此断言手机超时一定发生于 IONEX。
未使用 VMF3 账号做公网测试；VMF3 的 HTTP 失败路径通过无凭据故障注入验证。

### 实际 Kotlin 下载器公网测试

启用 `NavHttpsPublicSmokeTest` 的显式网络测试，使用目标日期 2026-10-20：

- NAV：`BRDC00WRD_R_20262930000_01D_MN.rnx.gz` 下载并通过 RINEX3/gzip 校验。
- BIA：当天 `WUM0MGXRTS_20262930000_01D_05M_OSB.BIA.gz` 下载并通过压缩完整性校验。
- SP3/CLK：同一发布时次的以下两份文件下载并通过压缩完整性校验：
  - `WUM0MGXNRT_20262920600_02D_05M_ORB.SP3.gz`
  - `WUM0MGXNRT_20262920600_02D_30S_CLK.CLK.gz`

NRT 测试参考时刻是目标日期 UTC 12:00，配对检查的是文件名标称覆盖；不代替 RTKLIB 的内部产品历元检查。
BIA 测试验证下载，不代表所有信号码 OSB 都覆盖观测时间。未运行新的 PPP 精度实验。

### 故障注入与回归

新增 `DownloadNetworkPolicyTest` 7 项、`DownloadTimeoutRecoveryTest` 7 项，验证：

- 目录读取、传输中、打开 HTTP 输入流及 HTTP 流中途超时。
- FTP 新会话重连、同一 HTTP URL 重试、HTTP 503 重试。
- 401/404 不冒充 timeout；取消、普通 IO 错误不无限重试。
- 最多三次后真实失败；所有流/连接关闭，`.part` 清理，原完整文件不被覆盖。

全量测试共 75 项：74 通过、1 跳过、0 失败。
跳过的是未提供原始 TXT 环境变量的 RINEX 转换回归，不是下载用例。
三个显式公网下载用例均执行并通过，未按默认开关跳过。

`testDebugUnitTest`、`assembleDebug` 编译成功，包含四个 ABI 的既有 native 构建检查。
界面在手机上的展示/操作尚未验证，按用户要求由用户自行测试。

## 使用与复现

APK：`app/build/outputs/apk/debug/app-debug.apk`。
覆盖安装新版后点击下载；如仍有失败，展开“最近活动”复制完整日志，或读取上面 `.log` 文件。
不要为避免超时关闭产品有效期检查、绕过 BIA 校验、改 PPP 参数或关闭 HTTPS 证书验证。

离线单元测试与编译：

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

另需实际访问公网时，执行前显式启用（`--offline` 仅限制 Gradle 依赖下载，不限制测试自身网络）：

```powershell
$env:GNSS_PUBLIC_NAV_SMOKE='1'
$env:GNSS_PUBLIC_NRT_SMOKE='1'
$env:GNSS_PUBLIC_NAV_DAY='2026-10-20'
```

证据位于 PC 工作区 `work/download_timeout_20261020/`；包含 `public_probe.json` 和测试 XML。
之前整理的 `PROJECT_EVIDENCE_20261020.zip` 是本次修复前快照，未重写；其旧 APK/源码指纹不能套用本次版本。
