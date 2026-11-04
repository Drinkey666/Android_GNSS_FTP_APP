# RTKLIB／Android GNSS 项目交接：新 Codex 账号先读

更新日期：2026-10-18（Asia/Shanghai）。这是本次长对话的本地交接资料。
文中明确区分“当前源码核对”“历史实验结论”“仅讨论未实现”。不能假定旧账号的对话、工具状态、授权或登录凭据能跨账号继承。

## 0. 接手规则：先读这里

1. 先读本文件，再读 `PRODUCT_DOWNLOAD_FIX_20261018.md` 和 RTS/NRT 诊断报告；不要重做已完成的 ADR／ppp-safe／产品替换实验，除非本地源码或输入已经变化。
2. 用户希望稳定、可迁移的手机实时浮点 PPP，不做为了某份数据贴近真值的硬调参；Q6 数量不是精度证明。
3. 当前用户只要求整理交接；**尚未要求在本轮开始实现实时流**。下一步先说明方案／范围，收到实施请求后再改。
4. Android 工程有大量未提交／未跟踪的正常修改，包含新界面和下载修复。不得 `reset --hard`、`checkout --`、`git clean`、整仓覆盖、盲目同步旧源码。
5. 不要修改复制工程 `E:\GNSS\rtklib_only\rtklib_only`；也不要把另一工程 `E:\GNSS\PPP_only` 当成原始工程。
6. 任何 PPP 模型、随机模型、信号策略、IONEX／VMF3／RCB 改动都必须单独授权、隔离实验、保存旧结果。不要顺手改这些来“修复下载”。
7. 不上传／不在交接里记录账号密码。Git 提交或上传也需要用户请求；本次没有提交、推送。
8. 所有历史数值均绑定具体数据、代码和产品。不得把 639 历元的早期实验、1200 历元的 3/17、600 历元的 3/21、四频短时 smoke test 混为同一基线。

## 1. 用户目标、已知边界

- 先在 PC 验证，再把改进过的 RTKLIB PPP 移植安卓，通过 GnssMeasurementsEvent 实时处理 Android 原始 GNSS。
- 不以模糊度固定／PPP-AR 为当前目标；研究方向是智能手机实时 PPP 的抗差／自适应卡尔曼滤波。
- 用户关心短时收敛、高程漂移、跨日期／跨手机泛化，不认为单纯延长到 1 小时就解决了需求。
- 观测可以直接 TXT → Android 中间结构 → obsd_t[] → rtkpos()，正式实时入口不能依赖 RINEX OBS。
- 用 RINEX 只作为独立输入基准。不能因为参考路径没有某个信号，就直接宣布那个信号无效。
- 预处理、信号定权、持续异常检测、IONEX 软约束、VMF3、OSB／接收机码偏差均有历史修改；先看现有代码和报告，不能从标准 RTKLIB 假设重新设计。
- 现有预处理已有粗差／码跳变、LLI/GF/MW/多普勒辅助周跳、因果伪距平滑与抗差／验后拒绝等逻辑。不要在入口重复处理一遍；Q5也不能用隐藏结果或强改标志伪装为Q6。
- 真值仅用于外部评估，禁止写入定位状态或硬拉高度。

## 2. 路径、Git 和版本边界（2026-10-18 实际核对）

| 用途 | 路径／版本 |
|---|---|
| 原始 PC 工程 | `E:\GNSS\rtklib_app\rtklib_Project2`，HEAD `12b044a` |
| 用户复制工程，不动 | `E:\GNSS\rtklib_only\rtklib_only`，HEAD `12b044a`，不能据此认为全目录与原工程相同 |
| 另一个独立项目，不混用 | `E:\GNSS\PPP_only`，HEAD `0fd98a9` |
| 单独导出的历史三频版本 | `E:\GNSS\rtklib_20260928_43c43b2`，源自 Git `43c43b2`，不是当前四频工程 |
| 安卓工程，本次主要工作区 | `E:\android\test\FTPGet`，HEAD `ca2e12c`，工作区脏，下载修复尚未提交 |
| PC 原始数据／产品 | `E:\RTKLIB_Data`，含 OBS、NAV、SP3、CLK、BIA、IONEX、tro、Tables |
| PC 结果，名称经常被覆盖 | `E:\RTKLIB_Data\PPP_Result`；通用 trace 为 `E:\RTKLIB_Data\ppp_debug.trace` |
| 历史工作区 | `C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp` |
| 结果可视化脚本 | `E:\python\质量分析.py` |

