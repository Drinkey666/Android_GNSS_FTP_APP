# 手动 WGS84 度分秒参考及解算误差对比

2026-10-20。安卓工程：`E:/android/test/FTPGet`。

## 最终输入约定

用户明确要求：**度分秒经纬度、WGS84坐标系、大地高（椭球高，m）**。本次最终界面以此为唯一约定，不显示正常高/高程异常选项。

定位页顶部“WGS84参考坐标”→“设置参考/编辑参考”：纬度、经度分别填写度、分、秒，选择北纬/南纬、东经/西经，再输入大地高。度、分为非负整数；秒为[0,60)有限数；纬度度数≤90，经度≤180，边界度数时分秒必须为0。高度允许有限负值，不猜测或套用高程异常。

保存后使用`degree + minute/60 + second/3600`转换为十进制度，南/西方向取负。显示DMS秒保留6位小数，使用整数微角秒进位，避免出现60秒/60分。内部保存实际转换结果，不使用显示舍入值反复更新参考。

参考坐标独立保存至App私有SharedPreferences，重启可恢复。旧十进制度/正常高版本的值保留在旧key，但本版要求重新用DMS保存，不把旧输入静默重解释。不会把历史K70/iQ真值文件的正常高批量改成大地高。

## 误差行为

- 手输参考替代手机系统定位作为比较原点；系统Location仍在单独区域显示，不作为真值来源。
- 对实时每个返回历元，以参考经纬度为ENU方向，WGS84参考椭球坐标为原点，投影`solution_ECEF - reference_ECEF`得到E/N/U。
- 水平误差=`hypot(E,N)`；三维误差=`hypot(horizontal,U)`。有符号E/N/U按东/北/天为正。
- Q0/缺少参考/非有限坐标不显示“0误差”，而是明确显示等待或无有效坐标。Q5显示单点提示，不冒充PPP状态。
- 编辑/清除参考只重新计算显示，不重启滤波，也不触碰JNI配置、观测值或产品选择。
- 实时`GNSS_Live/ppp_b_verified_<time>.errors.csv`记录每历元误差和该行实际参考值；中途改参考后不会丢失原参考来源。
- RINEX后处理完成后，在POS旁生成`.pos.errors.csv`，界面显示末历元误差。只接受本App的GPST/WGS84/ellipsoidal纬经高POS格式；导出完成才将`.incomplete`提交为正式CSV，不覆盖原POS和旧CSV。
- CSV记录十进制度、height_type=ELLIPSOIDAL与reference_frame=WGS84。误差计算不做地图坐标、投影坐标、坐标框架历元或大地水准面转换。

## 不影响滤波的证据

参考只由Kotlin输入/显示/日志对象持有。`LivePppController`在JNI已返回结果后读取一次不可变参考快照计算误差；没有新增参考坐标JNI参数。后处理导出也在`runPpp`返回之后发生。

32个PC同步内核源码SHA均保持一致；APK中8个正式解算.so与B_VERIFIED移植验证版本逐字节相同。没有重新调R/Q/P0/GIM、修改Joseph、启用BRIDGE3或自适应滤波，也没有用参考坐标初始化PPP。

## 测试及交付

- `:app:assembleDebug :app:testDebugUnitTest --offline --console=plain`通过。
- 本次新增31项：误差/输入/CSV/POS等21项＋DMS专项10项，全部通过。
- 当前JVM总计106项，102通过、4跳过、0失败/错误。
- 覆盖ENU方向、WGS84椭球已知轴、PC K70末点黄金值、跨日界线、极点、NaN/Inf、Q0、参考持久化编码、CSV逐行参考、POS安全导出、DMS范围、半球及舍入进位。
- 最终APK已成功保留数据更新安装至K70（2407FRK8EC），已用设备`base.apk` SHA核对安装的就是本次产物。
- APK：`app/build/outputs/apk/debug/app-debug.apk`，55,057,692字节；SHA256=`d37ed26b82ec9197f1d6ae99b5568c5081f7750c704b153fd85b9b0eee350973`。
- 额外测试APK安装被用户取消，未执行仪器测试；ADB输入注入被手机安全策略拒绝，最终屏幕截图为黑屏/锁屏，不能宣称已完成最终DMS界面的设备视觉QA。未绕过限制或再次安装测试包。主App安装成功不受此限制影响。
- 证据：`tools/manual_reference_checks.json`、`tools/manual_reference_build.log`、JUnit XML。修改前备份：`tools/ppp_sync_backups/manual_reference_20261020_163305/`。

## 修改文件

新增：`ReferencePosition.kt`（数学/格式）、`ReferenceDmsInput.kt`（固定DMS输入）、`ReferencePositionStore.kt`、`PositionErrorCsv.kt`、`PostPositionComparison.kt`、`ui/ReferencePositionSection.kt`和两个单测类。

修改：`ui/PosScreen.kt`、`ui/MainUiState.kt`、`MainActivity.kt`、`LivePppController.kt`、`res/values/strings.xml`。PC/C/JNI源码未改，无Git提交/推送。未重复K70的660历元数值回放；仅复用其已保存黄金值核验误差计算。
