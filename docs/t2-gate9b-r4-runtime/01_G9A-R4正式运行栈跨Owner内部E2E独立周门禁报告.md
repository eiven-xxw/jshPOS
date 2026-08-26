# G9A-R4 正式运行栈跨 Owner 内部 E2E 独立周门禁报告

## 1. 当前结论

`VERIFIED_CONDITIONAL_PASS_AWAITING_SPONSOR_CONFIRMATION`。

实现候选 `bf7a48bcde02ace78d4909a10318be7266bb5934` 的 GitHub Actions Run
`32917121269` 九个作业节点全部通过。三业态在同一正式 MySQL、Redis、商业 JAR、HTTP、
Vue dist、Flutter POS 与文件 SQLite 运行窗口完成 22 Owner 旅程、36 项守恒及 12 个固定
故障 seed；没有直接数据库后门、Mock/InMemory Owner 或外部成功伪造。

在项目发起人确认前，`G9A-E2E-P1-001` 继续
`OPEN_AWAITING_SPONSOR_CONFIRMATION`，本报告不自行关闭 Finding。

## 2. 授权范围完成情况

| 项目 | 结果 | 证据 |
|---|---|---|
| R4-R0 四个基线失败 | PASS | 可执行红灯回归稳定复现，随后保持为治理门禁 |
| R4-R1 同提交正式栈 | PASS | 当前 commit 构建商业 JAR、Vue dist 与 Flutter；MySQL/Redis/文件 SQLite 同窗运行 |
| R4-R2 三业态 Flutter 正式旅程 | PASS | 便利店、零食折扣店、社区超市均连接正式 JAR；未绑定测试内置 HTTP Server |
| R4-R3 商业运营与 22 Owner 后置旅程 | PASS | 三条旅程各 22 检查点，共 66；每条 12 项守恒，共 36 |
| R4-R4 重启、Redis 丢失与跨 Owner 收敛 | PASS | 实际 JAR 重启 1 次、Redis FLUSHDB 1 次，原事件/命令身份保持 |
| R4-R5 固定故障矩阵 | PASS | R4-F01 至 R4-F12 共 12/12 通过 |
| 外部零执行 | PASS | Provider 网络、真实设备/外设命令、生产 KMS/备份源、直接业务库写入均为 0 |

## 3. 三业态与数据守恒

- `R4-CONVENIENCE / CONVENIENCE`：22 Owner、12 项守恒、日结 `CLOSED`、开店内部状态
  `READY_TO_OPEN`；
- `R4-SNACK / SNACK_DISCOUNT`：22 Owner、12 项守恒、日结 `CLOSED`、开店内部状态
  `READY_TO_OPEN`；
- `R4-COMMUNITY / COMMUNITY_SUPERMARKET`：22 Owner、12 项守恒、批次/效期路径启用、
  日结 `CLOSED`、开店内部状态 `READY_TO_OPEN`；
- 汇总：3 条旅程、66 个 Owner 检查点、36 项逐字段守恒，全部 `PASS`；
- `READY_TO_OPEN` 只代表内部 Owner 检查已完成，支付、硬件、打印和伙伴四项仍精确显示
  `BLOCKED/UNAVAILABLE`，不得解释为真实开店通过。

## 4. V87 前向迁移专项结论

唯一新增迁移：
`V202608260087__g9a_r4_transfer_outbox_version_constraint.sql`，SHA-256
`43cedcb2da270701ccc9fa1f6910b3269ad8ca0ec936fdd981d0205b34926484`。

Android/Database Job 在 MySQL 8.4 中通过：

1. 空库迁移至 V87；
2. 从 V86 精确升级一版至 V87；
3. 第二次 migrate 为零新增迁移；
4. Flyway validate 成功；
5. `aggregate_version=0` 可写；
6. `aggregate_version=1` 后续事件可写；
7. 负版本被 `ck_trf_outbox_version` 拒绝；
8. 重复事件与不可变约束没有退化。

历史 `V202608170018__gate4d_transfer.sql` 未修改，SHA-256 保持
`ea56264ac1b9780e44b415693425e24b3ef02183f5c5e4697d69f435d7340908`；相对 R4
基线的迁移变化仍只有新增 V87。

## 5. 完整 CI

- GitHub Run：<https://github.com/eiven-xxw/jshPOS/actions/runs/32917121269>；
- 实现候选提交：`bf7a48bcde02ace78d4909a10318be7266bb5934`；
- 分支：`t2/gate9b-sprint27i-g9a-r4-runtime`；
- 结果：`SUCCESS`；
- 作业：Governance Ubuntu、Governance Windows、Server、Web、Flutter Ubuntu、
  Flutter Windows、Android/Database、Formal Runtime、Security，9/9 通过；
- 没有重跑失败 Job、跳过失败测试、自动重跑掩盖 Flaky、降低阈值或创建绿色占位。

## 6. 缺陷与变更边界

- 本批没有新增业务能力或 Requirement ID；
- 经独立 CR 记录并修复了正式装配、同步、证据、恢复、Reporting、Release 夹具及 V87
  约束等已复现缺陷；
- V87 以外没有新增或修改迁移；
- Transfer Java 状态机、聚合版本、API、事件 Schema、库存和成本事实没有因 CR-T2G9R4-014
  的专项修复改变；
- 运行时观察到的 P0/P1 已清零，但 Finding 的治理状态仍等待项目发起人确认。

## 7. Go/No-Go 建议

- G9A-R4：建议 `CONDITIONAL PASS`；
- `G9A-E2E-P1-001`：建议项目发起人确认后更新为 `CLOSED_IN_GATE9B`；
- 完整 Alpha、外部执行、生产发布：继续 `NO-GO`；
- `T2-PAY-002/HWD-001/PRN-001/PAR-001`：继续 `BLOCKED`；
- `T2-UAT-001/REL-001`：继续 `DRAFT`；
- `T2-LIC-001/JSH-001`：继续 `DEFERRED`。

## 8. 证据边界

本报告最高只证明虚构租户、虚构终端、现金与合成外部边界下的
`INTERNAL_FORMAL_RUNTIME_CROSS_OWNER_CANDIDATE`。不代表 SANDBOX、REAL_DEVICE、
REAL_PERIPHERAL、PILOT、FULL_ALPHA、PRODUCTION、COMMERCIAL 或商业 SLA。