远端（仅记录地址，不能擅自 push）：

- 原始／复制 RTKLIB：`https://github.com/Drinkey666/RTKlib_mix.git`
- 安卓：`https://github.com/Drinkey666/Android_GNSS_FTP_APP.git`
- PPP_only：`https://github.com/Drinkey666/RTKlib_PPPonly.git`

重要提交：

- `43c43b2`，2026-09-28：Update PPP processing and GNSS raw replay tools；三频、尚未加入 B1I+B1C 四槽共存。
- `3d8897f`，2026-09-30：统一配置与四槽北斗支持。
- `12b044a`，2026-10-02：当前 PC 主线提交，含 PC UI／后续诊断。
- 安卓 `ca2e12c`，2026-10-05：同步 PPP、中文说明、当时做过凭据外置；**目前工作区并不等于该提交**。

PC 原工程曾在用户明确请求下于 10/9 恢复到最后上传版本 `12b044a`，保留实时新增文件与本地实验。
备份在 `work\rollback_12b044a_20261009`；旧 index.lock 当时已作为恢复文件搬走，不是可反复删除的固定操作。
10/18 核对 PC `src` 和已跟踪 `gnss_replay` 无未提交差异；生成物／未跟踪实验另论。

历史完整版本导出已完成，**不要再回退当前工程来找它**：

- VS：`E:\GNSS\rtklib_20260928_43c43b2\rtklib_Project2.sln`
- 完整 ZIP：`C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp\output\RTKLIB_20260928_43c43b2.zip`（318033551 bytes，含历史已跟踪生成物）。
- 源码 ZIP：同目录 `RTKLIB_20260928_43c43b2_Source.zip`（885400 bytes）。
- 当时已验证全部 169 个已跟踪文件与提交吻合；Git autocrlf 会造成原始字节差，不能误判换行差为代码被改。

## 3. 两组坐标，必须按采集数据区分

| 数据 | 用户最后给定参考坐标（纬度°、经度°、椭球高 m） |
|---|---|
| 3/17 K80，年积日 076，20 分钟 | `30.759928, 103.984162, 482.2530` |
| 3/21，年积日 080 | `30.759991, 103.984034, 482.2020` |

早期用户曾说第一组是附近参考点有偏移，后来明确其为真值。记录用户最新指定，但不要声称已独立验证天线相位中心／测量基准。
Q6 表示 PPP 载波解算状态，不证明厘米精度；形式标准差也不是真实误差。
经纬高／ENU 比较要核对椭球高与正高、参考坐标、时段、是否只统计 Q6、是否包括启动段。

## 4. 核心链路与当前源码状态

PC 入口：`src/rnx2rtkp.c`，共用参数 `smartphone_ppp_config.c/.h`，RINEX → postpos／rtkpos。
TXT 入口：`gnss_raw.h`、`gnss_adapter.c/.h`、`gnss_signal_policy.c/.h`、`gnss_replay.c`。
adapter 只转换 clock/measurement，不直接读 TXT；同星多频合并为一个 obsd_t。
每历元只调用一次 rtkpos；每个会话只 rtkinit 一次，状态跨历元保存，结束再 rtkfree。
`obs.P` 米、`obs.L` 周、`obs.D` Hz，`-lambda*D` 对应 Android pseudorange rate；时间为 GPST。

安卓：

- Kotlin 包路径：`app/src/main/java/com/example/ftpget`。
- `MainActivity.kt`：采集／产品下载／调用组织，已有用户新增的 Compose UI，不要重写回老 XML。
- `RinexConverter.kt`：TXT → RINEX，历史有修复；此次下载任务没动转换器。
- `LivePppController.kt`、`LivePppNative.kt`、`LivePppProducts.kt`：实时调度、JNI 数据、产品选择。
- `PostPppProducts.kt`、`rtk_jni.c`：安卓后处理。
- `app/src/main/cpp/gnss_jni.cpp`：实时 JNI，nativeInit／nativeProcessEpoch／nativeRelease。
- 共享核心在 `app/src/main/cpp/ppp_live/rtklib`；不要误改另一个遗留 `cpp/rtklib`。
- CMake 的 `gnss_ppp_live` 目前没有链接 RTCM 解码文件；`rtklib_engine` 后处理目标有链接。
- 9 项产品顺序固定：NAV、SP3、CLK、BIA、IONEX、VMF左、VMF右、orography、ATX。
- 产品目前在启动时加载；没有实现实时 SSR 更新或长时间会话的完整产品热更新。

