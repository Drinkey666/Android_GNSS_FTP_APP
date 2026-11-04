# GNSS实际跟踪状态记录（2026-10-26）

本次仅补记录缺口，不改变PPP输入、信号选择、改正、R/Q/P0或抗差机制。未安装/替换手机上的主App，当前新构建APK尚未在真机验证新增日志。

## 已完成的独立真机探针

`GnssFullTrackingProbeTest.kt`请求Full Tracking，独立注册GNSS和位置回调，不启动PPP，不记录位置，不修改全局定位设置。用户授权安装临时测试包；主APK SHA前后均为`a8b84c13ae5dcce4ec37b4c21b32fda223181bc1775f340aa1a44005ac034788`。

首次安装被取消；首次已安装测试等待Activity生命周期超时；第二次未取得回调；第三次显式前台启动MainActivity后成功，15次回调均报告Full Tracking=true，0次报告false，15条测量均无ADR_VALID。原生硬件时钟不连续计数为0。不能据此推断历史记录的跟踪模式、室外连续载波或定位精度。用户当前无法移到开阔处，未重复采集。

PC证据：`E:/GNSS/PPP_only/validation/oct25_tracking_probe_v1/`。探针已注销自己的回调和位置监听，未留下后台采集任务。

## 修改与字段

- `GnssDataLogger.kt`：既有54列Raw的索引37（IsFullTracking）原来固定为空；现在API34+且event.hasIsFullTracking()为真时写芯片实际true/false，其余仍为空。不能将请求true当实际true，也不能将未知写false。既有RINEX转换器只解析0～35中的实际字段，不读37；实时JNI直接接收原event，未修改。
- `LivePppController.kt`：独立`ppp_b_verified_<会话开始系统毫秒>.tracking.csv`，与profile/trace/obs/solution/errors共用会话名。在产品初始化前记录，产品失败也保留事件证据。系统毫秒只命名文件，产品和观测时间仍用GNSS clock。
- 新CSV每次回调记录：event_index、clock_time_nanos、hardware_clock_discontinuity_count、has_full_bias、full_tracking_requested、full_tracking_reported、full_tracking、measurement_count，以及ADR有效/重置/周跳/未解决半周的计数。
- `full_tracking_requested`记录本控制器API31+实际使用的请求；旧注册接口留空。`full_tracking_reported`表示芯片实际状态是否可取得，`full_tracking`为true/false/空。
- ADR计数为该事件的位标志计数，可重叠，并非PPP接受数或最终弧段重启数；不新增质量筛选。半周未解决指REPORTED且非RESOLVED。
- 新诊断创建/写入/getter异常只禁用tracking日志并警告，不终止PPP或修改观测。记录有文件I/O成本，不承诺耗时逐位相同；未改变原生计算。

## 验证与回退

`:app:testDebugUnitTest :app:assembleDebug --offline --console=plain`完成：126项中121通过、5跳过、0失败/错误；12份四ABI原生库SHA与修改前一致。新增实际日志尚未在安装新版主App后验收，不能把独立探针通过当作生产日志真机验收。

此次修改前备份与只含本次差异的patch：`tools/gnss_tracking_metadata_20261026/`，构建/测试/原生与安装包SHA见`verification.json`。原文件还有用户及此前工作，回退应只反向应用本次patch；不要用Git reset或整文件覆盖破坏后续修改。未提交/推送，未把实验候选加入生产C++。
