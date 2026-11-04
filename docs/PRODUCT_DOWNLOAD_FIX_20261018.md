# 广播星历与预报产品下载修复说明

日期：2026-10-18。工程：`E:\android\test\FTPGet`。

## 发现的问题

1. 原代码只访问 UTC 当天 NAV 目录，没有处理当天目录不存在、目录为空或文件未发布。
2. NAV 放在 `preciseProductsReady` 条件内，WHU 精密轨道／钟差失败时，连广播星历都不请求。
3. 实际联网检查中，`igs-ftp.bkg.bund.de:21` 连接被拒绝；
   同时 BKG 的 HTTPS 目录正常，且有当天的 RINEX3 NAV。
   因此不能把所有下载失败都解释为“当天文件尚未生成”。
4. 原 NRT 逻辑锁定“昨天起算日”，并分别选择文件名最大的 SP3 和 CLK，可能忽略更晚的
   完整组合，或者选出不同时次。按天回退还会把大气产品日期一起退到过去。
5. 实时入口原来按本地修改时间选 NAV，刚重新下载的旧日期文件可能遮住新日期文件；
   旧版本残留的损坏文件也可能遮住本次成功回退的 NAV。
6. 原下载完成日志会覆盖产品缺失汇总，容易让用户误认为所有产品都就绪。

## 修复后的行为

### 广播星历 NAV

- 先通过 HTTPS 读取 BKG 目录；HTTPS 均失败时，才尝试原 FTP 后备通道。
- 以一次点击下载时固定的 UTC 日期为基准，依次检查当天、前一天、前两天、前三天。
- 按产品文件名的历元由新到旧选取，不用服务器或本地文件修改时间代替产品日期。
- 支持 IGS、WRD、DLR 的 RINEX3 混合 NAV 命名，包括 `.rnx` 和 `.rnx.gz`。
- 不把 BRD4/RINEX4 文件交给当前 RINEX3 引擎。
- 下载至 `.part`；大小、gzip CRC、NAV 类型、RINEX 版本和首条完整星历记录检查通过后，
  才替换正式文件。损坏文件不会覆盖现有目标。
- 如果当天没有合适文件，自动尝试历史日期，并明确提示“使用回退广播星历”和实际文件名。
- NAV 获取与 WHU SP3/CLK 成败解耦。

“最多回退三天”只是搜索范围，**绝不意味着广播星历有效期是三天**。
下载成功也不能证明每颗卫星的 TOE、健康状态及可用时段适合当前观测。
这些仍由现有 RTKLIB 检查；不要为了让旧 NAV 可用而放宽星历有效期。

北京时间上午 08:00 前通常仍是 UTC 的前一天。程序使用 UTC，而不是把北京时间日期
直接拼成 IGS 目录。跨公历年、闰年和 GPS 周边界均按日历计算。

### NRT 轨道／钟差

- 扫描两天产品可能所在的当前／相邻 GPS 周目录。
- 只考虑文件名标称时段覆盖下载参考时刻的候选，不把过期历史产品当实时产品。
- 选择同一起算历元、同一持续时间的 SP3/CLK 完整组合。
- 同一起算时次存在多个 CLK 采样间隔时，优先更高采样率，例如 30S 优先于 05M。
- 某一时次传输失败时尝试下一组仍覆盖当前参考时刻的候选。
- 只回退产品起算时次，不把今天的 IONEX／VMF3 目标日期跟着退到昨天或前天。
- 实时入口也使用相同的 NRT 配对规则，避免本地修改时间再把它们拆开选择。
- 文件名时段只是初筛，实际 SP3/CLK 记录的覆盖仍由 JNI／RTKLIB 检查。

没有恢复“本地存在则不下载”的功能。每次点击仍从服务器检索、下载动态产品；
本地读取时的完整性检查、gzip 解压复用不是下载缓存判断。
ATX 和格网高程继续沿用既有本地静态文件策略。

### 本地读取与界面

- 实时 NAV 按日期选择完整、有效的近期文件，跳过损坏残留文件。
- 先验证压缩文件 CRC，再解压，避免坏 gzip 覆盖完好的明文副本。
- 后处理不再仅因前一天 NAV 文件名的 24 小时已结束就直接拒绝；
  真正的单星星历有效性仍交给 RTKLIB，而不是扩展其有效期。