10/18 交接核对发现：PC 与 Android 的 ppp.c 原始字节／换行归一化哈希不同，不能简单写“字节一致”。
但忽略 C 注释及空白后，两者 34144 个词法 token 一致，差异在注释等文本。
rtklib.h、smartphone_ppp_config.c、gnss_adapter.c、gnss_signal_policy.c、ephemeris.c、preceph.c 换行归一化后一致。
这不等于验证所有源码、编译器、平台浮点结果都一致；不要再次盲目覆盖源码来“同步”。

### 当前四频共用参数（不是旧三频实验参数）

- NFREQ=4，nf=4；默认 GPS＋GAL＋BDS（navsys=41），不启用 GLONASS。
- 编译带 ENAGLO 仅说明结构支持，不能说 GLONASS 已参与解算。
- 静态 PPP（mode=8）、dynamics=0、EPHOPT_PREC；浮点，AR 关闭。
- IONOOPT_EST，TROPOPT_EST；高度角 15°，maxout=30。
- phase err 常数／高度角项 0.008／0.012 m；eratio=100,70,60,100。
- 默认配置标识 `pc-baseline-20260930-v1`；签名要以当前日志为准，别把历史 `240c4d93`／`13e7e58d` 当通用真值。
- 当前共用 options：

```text
-GAP_RESION=120 -IONCONS=1.5 -IONCONSINT=1 -VMF3SIG=0.15
-VMF3ZWDSIG=0.30 -VMF3ZWDINT=300 -DOPPSM=0.90 -DOPPWARM=0
-PREPROC=1 -DOPPSLIP=0.50 -CODEJUMP=30 -MWTHRES=5
-BDSCODEVAR=1 -BDSCODEWARM=120 -WGTELCN=0 -PPPDIAG=1
-PPPQUAR=3,120 -GALE5BPHASE=0
```

这些是现有状态，不是新账号可擅自“优化”的建议。PC 诊断用 `43c43b2` 时为 nf=3、DOPPWARM=10，不能混用。

### 信号策略：区分“核心支持”和“正式 Android 输入允许”

当前 ppp-safe 允许：GPS 槽1 1C、槽3 5Q；GAL 槽1 1C、槽3 5Q；BDS 槽1 2I、槽2 7I、槽3 5P。
Galileo E5b／7Q 不送入 ppp-safe；BDS 5Q 当前无相应 RCB 且历史产品缺 OSB，不送入。
BDS 1P 在 full 研究路径可识别，原来与 2I 抢槽导致差异；正式 ppp-safe 不让它抢主频槽。
PC／RINEX 四槽核心后来支持 B1C 放第4槽，但**NFREQ=4 不等于 Android ppp-safe 已纳入 B1C**；当前政策代码没有该准入。
不能为了复现 RINEX 而改正式信号规则，必须单独验证产品支持、实际使用与状态／坐标。

### 大气与偏差：避免重复误解

- 对流层状态通常存总 ZTD；估计 ZWD=ZTD-ZHD，不能不改索引／观测方程就把状态解释为纯 ZWD。
- VMF3 用于映射、ZHD、初始化；当前 VMF3ZWDSIG>0 还会加入湿延迟软约束。
- VMF3 文件“加载成功”不等于约束已生效，需看 PPP_PRODUCT_USE／PPP_DIAG_ZTD。
- orography_ell_5x5 是格网高程静态文件，不是另一种实时对流层产品。
- IONEX 是电离层未知参数的软约束，需注意时间相关性，不能每秒把同一产品当独立真值无限强化。
- 卫星 OSB 和接收机 RCB／IFB 不同。浮点 PPP 不等于可以忽略码偏差。
- 现有 corr_meas 对当前有效 code OSB 用 P-=OSB；已知 OSB 过期时会清除该码观测，不能擅自删检查或延长有效期。

## 5. 已完成的输入一致性实验：不要再从零排查

主要样本：`E:\RTKLIB_Data\OBS\RawData_20260922_141724.txt` 与
`GEOL00CHN_R_20262650617_00U_01S_MO.rnx`，639 历元。

### ADR 问题已定位并修复

