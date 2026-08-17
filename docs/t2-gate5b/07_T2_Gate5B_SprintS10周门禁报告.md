# T2 Gate 5B / Sprint S10 周门禁报告

> 文档编号：JSH-POS-T2-G5B-007  
> 日期：2026-08-17  
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`  
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`  
> Gate 5B 分支起点：`e947a229782865f7759525cfa3e2e90819ebfba5`  
> 顺序实现提交：`5aabccc` → `ff2f2af` → `9e54235`  
> CI 准入提交：`68f3262a6dc1640f075a12d465cc8f3c17580134`  
> 全绿实现候选：`097498a287d9131ac3f6fcc68a7923f700597365`  
> 全绿 CI：[T2 Gate 5B Sale Return Quality Gates #32014486873](https://github.com/eiven-xxw/jshPOS/actions/runs/32014486873)  
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 5B 获准的三项需求已严格按 `T2-POS-006 → T2-ORD-003 → T2-REF-002` 完成独立设计准入、实现、提交和总门禁验证，没有一次铺开后倒补设计或证据。实现候选在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器完成 10 个 Job，全部为 `success`。

本轮已建立：POS 促销报价、人工优惠审批引用、成交促销快照、现金收款、订单事实、班次现金效果和 Outbox 的 SQLite 本地原子结算；Order Owner 对 Promotion Owner 不可变快照的可信消费与金额不变量；原单退货退款按原成交快照恢复，并与现金/Provider 无关退款、库存入库、审计及 Inbox/Outbox 检查点最终收敛。

建议将三项需求继续保持 `VERIFIED`，提交 Gate 5B `CONDITIONAL PASS`。只有项目发起人明确确认后，才能更新为 `ACCEPTED`。

最高证据等级为 `STATIC + UNIT + MYSQL_INTEGRATION + SQLITE_INTEGRATION + CROSS_RUNTIME_VECTOR`。外部证据仍为 `sandbox=0`、`realDevice=0`、`pilot=0`，Provider 网络调用为 0。因此本结论不代表 Alpha、实机验收、试点就绪或可商用。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-POS-006` | `VERIFIED` | SQLite V6 单事务冻结购物篮、规则包、报价 fingerprint、人工审批、快照、订单/行、现金、班次与 Outbox；失败全回滚，原幂等键恢复 | Android 实机、物理断电、真实钱箱/打印及长稳 |
| `T2-ORD-003` | `VERIFIED` | `order.submitted.v2` 正式消费；快照身份/租户/门店/终端/业务日/订单/摘要/金额核验；头行守恒与不可变绑定 | 生产历史数据迁移、真实门店并发与财务对账 |
| `T2-REF-002` | `VERIFIED` | 累计可退数量/金额上限、最后一次合法余数、原优惠恢复、现金或 Provider 无关退款、库存入库、审计及检查点恢复 | 真实 Provider 退款、实机断电、试点及商业验收 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B-Prep 真实资料清单 | 缺授权沙箱、测试终端、正式接口、签名/回调/退款/账单和技术联系人 |
| `T2-MEM-001..002` | `DRAFT` | 领域、隐私/权限、API/事件和合成用例输入 | 未准入会员运行时，禁止真实个人信息 |
| `T2-RPT-001..002` | `DRAFT` | 权威 Owner 事实到可重建查询投影的设计输入 | 未准入报表运行时；支付外部账单仍依赖 `T2-PAY-002` |

既有 Gate 0—5A 状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。Fake 和合成证据没有解除任何外部阻断。

## 3. 顺序准入和跨 Owner 边界

| 顺序 | 需求 | 独立提交 | 主要 Owner 边界 | 结果 |
|---:|---|---|---|---|
| 1 | `T2-POS-006` | `5aabccc` | POS 只写本地结算事实与 Outbox，不修改服务端 Owner 表 | `VERIFIED_POS006` 后才准入 ORD-003 |
| 2 | `T2-ORD-003` | `ff2f2af` | Order Owner 只读 Promotion 快照端口，不复制促销算法、不写促销表 | `VERIFIED_ORD003` 后才准入 REF-002 |
| 3 | `T2-REF-002` | `9e54235` | Return Owner 编排检查点；Promotion、Order/Refund、Inventory、Payment 只写各自事实 | `VERIFIED_REF002`，等待发起人确认 |

跨 Owner 交互只通过明确端口、版本化事件和 Inbox/Outbox；没有跨模块 Mapper 直接更新其他 Owner 表。`tenant_id` 只来自可信设备或服务端上下文，已覆盖本地库、HTTP、Inbox、任务、缓存、导入导出和对象存储攻击面。

## 4. 交易、金额与恢复不变量

- POS 结算所有规定事实在同一 SQLite 事务中提交；任一写入失败整体回滚。重启只使用原幂等键恢复，不重新生成业务命令或按新规则重算。
- 订单统一满足 `gross - discount + surcharge = receivable`，行汇总与订单头一致；金额使用最小货币单位整数，数量不使用浮点数。
- 促销快照按身份、内容摘要和订单绑定不可变；Order Owner 不推导、复制或重算促销结果。
- 退货退款只读原订单和原快照，按累计已退数量/金额扣减上限，最后一次退款吸收合法余数。
- 同幂等键同内容返回原结果，同键异内容拒绝。重复、乱序、ACK 丢失、进程终止、服务端已收客户端未知和部分 Owner 失败通过稳定命令与检查点收敛；`UNKNOWN` 禁止以新命令重试。
- MySQL V24—V27 与 SQLite V6 尚未发布/未接受的候选迁移已根据干净 MySQL 结果修正并重算摘要；本次门禁确认后封印，后续只允许新的前向迁移。

## 5. CI 发现的真实失败与修复

| Run | 结果 | 真实问题 | 处理 |
|---|---|---|---|
| [#32012879249](https://github.com/eiven-xxw/jshPOS/actions/runs/32012879249) | `failure` | MySQL 8.4 禁止删除仍被 `fk_cash_ledger_payment` 依赖的旧唯一索引 | 显式重建外键支撑索引，新增销售收款生成列唯一键，保留原不变量并允许多次部分退款 |
| [#32013960268](https://github.com/eiven-xxw/jshPOS/actions/runs/32013960268) | `cancelled`（MySQL Job 已红） | MySQL 8.4 不允许同一 `ALTER` 中以相同名称删除并重建外键 | 在新支撑索引就绪后以独立 `ALTER` 恢复外键；新推送依并发策略取消旧 Run |
| [#32014203212](https://github.com/eiven-xxw/jshPOS/actions/runs/32014203212) | `cancelled`（MySQL 迁移已成功） | 19 个实际迁移文件已到达 V27，但测试错将最高版本号 27 当成文件数 | 同时强制断言 19 个文件全执行、最高版本为 V202608170027、再次 migrate 为 0 且 validate 通过 |
| [#32014486873](https://github.com/eiven-xxw/jshPOS/actions/runs/32014486873) | `success` | 无 | 10 个 Job 完整单次运行全绿，未局部重跑或自动重试 |

三个红色/取消 Run 和日志全部保留。没有跳过失败测试、降低安全或覆盖率阈值、自动重跑掩盖 Flaky，也没有建立绿色占位。

## 6. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | 43 模块；证据聚合 350 tests，0 failure/error/skipped |
| Returns 核心覆盖率 | PASS | line 47/49 = 95.92%；branch 35/36 = 97.22%；达到既定阈值 |
| Flutter POS | PASS | Linux/Windows 各 67 tests；analyze 通过；Gate 5B 1112/1174 = 94.72%；Gate 5A 回归 824/888 = 92.79% |
| Android 构建 | PASS | Linux 干净 Runner 完成 Kotlin 编译和 debug APK；APK 162,603,076 B，SHA-256 `e9c0e8a2fcd1cf697f770698ea05555cf858ca8d003d0273be8b1d22d3708ec9` |
| MySQL 8.4.6 | PASS | 19 个实际迁移文件全部执行并到达 V202608170027；重复 migrate=0；validate、外键、唯一投影、不可变触发器和权限通过 |
| SQLite 与交易故障 | PASS | V1→V6、15 项 Gate 5B POS 故障/原子性用例；重复、中断、磁盘失败、重启和同键异内容覆盖 |
| 跨端固定向量 | PASS | 6 组场景，含 1 组订单、4 组退款及完整性结果；Java/Dart 金额与摘要一致 |
| 租户、权限与攻击 | PASS | 2 个虚构租户、多店/多终端；26 个攻击面；越权成功路径 0 |
| Web | PASS | audit/build/lint/typecheck 及 8 tests 通过；依赖许可证清单生成 |
| 安全与供应链 | PASS | Secret、IaC、HIGH/CRITICAL 漏洞、服务端/Flutter SBOM 和许可证门禁通过 |
| 总证据 | PASS | 142 个证据文件，逐文件 SHA-256；Provider 网络调用 0 |

## 7. GitHub Actions Job 和制品

全绿运行 `#32014486873` 为 `run_attempt=1`，提交 `097498a287d9131ac3f6fcc68a7923f700597365`，结果 `success`，耗时约 8 分 15 秒。

| Job | Job ID | 结果 | 主要证据 |
|---|---:|---|---|
| governance | 95340996989 | PASS | 基线祖先、顺序准入、RTM、ADR、范围、迁移摘要和 Provider 网络静态门禁 |
| server | 95340996947 | PASS | 350 tests、覆盖率、Admin JAR、Returns JAR、聚合 SBOM |
| mysql-migration | 95340996960 | PASS | 干净 MySQL 8.4.6，19 文件到 V27、结构与不可变约束 |
| tenant-security | 95340997018 | PASS | 22 tests 及 26 面可信租户/Owner/事务攻击 |
| cross-runtime-vectors | 95340997004 | PASS | 6 组 Java/Dart 订单与退款固定向量 |
| pos-linux | 95340996969 | PASS | Flutter、SQLite V6、覆盖率、Kotlin、debug APK、Flutter SBOM/许可证 |
| pos-windows | 95340997117 | PASS | Windows 干净执行器 Flutter 67 tests |
| admin-web | 95340996998 | PASS | audit/build/lint/typecheck/8 tests/许可证 |
| security-sbom-license | 95342754363 | PASS | Trivy 漏洞、Secret、IaC、双 SBOM 和许可证策略 |
| evidence | 95342914859 | PASS | 九类上游证据、142 文件摘要和最终索引 |

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate5b-evidence-index` | 9283219140 | 9,769 B | `sha256:b619abb8405504423aa7135209af10b6360ccd659d3de2ad1bf1f242cd770a30` |
| `t2-gate5b-security` | 9283203110 | 86,962 B | `sha256:c12d276117f4696ddf6bb10069acd760162fdf7496b44c79aeb10d5fe993dcdc` |
| `t2-gate5b-server` | 9283186218 | 154,320,221 B | `sha256:431202b5cf1be5d2bf30fae83a825ab8eb7e61f440554e1058bf2e6af278d0cd` |
| `t2-gate5b-pos-linux` | 9283132586 | 74,834,961 B | `sha256:7efe16b405492cc970daadddcc77a82f2a56e43f1c529d200a6ecaa00aca2ec5` |
| `t2-gate5b-pos-windows` | 9283056901 | 4,895 B | `sha256:6e2ff86c48c45161932bb2ca536a6df397cbd1a213e140d3236801922a3d9c0f` |
| `t2-gate5b-mysql` | 9283020070 | 6,082 B | `sha256:55159a92ef5e0fb7c4c339b337e9836f4a964d25c00d580902079e84b6cd99dd` |
| `t2-gate5b-web` | 9283014891 | 79,794 B | `sha256:bba50bc6e03d0a002c106c56d7e21de0b06ac105df6a11ea4bd844ce0e0db846` |
| `t2-gate5b-tenant` | 9283001838 | 32,057 B | `sha256:a1d9a2ce1c77ba8c4fffb060c698f38e2f6e064eaa348de4db31cfcffe02c103` |
| `t2-gate5b-vectors` | 9282981707 | 827 B | `sha256:ca33c0f24f730885c917301035c94378cd6be43baaf806d890e507f7ededb695` |
| `t2-gate5b-governance` | 9282981237 | 1,204 B | `sha256:a44a7d0a6718434820b5af2c616014450fb784f09d0191606bfa0bd2060bf409` |

当前 10 个制品合计 229,376,772 B，每个上游制品仅保留一份，最终 Job 只上传轻量证据索引，没有重复打包全量制品。

## 8. 证据归档和 Artifact 治理

- 全绿证据索引已下载至 `C:\Users\Administrator\.codex\archives\jshPOS-actions-20260817-gate5b\32014486873_9283219140_t2-gate5b-evidence-index.zip`，大小 9,769 B，本地 SHA-256 与 GitHub digest 一致：`b619abb8405504423aa7135209af10b6360ccd659d3de2ad1bf1f242cd770a30`。
- Gate 5A 封板证据在清理前已归档并验证：`32004410578_9279703033_t2-gate5a-promotion-evidence-bundle.zip`，229,364,805 B，SHA-256 `b10cf09bfc154b9912f0b4363e3aacfae223b62385df70ab85ec9d3a28dd276f`。
- 按项目发起人已授权的精确范围，删除两个已结束失败/取消 Run 的 13 个非封板 Artifact，释放 229,479,523 B。只删除 Artifact，未删除 Workflow Run、日志、Git 提交、tag、报告或失败证据。

## 9. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 继续 `BLOCKED`；本轮没有 Provider SDK/HTTP、真实回调、账单下载、生产密钥或真实资金，Provider 网络调用为 0。
- P0：主认证 Android 实机、打印/扫码/称/钱箱/客显、物理断电和多日长稳未验证；CI 的 Kotlin/APK 通过不是 `REAL_DEVICE`。
- P0：设计伙伴和真实试点未解阻，三类业态仅使用合成数据；`PILOT=0`。
- P1：现有结果为合成流量、故障和 CI 干净环境证据，未覆盖生产历史数据、真实终端长稳、门店运营和财务审计。
- P1：会员、积分、基础报表和支付报表仍为 `DRAFT`；优惠券、储值、预算抢占、应付、发票、总账、批次成本和复杂 WMS 均未实现。
- 外部证据持续为 `sandbox=0`、`realDevice=0`、`pilot=0`；Fake 和合成结果不得解除任何阻断。

## 10. 退出建议

建议项目发起人接受 Gate 5B `CONDITIONAL PASS`，并在明确确认后将 `T2-POS-006`、`T2-ORD-003`、`T2-REF-002` 从 `VERIFIED` 更新为 `ACCEPTED`。

下一内部阶段建议为 Gate 5C / Sprint S11，严格按 `T2-MEM-001 → T2-MEM-002` 建立会员最小档案/身份/隐私边界，再建立等级与不可变积分权益账本。`T2-RPT-001/002` 仅允许继续契约和查询投影准备，报表运行时不得与会员一次铺开。

本报告与 RTM 封存提交后必须再运行完整 Gate 5B CI；若封存复跑不是全绿，本报告自动失效。项目发起人确认前，不得将三项需求改为 `ACCEPTED`，不得启动 Gate 5C、报表运行时、支付 Provider 网络或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