- 公共目录导出白名单补充 BRDM 合并 NAV。
- 最终界面保留缺失产品汇总、广播星历回退提示，不再被最后一条日志覆盖。

## 实际联网验证

2026-10-18 的 PC/JVM 公网冒烟测试调用了修改后的实际 Kotlin 下载方法，而不只是 Python 探测。

### BKG NAV

- HTTPS 目录：`https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2026/291/`
- 成功下载：`BRDC00WRD_R_20262910000_01D_MN.rnx.gz`
- 本次验证文件大小：171140 bytes。
- gzip CRC、RINEX3 NAV 内容验证通过。
- 同目录还存在 WRD_S 文件，头部说明它来自数据流并每 15 分钟更新。
  不需要等当天完整的日文件归档才尝试当前可用产品。

### WHU NRT

- 当前 GPS 周目录 2441 在检查时为空。
- 自动扫描上一周 2440 后成功下载配套文件：
  - `WUM0MGXNRT_20262892300_02D_05M_ORB.SP3.gz`
  - `WUM0MGXNRT_20262892300_02D_30S_CLK.CLK.gz`
- 两文件起算于 2026 年第 289 天 23:00，标称 48 小时覆盖第 291 天的测试参考时刻。
- 两份 gzip 完整性验证通过，配对检查通过。

这些验证没有运行 PPP 精度试验，也不等于已在用户手机网络环境完成安装／下载实测。

## 修改文件

实现：

- `app/src/main/java/com/example/ftpget/BroadcastNavPolicy.kt`（新增）：UTC 回退、NAV 命名与内容检查。
- `app/src/main/java/com/example/ftpget/NrtProductPolicy.kt`（新增）：NRT 覆盖、GPS 周目录及成对选择。
- `FtpDownloader.kt`：HTTPS 优先 NAV、FTP 后备、NRT 配套下载、发布前校验。
- `MainActivity.kt`：独立下载 NAV、固定 UTC 参考、大气日期不倒退、保留最终状态汇总。
- `LivePppProducts.kt`：NAV 日期／完整性选择和 NRT 配对。
- `PostPppProducts.kt`：允许近期 NAV 候选，保留 RTKLIB 星历有效期检查。
- `PublicGnssExporter.kt`：允许导出 BRDM 格式合并广播星历。

测试：新增 BroadcastNavPolicyTest、NrtProductPolicyTest、NavDownloadFallbackTest、
NavHttpsDownloadTest、NavHttpsPublicSmokeTest、LiveNavSelectionTest；
PostPppProductsTest 补充回退 NAV 测试。

本次没有修改 C/C++ PPP 核心、处理参数、Android 测量采集或 RINEX 转换。
工程原有未提交的界面等修改保留，没有 Git 回退、提交或上传。

## 编译和复测

本次最终验证：`BUILD SUCCESSFUL`；共 60 项测试，59 项通过、0 失败、1 项跳过。
跳过项是未配置真实采集 TXT 的既有 RINEX 转换回归；两项公网冒烟测试均实际执行并通过。
`git diff -- app/src/main/cpp` 为空，未修改原生解算代码。

使用 Android Studio 内置 JBR 运行：

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

手工启用公网验证（使用 `--rerun-tasks` 避免仅改变环境变量时 Gradle 复用测试结果）：

```powershell
$env:GNSS_PUBLIC_NAV_SMOKE='1'
$env:GNSS_PUBLIC_NRT_SMOKE='1'
$env:GNSS_PUBLIC_NAV_DAY='2026-10-18'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --rerun-tasks
```

公网测试默认不启用，以免离线构建依赖服务器状态。
既有真实采集数据转换回归需要额外设置 GNSS_RAW_TEST_FILE / GNSS_RINEX_TEST_OUTPUT；
未设置时会跳过，不应称其已通过。

APK：`app/build/outputs/apk/debug/app-debug.apk`。
安装更新后点击“获取产品”。不要求清空原产品目录，也不要求卸载应用。
遇到回退提示时，留意实际 NAV 日期；若实时 PPP 仍提示星历缺失／过期，
应更新有效 NAV，而不是把旧文件重命名成今天。

参考：

- [BKG 当天 NAV 目录](https://igs.bkg.bund.de/root_ftp/IGS/BRDC/2026/291/)
- [IGS 广播星历与小时更新说明](https://connect.igs.org/data/)