- 原来 ADR uncertainty<=0.5 m 硬门限丢掉大量正常相位：旧实现仅 538 条，修改 reset/slip 后同门限 542 条。
- 1.0／2.0／5.0／off 均有 21599 条 phase，Q6 从 7/639 恢复至 628/639。
- 0.5 m 门限丢掉 21057 条原本可用的已选相位；有效 ADR 不确定度中位数约 0.500625 m，不应恢复该门限。
- 正式默认 `--adr-unc-max 1.0`，保留 off 诊断；ADR uncertainty 当前不直接进入 PPP 权重。
- ADR_VALID＋finite＋非零时 L=ADR/lambda；RESET／SLIP 保留本历元 L，同时置 LLI_SLIP；半周映射 LLI_HALFC。
- 有 P/L/D/LLI、相位米差、多普勒米每秒差、缺相位原因等历史报告；不要只追 Q6。
- 权威报告：PC `gnss_replay\ADR_PHASE_DIAG.md`。

### 同 Q6 不等于同轨迹，正式 ppp-safe 已做过验证

- RINEX：Q6=628/639，phase=17650。
- TXT_FULL：同 Q6，但相对 RINEX U 差 RMS=0.80748 m。
- TXT_RINEX_COMPATIBLE：U 差 RMS=0.00749 m。
- TXT_COMMON_ONLY：U 差 RMS=0.000045 m（诊断模式，依赖 RINEX，不能作为正式实时模式）。
- 多出的相位约3955条，主要是 BDS 5Q 3677条、BDS 1P 277条；5Q 没有 OSB 且当前模型无相应 RCB，进入 PPP 会改变 ZTD／坐标。
- 正式 ppp-safe 不依赖 RINEX；同639历元 E/N/U 差 RMS=0.00564/0.00727/0.02356 m，最终差=+0.00250/-0.00250/+0.01366 m。
- 有／无 --rinex-obs 的 ppp-safe 观测、坐标、状态、trace 一样，只有耗时不同。
- 这仅证明该样本上的入口相对一致性，不能宣称绝对厘米精度或所有手机均验证。
- 权威报告：`GNSS_SIGNAL_PPP_EQUIV.md`、`GNSS_PPP_SAFE_REPORT.md`。
- 快／1×回放已用 monotonic clock，保持一个 rtk_t；不是每历元 sleep(1)。
- 现四频配置另有20历元 smoke：TXT_FAST／TXT_REALTIME 坐标状态完全一致，19 Q6，0 deadline miss；不能把这个短测冒称639历元全程测试。
- 报告：`gnss_replay\config_unification\txt_smoke\REALTIME_VS_RINEX_REPORT.md`；另有更早基线结果，注意报告路径／配置。

## 6. 找回截图结果的版本：已完成

截图标题是 `K80-GNSS00GEO_R_20260760533_20M_01S_MO.pos`，不是3/21。
匹配特征：1200历元、Q6=1187；Q5在0-9、299、704、1126秒；
U峰值4.0373m，最后N/E/U=-0.70128/+0.35140/+0.21780m。
全历元3D RMS约1.653916m，末300历元约0.834849m（依照指定参考坐标）。

- 结果：`work\rtklib_audit\march17_bds_equal.pos`。
- 它与当时公共同名 .pos 的 SHA256 完全相同：
  `8B01EAD5226B0CE61B606DED95AD80955B1B326491574AD207D8DF2ED9E0A369`。
- 历史运行记录确认 9/27 的 BDSCODEVAR=1、DOPPWARM=10、nf=3；可追溯代码基线 `43c43b2`。
- B1I_only_final_20260929.pos 也有同形曲线，但不要仅凭文件名归因。
- 不是更早 k80_final_verify.pos 的 1.712／0.863m，也不是后来的四频版。
- 单独源码／ZIP已导出，见第2节；没有为了找版本而覆盖现用工程。

## 7. 3/21 RTS 与 NRT 差异：已隔离主要来源，未修复生产模型

详细证据必须看：
`C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp\work\product_difference_20261011\RTS_NRT_CAUSE_REPORT.md`

该目录还有 run_cases.py、make_coarse_clock.py、独立 snapshot、bin、每组 .pos/.stat/.trace。
snapshot 仅改复制件路径入口／诊断删星入口，核心仍为43c43b2；正式工程未改。
用户当时未保存NRT结果，因此做了配套NRT、混合BIA等多组对照，而不是臆测其当次选择。

基准：`GNSS00GEO_R_20260800210_10M_01S_MO.rnx`，3/21 02:11:12–02:21:11 GPST，600历元，参考482.202m。
相同 NAV、IONEX(COD0OPSPRD)、VMF3 H00/H06、ATX、参数。RTS_ALL 与用户最新 .pos 文件哈希完全相同：
`9A176BF5BD41D4E86F572C9EEC5BD2BCD18C340ED352C8C578DC0BD4FB2A4501`。

