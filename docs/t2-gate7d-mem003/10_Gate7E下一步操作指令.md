# Gate 7E 下一步操作指令

建议项目发起人确认本报告后复制以下指令。它只准入 Gate 7 实际获批能力的内部汇总验收，
不准入 `T2-SAA-001/SUB-001/SVC-001`，不解除任何外部 P0。

```text
我确认《T2-MEM-003 独立周门禁报告》，接受 T2-MEM-003
CONDITIONAL PASS。

同意将 T2-MEM-003 由 VERIFIED 更新为 ACCEPTED。明确该接受只覆盖三业态默认关闭的
版本化会员权益、会员价、BEST_PRICE/显式叠加、成交快照、原快照退款、签名离线包和
内部软件执行；不代表真实 PII、支付沙箱、真实设备/外设、完整 Alpha、现场试点、
生产、商业验收或商业 SLA。

按 CONDITIONAL GO 启动 T2 Gate 7E / Sprint S23-A：商业 V1 内部汇总验收。

以 T2-MEM-003 最终封存提交
[填写 T2-MEM-003 最终封存提交]
作为工作分支起点，创建独立分支：
t2/gate7e-sprint23a-internal-v1-business-complete

本阶段只允许逐项准入 T2-E2E-004。先核对 Gate 7B—7D 实际获批 Requirement 全部
ACCEPTED，冻结汇总 RTM、正式旅程、环境拓扑、Owner 检查点、数据守恒、P0/P1 标准、
故障 seed、性能边界、证据目录、运行手册和 Go/No-Go，再进入内部 E2E 实现和缺陷修复。

T2-SAA-001、T2-SUB-001、T2-SVC-001 未获独立 CR，继续 DRAFT，不属于本次“实际获批
能力”；不得为通过汇总验收而创建其运行时、数据库表、Controller、页面、任务或绿色占位。

内部汇总必须只通过正式 REST/API、应用服务、事件、Inbox/Outbox 和 Owner 端口，禁止
直接数据库写入、跨 Owner Mapper、测试专用后门、伪造外部成功或前端重算金额/库存/
成本/促销/会员权益。

至少使用两个虚构租户、多组织、多门店、多终端和便利店、零食折扣店、社区超市模板，
覆盖：后台初始化/迁移/开店 → 商品价格/权益包发布 → POS 登录开班 → 秤码/普通扫码 →
促销/会员价/人工优惠 → 挂取单/现金与部分现金 → 成交同步 → 库存/成本/报表 → 部分和
最终退货退款/换货 → 采购/盘点/调拨/补货 → 日结/异常处置 → 关班 → 备份恢复与合成升级。

固定故障至少覆盖重复、乱序、ACK 丢失、UNKNOWN、磁盘失败、进程终止、业务日切换、
跨租户/门店、同键异内容、旧包/坏包、快照/摘要篡改、部分 Owner 失败、投影重建、
迁移中断和安全前向修复。金额、数量、优惠分摊、库存、成本、会员权益和日结必须逐字段
守恒；P0/P1 缺陷必须为 0，否则保持 IN_PROGRESS/NO-GO。

CI 继续执行治理、Server、Web、Flutter Linux/Windows、Android/Kotlin、MySQL、SQLite、
Java/Dart 跨端向量、内部汇总 E2E、迁移/恢复、租户权限、Secret/PII、依赖、SBOM、
许可证、覆盖率和既有 Gate 全量回归；不得降低阈值、跳过测试、自动重跑掩盖 Flaky、
修改已发布迁移或创建绿色占位。

T2-PAY-002、T2-HWD-001、T2-PRN-001、T2-PAR-001 继续 BLOCKED；T2-UAT-001、
T2-REL-001 继续 DRAFT；T2-JSH-001、T2-LIC-001 继续 DEFERRED。Provider 网络、
真实资金、真实设备/外设、伙伴现场、完整 Alpha、生产部署和商业声明保持 0。

T2-E2E-004 的最高结论只能是 INTERNAL_V1_BUSINESS_COMPLETE_CANDIDATE，不得更新或
替代 T2-UAT-001，不得宣称 SANDBOX、REAL_DEVICE、PILOT、FULL_ALPHA、PRODUCTION
或 COMMERCIAL。

完成后提交《T2 Gate 7E / Sprint S23-A 商业V1内部汇总验收报告》等待我确认；
不得自动启动外部 P0、完整 Alpha、现场试点或生产发布。完成后为我整理下一步操作指令。
```
