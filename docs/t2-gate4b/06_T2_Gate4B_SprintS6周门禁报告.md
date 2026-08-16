# T2 Gate 4B / Sprint S6 周门禁报告

> 文档编号：JSH-POS-T2-G4B-006
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 4B 分支起点：`9557112a4eb573ec4a54ef30be477f1ab8f09d31`
> 技术候选：`72792d2b6073f75f5fda20005b4bf535a997e1f7`
> 技术候选 CI：[T2 Gate 4B Quality Gates #31961246460](https://github.com/eiven-xxw/jshPOS/actions/runs/31961246460)
> Closure 候选：`d7d58cf488d040700cbde77612789bc93317d765`
> Closure CI：[T2 Gate 4B Quality Gates #31961900319](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 4B 获准的盘点快照、复核、差异审批和幂等调整流水，以及供应商、采购订单、收货、原收货退货和幂等库存流水，已经完成设计、正式实现与内部验证。技术候选和包含迁移封印、RTM `VERIFIED` 的 closure 候选，均在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器运行十个 Job；没有降低阈值、跳过测试、自动重跑或绿色占位。

建议将 `T2-INV-003`、`T2-PUR-001` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，但不自行更新为 `ACCEPTED`。本轮建立的是服务端盘点与采购核心，不包含移动加权成本、调拨、促销、支付 Provider 网络、PDA 离线盘点、Android 实机或门店试点，因此不能宣称 Alpha、可试点或可商用。

`T2-CST-001`、`T2-TRF-001` 只完成设计契约，继续 `DRAFT`；`T2-PAY-002` 继续 `BLOCKED`。Provider 网络调用、沙箱、实机和试点证据均为 0。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-INV-003` | `VERIFIED` | 动态快照、盲盘、不可变计数修订、复盘、复核、三人职责分离、审批、零差异关闭和 Owner 调整流水 | PDA 离线盘点、物理断电、真实门店并发盘点、批次效期 |
| `T2-PUR-001` | `VERIFIED` | 供应商、采购单审批、精确单位换算、部分收货、容差超收、拒收、原收货退货和 Owner 库存流水 | 询价/采购申请、无原单退货、应付、发票、真实供应链对账 |
| `T2-CST-001` | `DRAFT` | 成本输入事实、精度、幂等、重建边界与设计 Schema | 无成本账本、移动加权平均、成本重放或财务验收运行时 |
| `T2-TRF-001` | `DRAFT` | 申请、审批、发出、在途、收货和差异状态设计 | 无调拨单、在途库存和跨仓运行时 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B 解阻输入与安全核验清单 | 缺首接 Provider 授权沙箱和全部外部资料 |

既有 Gate 0—4A 的需求状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。

## 3. 架构和数据主权结果

- `jshpos-inventory` 继续独占库存事实、不可变数量流水和余额投影；盘点只保存快照、计数和审批事实，最终调整必须调用库存 Owner，禁止直接更新 `inv_stock_balance` 或修改历史流水。
- 新增独立模块化单体 `jshpos-procurement`，独占供应商、采购订单、收货/退货命令、审计和 Outbox。Controller 仅做协议适配，领域规则没有进入 RuoYi 系统模块或通用工具类。
- 盘点快照冻结截止时的账面数量、策略版本和序号；截止点后的销售/退货不会篡改快照。审批时按最终实盘与快照差额生成一次幂等调整；零差异关闭不生成库存流水。
- 盘点采用计数人、复核人、审批人三人职责分离；计数修订只追加版本，已审批/已入账事实不可修改。重复审批返回原结果，同键异内容拒绝。
- 采购订单本身不改变库存；只有收货确认和退货入账通过库存 Owner 产生不可变流水。收货数量不超过订单数量乘以版本化容差，拒收数量不入库，累计退货不超过原确认收货。
- 采购价只是业务单据快照，不被误当成成本账本结果；移动加权成本继续后置。
- 数量和单位换算统一使用 `DECIMAL(19,6)`/`BigDecimal`，禁止浮点数；所有 Mapper/XML、任务、缓存、导入导出和对象存储命名空间均使用可信 `tenant_id` 和门店/仓库数据范围。
- 核心类与实体具备中文注释；简单持久化使用 MyBatis/MyBatis-Plus 既有能力，复杂锁定和聚合 SQL 保留在 XML，未重复制造通用工具。

## 4. 状态、API、事件、迁移与回退

- 盘点状态冻结为草稿、盘点中、待复盘/复核、待审批、已入账/已关闭及取消边界；非法迁移、越权审批和入账后修改均拒绝。
- 采购冻结供应商状态、采购单草稿/待审批/已批准/部分收货/已完成/关闭，以及收货草稿→确认、原收货退货草稿→待审批→已入账边界。
- OpenAPI 与 Schema 冻结盘点命令、采购单、收货/退货、错误码、稳定幂等键、内容摘要、关联标识和库存 Owner 端口。
- `purchase.receipt.confirmed.v1` 等事实与业务写入同一事务；任何库存 Owner 调用失败会使单据、审计与 Outbox 整体回滚。
- Flyway V13 建立盘点事实，V14 建立采购事实，V15 建立 `9200600` 段权限；均使用复合租户约束和不可变保护。
- V13—V15 已写入 `contracts/t2/gate4b/migration-checksums.json`，SHA-256 分别为 `b0ef3ea091afbbaad3578907b7a97219d931d5366fe0130d9262a4c58458d60c`、`0975b9eaa57b0d13e9e55cdc02ff423ca60ba093c6b31a7eae51eb08b2074f96`、`9cc7c578535f5b3e6234eec74ffb25cfc9083517bc6a908a1c917011d435c12c`；封印后只能新增前向迁移。
- 应用回退只能关闭新入口并回退应用版本；不得删除成功迁移或覆盖盘点、采购、库存事实。迁移失败使用安全前向修复。

## 5. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | Foundation 57 + Catalog 21 + Order 18 + Sync 19 + Payment 49 + Inventory 29 + Procurement 12 = 205 tests；0 failure/error/skipped |
| Inventory 核心覆盖率 | PASS | line 82/87 = 94.25%；branch 80/82 = 97.56%；阈值 90%/85% |
| Procurement 核心覆盖率 | PASS | line 57/59 = 96.61%；branch 47/48 = 97.92%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | V1—V15 完整 Flyway、重复 migrate/validate、复合租户外键、不可变约束和前向修复；1 integration test |
| 固定合成向量 | PASS | 20/20 映射到执行测试；覆盖盘点、采购、幂等、事务和迁移边界 |
| 租户与权限 | PASS | 两个虚构租户、14 个攻击面；越权成功路径 0 |
| Flutter Linux | PASS | 38 tests；同步核心 line 90.94%；Kotlin 与 debug APK 编译通过 |
| Flutter Windows | PASS | 38 tests；独立 Windows 执行器完成 analyze、SQLite 与 HTTP 回归 |
| Web 回归 | PASS | audit/build/lint/typecheck/8 tests/许可证门禁通过 |
| 安全与供应链 | PASS | 服务端/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret、IaC、许可证门禁通过 |

## 6. GitHub Actions

技术候选 #31961246460 与 closure #31961900319 均为 `run_attempt=1`、总结果 `success`。Closure 总时长 10m 21s；下表列示 closure Job。

| Job | Job ID | 时长 | 结果 | 主要证据 |
|---|---:|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007534) | 95201007534 | 8s | PASS | 基线祖先、RTM、ADR、契约、迁移封印和范围差异 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007627) | 95201007627 | 6m 47s | PASS | 205 测试、双模块覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007548) | 95201007548 | 1m 37s | PASS | MySQL 8.4.6、V1—V15、复合租户约束与不可变保护 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007488) | 95201007488 | 49s | PASS | 可信上下文、Mapper/XML 与 14 面租户攻击 |
| [fixed-vectors](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007553) | 95201007553 | 6s | PASS | 20 个盘点/采购/事务/恢复固定向量 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007566) | 95201007566 | 8m 16s | PASS | 38 测试、覆盖率、Kotlin、debug APK、Flutter SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007537) | 95201007537 | 2m 27s | PASS | Windows 38 测试与独立 SQLite/HTTP 回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95201007601) | 95201007601 | 1m 27s | PASS | audit/build/lint/typecheck/测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95202023773) | 95202023773 | 49s | PASS | Trivy、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31961900319/job/95202143550) | 95202143550 | 1m 5s | PASS | 九类证据、183 文件和 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、失败测试跳过、阈值降低或绿色占位。GitHub 产生 14 条非阻断告警：所固定的部分 Action 仍以 Node 20 为目标并被平台强制在 Node 24 运行，以及 `setup-java@v4` 已进入弃用提示；本轮不影响结果，登记为 P2 供应链维护项，后续只能通过评审后的固定 SHA 升级处理。

## 7. 主要制品

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate4b-stocktake-procurement-evidence-bundle` | 9267577286 | 436 MB | `sha256:4bfc2c03133213bf43580fa330c7d6e25b6262982cc6e468dd75947010b575cc` |
| `t2-gate4b-security` | 9267562862 | 218 MB | `sha256:1477c43619637ce05875a8118e9b08202487cc4e5493e86c3ba108154c443aa1` |
| `t2-gate4b-server` | 9267533322 | 147 MB | `sha256:c4090b46ce2ad806bce1dd4f4fa86b386958bea92c374dab44c866583b60d5d3` |
| `t2-gate4b-pos-linux` | 9267551297 | 71.4 MB | `sha256:dd89b2fd0ed092c76ac5c60d8b4d5f1eb860e1261e0b612adf7ff7f7d1878f8a` |
| `t2-gate4b-pos-windows` | 9267477566 | 2.99 KB | `sha256:deebb3972626a29fd161d9f035cd2bb6b22d23b5921ecaa44f5943b900e8bd5c` |
| `t2-gate4b-tenant` | 9267458101 | 26.2 KB | `sha256:4c896223aaea35ea6376780bfa3b04851acc6cfeb1fb64c7751671fa12bdde37` |
| `t2-gate4b-mysql` | 9267467618 | 5.84 KB | `sha256:d1b98658f28b57c12fa4161a7630ecb4c845c3af2607dceb226fbb249d664f53` |
| `t2-gate4b-web` | 9267465463 | 77.9 KB | `sha256:2f5739e0d3bb93b9828b9d674121353d64fc20d5fc2a5d607a2e3a670214db86` |
| `t2-gate4b-vectors` | 9267449302 | 1.46 KB | `sha256:bc2a64d899f7c38d1cba4308fc641f63299442db96b018a45cece3d9fe0c7557` |
| `t2-gate4b-governance` | 9267449658 | 1.02 KB | `sha256:4a9bab3be641c20d110118e24d62263223002d17afe21bc7381c225bd95836bb` |

证据聚合器核验九类上游制品，生成 SHA-256 索引；最终摘要：

```text
T2-GATE4B EVIDENCE OK: stage=closure files=183 serverTests=205 vectors=20 paymentNetwork=0
```

## 8. 风险和不可宣称

- 本阶段没有失败 CI，也没有使用单 Job 重跑或自动 retry。
- P0：`T2-PAY-002` 缺首接 Provider、授权沙箱、测试终端、官方文档、网络和联系人，保持 `BLOCKED`。
- P0：主认证 Android 实机、打印/扫码/电子秤/钱箱/客显和物理断电证据缺失，`REAL_DEVICE=0`。
- P1：移动加权成本与基础调拨只有设计契约，没有正式运行时；门店尚不能据此完成完整成本与跨仓闭环。
- P1：盘点和采购证据来自虚构租户、合成数据与 CI MySQL，不是容量、长稳、实机或商业验收。
- 外部证据继续为 `sandbox=0`、`realDevice=0`、`pilot=0`；Fake/合成数据未解除任何阻断。

## 9. 退出建议

建议项目发起人接受 Gate 4B `CONDITIONAL PASS`，并在明确确认后将 `T2-INV-003`、`T2-PUR-001` 从 `VERIFIED` 更新为 `ACCEPTED`。随后继续双轨：Gate 3B 等待真实支付资料；内部 Gate 4C / Sprint S7 仅正式准入 `T2-CST-001` 移动加权平均成本，`T2-TRF-001` 继续契约和测试准备，避免成本与调拨一次铺开。

项目发起人确认本报告前，不得把两项需求改为 `ACCEPTED`，不得启动 Provider 网络、成本、调拨、促销或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