| 产品组合 SP3/CLK/BIA | 最终U误差m | 相对RTS共同Q6的U轨迹差RMS m |
|---|---:|---:|
| RTS/RTS/RTS | -0.4263 | 0 |
| NRT/RTS/RTS | -0.2836 | 0.1430 |
| RTS/NRT/RTS | +0.6961 | 1.0872 |
| RTS/NRT/NRT | +0.9005 | 0.8806 |
| NRT/NRT/RTS | +0.7534 | 1.3269 |
| NRT/NRT/NRT | +1.7783 | 1.9822 |
| NRT，全套且仅共同卫星诊断 | +1.1227 | 1.0757 |
| RTS，仅将CLK降采样300s | -0.7104 | 0.4001 |

上述每组Q6=589/600、Q5=11，Q6没有反映米级差。
NRT全套-RTS：共同Q6 E/N/U差RMS=0.5980/1.7826/1.9822m；3D差RMS2.7321m，最大3D差3.4860m；最终高程差+2.2046m。
全时段绝对U RMS反而RTS1.1916m、NRT1.1776m，因此不要仅凭末点宣布所有统计均恶化。

已确认：

- 主要不是单独SP3轨道；CLK／码偏差和卫星集合改变更明显。
- 首历元12颗共同BDS，系统去均值CLK差距离RMS5.250m；按各自C2I OSB修正有效量 c*dt_sat-OSB 后仍差1.841m。
- 这个数是两产品的差，不是 NRT 对独立真值的精度误差；公共项能进入钟差／ISB，星间剩余差不能全由单个ISB消除。
- 本地RTS_CLK文件虽命名05S，实际G01间隔30s；NRT间隔300s，不能只看文件名。
- preceph.c EXTERR_CLK=1E-3m/s 将节点时间差加入CLK标准差，PPP总R也因此变化。中点新增项约0.015m vs0.150m，不代表总R必定100倍。
- 降采样实验同时改变插值值和方差，不能把0.4001m差全部归于权重。
- 平均ns由25.630变28.715；NRT新增实际使用载波C06/C11/C16/E29，还有部分GEO码观测。
- RTS估计ZWD=-0.0349m，NRT=-0.2466m；VMF3两组均生效，显示高度/ZTD吸收不一致，不是天气突变。
- 删除GEO后最终U+2.7716m，删C01-C18后-4.7976m；不能靠永久删星掩盖问题。
- 本地diag_20260321的FIN全套同旧模型最终U=-4.3699m、ns不同，也不能宣称换FIN必好。
- 此处NRT CLK生成时间是3/22，不能仅凭“NRT”名称断言3/21当时已可获得或断言整份都是预测误差。

尚未闭环：如何区分产品本身残余、逐信号码偏差基准／天线参考频率适配、可观测性与随机模型。
未实施生产滤波修复，不可声称已解决所有高程系统偏差。混合产品只作因果隔离，不能因为末点更近就推荐混用。

## 8. 安卓产品下载修复：10/18 已实施并验证，未提交

详细报告：`E:\android\test\FTPGet\docs\PRODUCT_DOWNLOAD_FIX_20261018.md`。

已修复：

1. BKG FTP21在实测时连接拒绝，而HTTPS可用。NAV优先HTTPS目录，失败再FTP。
2. 固定一次下载的UTC参考，NAV从当天到前3天依次查找，跨年／闰年正确处理。
3. 兼容BRDC00WRD_R/S、IGS、BRDM DLR等RINEX3合并NAV，拒绝RINEX4；支持.rnx和.gz。
4. `.part` 完整性、gzip CRC、RINEX3 NAV头／首条完整记录校验后才发布，不让坏下载覆盖旧目标。
5. NAV独立下载，不再被SP3/CLK是否成功挡住。回退会警告实际日期，不能说“今日NAV就绪”。
6. NRT扫描当前及相邻GPS周；只选标称覆盖当前参考时刻、同起算时次／持续时间的SP3+CLK组合；同一时次优先较高率CLK。
7. 不再把大气产品日期跟着精密产品起算日回退；当前VMF3／IONEX仍面向当前UTC日。
8. Live选择NAV按产品日期及完整性，不按mtime；跳过损坏近日期残留；NRT在Live也按组合选。
9. 后处理近期NAV不再仅被文件名日界线拦住；真实TOE／健康状态仍交RTKLIB，绝未延长星历有效期。
10. BRDM支持公共产品导出；完成状态不再被最后一条日志覆盖。

没有恢复动态产品“已存在就跳过下载”：每次点击仍检索并下载；本地解压／完整性检查不是下载缓存。
ATX、orography仍用原有内置／本地静态文件，不重复联网。

