# 中文注释改动验证记录

日期：2026-10-05。工程：`E:\android\test\FTPGet`。

## 修改范围

给 8 个数据入口/调度源码文件补充中文注释，新增 `ANDROID_GNSS_DATA_GUIDE.md`。
不调整采集、转换、JNI 的代码逻辑；不修改 PPP 核心或参数，不修改 adapter/ppp-safe。
工程修改前已有未提交内容，全部保留，没有回退或替换为 Git HEAD。

## 注释与逻辑隔离检查

以本次修改前本地快照为基线，保留字符串/字符常量，去除注释后比较代码 token：

| 源文件 | 结果 |
|---|---|
| GnssDataLogger.kt | 一致 |
| RinexConverter.kt | 一致 |
| MainActivity.kt | 一致 |
| LivePppController.kt | 一致 |
| LivePppNative.kt | 一致 |
| LivePppProducts.kt | 一致 |
| GpsTimeUtil.kt | 一致 |
| gnss_jni.cpp | 一致 |

`app/src/main/cpp/ppp_live/rtklib` 中 30 个文件逐字节 SHA-256 均与本次修改前一致（含快照清单）。CMake 的 PC 核心快照验证亦随编译通过。

检查脚本和修改前备份放在工作目录：
`C:\Users\Drinkey\Documents\Codex\2026-09-21\ppp-ppp\work\android_comments_20261005`。

## 编译

执行 `:app:testDebugUnitTest :app:assembleDebug --offline`：BUILD SUCCESSFUL。
原生 arm64-v8a、armeabi-v7a、x86、x86_64 构建完成。

APK：`app/build/outputs/apk/debug/app-debug.apk`，34,780,071 字节。
仍有原先旧 Carrier/AGC/FTP 接口弃用提示及 Channel API 使用提示；本次未通过修改行为来消除这些警告。

## 回归测试

第一次运行：7 项通过，真实 Raw 转换测试因未设输入而跳过。
随后指定真实文件重新执行全部单元测试，最终：**8 项通过，0 失败，0 错误，0 跳过**。

| 测试类 | 测试数 | 最终结果 |
|---|---:|---|
| ExampleUnitTest | 1 | 通过 |
| PostPppProductsTest | 1 | 通过 |
| ProductCacheTest | 5 | 通过 |
| RinexConverterRegressionTest | 1 | 通过，使用真实 TXT |

真实转换输入：`E:\RTKLIB_Data\OBS\RawData_20260922_141724.txt`。
新生成 OBS 放在工作备份目录，不覆盖用户原始文件或既有解算结果：
`raw_regression_20260922.rnx`，4,977,472 字节。

转换头部记录：RAW=53,616，CODE=47,398，PHASE=5,986，DOP=48,842；REJ_CODE=10，SLIP_DOP=0，SLIP_MW=0，SLIP_ADR=194，CLOCK_EPOCH=1。
这些是现有转换器的诊断计数，**不是 PPP 实际采用数量，也不证明实时与离线入口已等价**。

## 限制与现有差异

未安装 APK 或进行新手机采集，未运行新的定位精度实验；本次任务是解释现有工程，不是优化精度。
文档已如实列出离线 ADR 0.50 m 与实时 1.0 m、RESET/SLIP、半周位、信号准入等现有差异。
主说明中的所有这些差异均未在本次被改变。

## GitHub 上传前的本地凭据迁移验证

随后根据用户授权，将 MainActivity 中的 VMF3 明文账号改为读取本地配置生成的 BuildConfig。
此项是单独的配置迁移，不再属于前述“只改注释”的 token 检查范围。

- 本地配置与生成的 BuildConfig 两个字段逐值一致，下载凭据未改变（不记录真实值）。
- `:app:testDebugUnitTest :app:assembleDebug` 再次成功，8 项测试全部通过、无跳过。
- 全部待提交文件按实际凭据值扫描通过，`vmf3.local.properties` 不在 Git 索引中且已被忽略。
- PPP 共享核心快照仍与本次注释工作开始前一致；未更改解算参数。
- 编译缓存、历史同步备份和个人构建日志不上传；本机文件保留。

新机器认证配置与 APK 安全边界见 `LOCAL_VMF3_CONFIG.md`。
