# 采集码相预处理实验（2026-10-30）

已在本地源码增加采集页显式开关，默认关闭。完整报告：
E:/GNSS/PPP_only/docs/ACQUISITION_CODE_PHASE_REPAIR_REPORT.md

开启后另存 CodePhaseCandidate_*.csv.gz；Raw TXT/原生PPP输入不变，停止后自动按既有授权目录导出。伪距仅在同星连续健康相位支持下生成修复候选；相位异常默认只标记。候选有相关性，不能冒充独立GNSS输入，不可直接喂给PPP。关闭即回退原采集。

依据Mu et al.2024, DOI10.3390/rs16173117的因果差分预测法作工程改编，最高导数噪声1 m/s^4是明示实验假设而非论文数值或通用标定。此方法不保证恢复任意慢漂CCI。

175项JVM：170通过5跳过0失败，含44模型/3写盘/2回放新测试；四段K70共4270历元308324行已处理。103次100m伪距注入被识别，候选相对未注入对照中位差0.348m、最大2.279m，不是真值精度。首版相位修复有74.276m反例，未采用。10/25无P修复候选，不宣称持续东偏已修复。

32本地/前轮12原生库及PC正式52文件不变。原Raw formatter逐字保持；有队列、gap/ADR/clock边界、失败与丢失记录。备份/精确patch：tools/acquisition_repair_20261030/；PC独立证据 validation/acquisition_code_phase_repair_v1/。主APK只构建未安装，未做现场采集验收，无提交推送。