实际Kotlin公网冒烟测试（不是只用Python看列表）：

- 下载并验证 `BRDC00WRD_R_20262910000_01D_MN.rnx.gz`，171140 bytes。
- WHU当前周2441空，自动取上一周2440的
  `WUM0MGXNRT_20262892300_02D_05M_ORB.SP3.gz`＋`WUM0MGXNRT_20262892300_02D_30S_CLK.CLK.gz`，配对和gzip通过。
- 这些网络状态仅代表10/18当时，不是以后永远如此。标称覆盖不是实际记录已全部有效，JNI仍检查。

主要新增／改动：

- BroadcastNavPolicy.kt、NrtProductPolicy.kt（新增）。
- FtpDownloader.kt、MainActivity.kt、LivePppProducts.kt、PostPppProducts.kt、PublicGnssExporter.kt。
- 测试：BroadcastNavPolicyTest(9)、NavDownloadFallbackTest(8)、NavHttpsDownloadTest(6)、
  NrtProductPolicyTest(8)、LiveNavSelectionTest(3)、NavHttpsPublicSmokeTest(2)，PostPppProductsTest现2项。
- 含既有测试共60项，59通过，0失败，1跳过；跳过的是真实TXT未配置的RinexConverterRegressionTest，不能说它通过。
- `:app:assembleDebug`成功，四个ABI构建；没有改C/C++核心，`git diff -- app/src/main/cpp`为空。
- 这些测试并非手机安装后实采／PPP精度验证。

## 9. BIA 问题：已诊断，尚未修复获取策略／降级策略

**最容易被新账号误当作已修复的问题：BIA不在第8节修复范围内。**

当前MainActivity仍调用旧 downloadLatestProduct，目录：
`ftp://igs.gnsswhu.cn/pub/whu/phasebias/2026/bias/`，只匹配UTC当前 YYYYDDD 且.gz。
无匹配时直接false，日志不够明确。BIA请求仍放在pairOk内。
当前NRT轨道／钟差却配RTS目录BIA，产品基准配套性需另行核对。

10/18实测：

- phasebias目录607份BIA，最新RTS为 `WUM0MGXRTS_20262900000_01D_05M_OSB.BIA.gz`。
- 当时不存在包含2026291的当天文件，因此当前代码没有匹配项，不是本地存储错误或下载传输失败。
- 已真正下载该290文件检查：881条码OSB的实际有效期全是 `2026:290:00000` 到 `2026:290:86400`，
  对10/18当前历元已过期。
- 其文件头却写第291天，**不能用文件头代替每条 BIAS/SOLUTION 的有效期**。
- 服务器在其他目录可能有别的产品，此次未证实有可覆盖当前时刻并与所选NRT配套的OSB，不可写“已经找到”。

不能直接把昨天BIA改名、改有效期、去掉检查或套到今天，也不能像NAV一样下载旧文件后宣布当前OSB有效。
可以区分“获取最新归档”与“当前历元可用”，并明确提示未发布／过期；这项代码尚未做。

### 为什么现在会卡住实时PPP

- `gnss_jni.cpp` 产品加载要求BIA读取成功、nav->nosb>0。
- 逐历元检查至少一条OSB时段包含当前epoch；否则报 BIA_TIME_ERROR，返回空结果。
- 这只是全局检查，不证明每颗卫星每个信号都有有效OSB；PPP内仍需逐信号检查。
- 当前核心碰到已知但过期OSB会清除对应P；直接删JNI检查也不构成安全降级。

用户不做AR，并不表示这份BIA可忽略：现在主要用其**卫星码偏差**修正伪距。
浮点相位常数偏差通常可并入弧段模糊度，但码偏差和钟差基准仍必须处理。
接入连续改正服务是正式实时方向，或另做经过验证的DCB/TGD/BGD等信号级降级；没有实现、也没有验证这样降级后的精度。

## 10. 实时流：仅完成讨论和源码检查，没有开始实施

用户询问实现难度、如何室内调试。已建议：先PC验证，再安卓，不重写滤波器。
没有实现NTRIP客户端、SSR解码接线、改正缓冲／热更新、同步录流或SSR回放。
现有RTCM／SSR代码只是可复用基础，不能声称当前APK已支持实时流。

实际源码：

- 当前 opt->sateph=EPHOPT_PREC；改SSR需明确APC/CoM模式、广播星历IOD等匹配，不能仍当SP3加载。
- `ephemeris.c` 有 EPHOPT_SSRAPC／EPHOPT_SSRCOM 分支。
- `ppp_live/rtklib` 有 rtcm.c／rtcm3.c等；live CMake目标尚未链接它们，后处理目标链接。
- 具体流可能用RTCM SSR或IGS SSR，需核对解码支持、消息类型、信号bias和实际挂载点；不要臆造可用服务／账号。
- 要成套检查轨道、钟差、码偏差；不要只把实时码偏差随意拼在NRT CLK上，也不能OSB与SSR偏差双重改正。
- 网络线程、改正缓冲和观测处理要分离；rtk_t连续，不因重连每秒rtkinit；过期改正不能无限沿用。
- 需要先决定断流停止／明确降级策略，日志区分网络连接、完整改正、时间有效、实际用于PPP。
- 用户尚未提供实时caster账号／挂载点；凭据应放不提交的配置，不要求用户把密码写入公开对话／源码。

### 用户不能在室内采集卫星：已提出但未实现的调试路线

1. 室内先调NTRIP认证、网络重连、RTCM CRC／解码、卫星信号覆盖、改正时间，不需要手机收到卫星。
2. 室外一次采10–20分钟功能样本，同时保存Android TXT、原始SSR字节、同期广播星历、接收时间与产品／参数清单。
3. 优先同一App记录统一时间基准；流在观测前提前记录并贯穿全过程。若PC／手机分别记录，必须处理两个单调时钟的对齐，不能直接相减。
4. 室内按原始到达顺序重放观测、NAV、SSR。处理某epoch只能用当时已到达的改正；不能提前加载整段未来改正。
5. 同时保存改正自身GNSS历元和接收UTC／monotonic时间，回放要还原延迟、断流、更新和不完整消息。
6. 历史RTCM解码的周／日期参考也必须是采集日期，不得用电脑今天的日期推断历史消息。
7. 3/21 TXT不能配今天SSR。要么找同期档案且核对实际接收时序，要么重新采同期完整包。
8. 既有gnss_replay有TXT_FAST／TXT_REALTIME基础，但**没有SSR同步回放**，需要新增。
9. 先比较fast与1×在相同事件顺序下的观测／改正使用、坐标和状态，再实机实时对照。不能只以Q6变多验收。
10. 10–20分钟只是功能样本，不是手机厘米精度或跨场景泛化证明。采集端／信号规则变化与最终实机验证仍可能需要新数据。

建议后续顺序（待用户授权实施，不是本轮已经开始）：

- 明确可用caster／挂载点及产品族，先PC只录流和输出解码诊断。
- 增加完整同步记录与因果回放，建立可以在室内重复的基线。
- 接入同一持续rtk_t，核对信号码、IOD、改正龄期、轨道参考点和偏差参考。
- 验证后移植安卓，并保留文件模式与历史基线，不顺手调整随机模型和大气约束。

## 11. 构建、测试与状态检查

环境：Windows PowerShell，文件访问以用户授权为准。推荐用rg找文件；局部编辑用apply_patch。
没有必要生成新worktree或新任务；不能把旧对话中的“继续”当成无限制授权。

Android Gradle（`E:\android\test\FTPGet`中运行）：

```powershell
$env:JAVA_HOME='E:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --offline --console=plain
```

Gradle8.11.1，使用:testDebugUnitTest，**不存在 :app:testClasses**。
`--offline`指Gradle依赖解析；手工启用的公网测试仍会访问网络。

```powershell
$env:GNSS_PUBLIC_NAV_SMOKE='1'
$env:GNSS_PUBLIC_NRT_SMOKE='1'
$env:GNSS_PUBLIC_NAV_DAY='2026-10-18'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --rerun-tasks
```

不设置公网开关时相应测试会跳过；固定日期应按验证目的更新，不能把归档测试称为“当前最新”验证。
真实转换回归还需GNSS_RAW_TEST_FILE和GNSS_RINEX_TEST_OUTPUT。
XML报告：`app\build\test-results\testDebugUnitTest`。
APK：`app\build\outputs\apk\debug\app-debug.apk`，10/18快照最后写入09:39:33，SHA256：
`0A54B0A10A2B08F6D2ECEBD1F483B4C5FA082F7A2AD9735F83DD09AC6982A006`。
前一次APK哈希不同，不必据APKZIP字节变化就推断模型改变；也不要把旧日志冒充新APK的完整手机验证。
没有在本轮自动安装／卸载手机App。

PC MSBuild可用：
`E:\Program Files\Microsoft Visual Studio\2022\Professional\MSBuild\Current\Bin\MSBuild.exe`。
脚本分析Python曾使用：
`C:\Users\Drinkey\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe -X utf8`。
换账号后运行时路径可能不同，应检查；不要依赖旧账号工具状态或固定临时session ID。

## 12. 安全与未提交状态

- 10/18交接只做只读核对和新增说明文档，未重新跑所有实验、未改源码模型、未恢复Git、未上传。
- Android已有Compose UI、PublicGnssExporter、学习文档等未提交改动。当前下载修复也依赖新policy文件；
  仅拉GitHub的ca2e12c无法拿到这些修复。迁移电脑／账号需保留完整工作目录和未跟踪文件。
- `.vs`／build／CMake输出可能有大量变化，不要把它们当成需要恢复整个仓库的理由。
- **凭据安全警告**：历史做过vmf3.local.properties外置，Gradle有BuildConfig字段；但10/18实际检查
  MainActivity仍存在VMF3用户名／密码字符串常量。真实值不记录在这里。
  任何上传前必须单独处理并核查，不能因为存在LOCAL_VMF3_CONFIG.md就声称凭据已安全外置。
- 本轮未擅自改这段既有用户修改。APK内置凭据也不是安全保密方案。
- 切勿用“分享整个旧对话”代替安全交接，历史工具输出可能出现敏感信息。

## 13. 重要文件索引与非算法文档

- 安卓 docs：ANDROID_GNSS_DATA_GUIDE.md、ANDROID_GNSS_COMMENT_VALIDATION.md、
  ANDROID_GNSS_API_QUICKSTART.md、Android_GNSS原始数据采集学习手册.docx、PUBLIC_GNSS_FILES.md、LOCAL_VMF3_CONFIG.md。
- 学习文档面向初学者，包含Kotlin、Android API、TXT/RINEX、JNI，重点不是PPP公式；用户喜欢详细中文注释。
- 安卓根目录：ANDROID_PPP_LIVE_V1.md、ANDROID_PPP_POST_PC_SYNC.md、PPP_CONFIG_UNIFICATION.md。
- PC gnss_replay：ADR_PHASE_DIAG.md、GNSS_SIGNAL_PPP_EQUIV.md、GNSS_PPP_SAFE_REPORT.md、README.md。
- 其他历史审计：pc_system_audit_20261002、glonass_experiment/GLONASS_G1_DIAG.md、
  coarse_experiment/COARSE_QC_E5B_REPORT.md、config_unification 等；报告不代表所有建议已合入主线。
- 实验目录：work/rtklib_audit、work/ppp_diagnosis、work/weight_eval、work/height_drift_20260929。
- 开题报告已生成：
  `C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp\output\智能手机实时PPP抗差自适应滤波开题报告.docx`。
  参考原模板，个人信息留空，开题后12个月；渲染／验证素材在work/proposal_20261003。
  不能把报告中的拟研究工作写成当前已实现成果。

## 14. 新账号第一轮应做什么／不应做什么

应做：读本文件与两份重点报告；确认用户仍在同一机器；只读git status／HEAD；检查所需新文件存在；
将“下载修复已完成、BIA策略未完成、实时流尚未实现、室内因果回放待开发”准确复述给用户，确认下一步。

不应做：再次分析0.5m ADR门限；重新让BDS5Q抢槽；永久禁用星座追求更好曲线；
把当前实时流看作已实现；拿今天SSR配3月观测；把过期BIA改有效期；覆盖复制工程；
为了同步注释而重写PPP；凭截图断言当前绝对厘米精度；把未提交修改直接当可丢弃文件。

本文件及 `CODEX_HANDOFF_SNAPSHOT_20261018.json` 是交接快照，不是永远最新的事实。
后续如果源码、产品、时间或账号环境变化，应只针对变化重验，不要复述旧结果冒充新结果。

## 15. 可携带的交接压缩包

`C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp\output\GNSS_PROJECT_HANDOFF_20261018.zip`

包含本文件、JSON快照、HANDOFF_READ_FIRST.md接手提示，以及evidence目录中的下载修复、
RTS/NRT、ADR、信号等价性、ppp-safe和四频短时回放报告副本。
只有说明和证据报告，不含源码、账号配置、原始观测、APK或完整实验结果；不是工程备份。
换电脑还必须迁移完整工程工作目录，尤其是未提交／未跟踪文件。
若旧账号工作区路径不可访问，可以先读取包中的报告副本；需要原始实验时再向用户确认数据路径。
