# T2 Gate 4A / Sprint S5 周门禁报告

> 文档编号：JSH-POS-T2-G4A-006
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 4A 分支起点：`451a48f982d3a88c68ff20ca283a190e7bf53ccf`
> 技术候选：`b0a1603a3f78deeb593b41fe73acdb926fd0f370`
> 技术候选 CI：[T2 Gate 4A Quality Gates #31957398048](https://github.com/eiven-xxw/jshPOS/actions/runs/31957398048)
> Closure 候选：`4f49eaf0d9e068fb10f65602128ed7e4431b88b7`
> Closure CI：[T2 Gate 4A Quality Gates #31958122232](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 4A 获准的不可变库存流水与可重算余额、销售完成/成功原单退货幂等出入库、在手/可用/预占/负库存版本化策略三项需求，已经完成设计、正式实现和内部验证。技术候选与包含迁移封印、RTM `VERIFIED`、周门禁报告的 closure 候选，均在 GitHub Ubuntu、Windows、MySQL 8.4.6 干净执行器运行十个 Job 并全部一次通过；closure 证据聚合器核验 161 个文件并生成 SHA-256 索引。

建议将 `T2-INV-001`、`T2-INV-002`、`T2-INV-004` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，但不自行更新为 `ACCEPTED`。本轮建立的是服务端正式库存数量账本内核，不包含盘点、采购、成本、调拨、促销，也未获得 Android 实机断电、门店试点或商业运营证据，因此不能宣称 Alpha、可试点或可商用。

Gate 3B 仅完成解阻准备，`T2-PAY-002` 继续 `BLOCKED`；Provider 网络调用、沙箱证据和真实资金均为 0。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-INV-001` | `VERIFIED` | 只追加流水、维度顺序号、来源幂等、同事件异 hash 拒绝、余额从流水聚合重建 | 生产容量、实机断电、门店历史库存迁移 |
| `T2-INV-002` | `VERIFIED` | 权威订单/退款只读快照、销售出库、成功原单退货入库、多行同事务、崩溃回滚 | 第三方渠道订单、跨服务消息时延、真实退货流程 |
| `T2-INV-004` | `VERIFIED` | `on_hand/available/reserved/frozen/safety_stock` 口径、`DENY`、`ALLOW_AND_ALERT`、不可变策略版本 | 外部订单预占、越权放行、三类业态运营参数校准 |
| `T2-INV-003` | `DRAFT` | 盘点契约与测试准备 | 无盘点运行时、审批、调整流水 |
| `T2-PUR-001` | `DRAFT` | 采购收货到库存的 Owner 边界 | 无供应商、采购单、收货/退货运行时 |
| `T2-CST-001` | `DRAFT` | 成本事件引用库存流水的边界 | 无移动加权平均成本运行时 |
| `T2-TRF-001` | `DRAFT` | 发出/在途/收货双命令边界 | 无调拨运行时 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B 解阻输入与安全核验清单 | 缺首接 Provider 授权沙箱和全部外部资料 |

既有 Gate 0—3A 的 28 项需求保持 `ACCEPTED`；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。

## 3. 架构和数据主权结果

- 新增模块化单体 `jshpos-inventory`，独占 `inv_*` 命令、策略、流水、余额、异常、审计和 Outbox 写入；Controller 只做协议适配，领域规则未进入 RuoYi 系统模块或通用工具类。
- `jshpos-order` 和 `jshpos-payment` 分别以只读端口提供已完成/已支付订单和已成功退款的权威快照。客户端只提交来源 ID、事件 ID、仓库和关联标识，不能自报 `tenant_id`、SKU、单位、数量或资金状态。
- 一次来源命令在同一数据库事务内写入命令结果、全部流水、余额投影、异常、审计和 Outbox；任一行失败导致全部回滚，多行按 SKU 和订单行稳定排序加锁。
- 数量统一使用 `DECIMAL(19,6)`/`BigDecimal`，禁止浮点数；金额和成本未进入本 Gate。普通 POS 现货销售的 `reserved=0`，外部订单预占留待后续准入。
- 库存流水由 MySQL 触发器禁止更新/删除；余额是可丢弃投影，管理员只能从流水 `SUM(delta)` 与最大顺序号受控重建，并保留前后值审计。
- 负库存策略是不可变发布版本。便利店、零食折扣店和社区超市初始模板均使用 `DENY`；受控 `ALLOW_AND_ALERT` 会保存真实负数、异常事实、审计和告警事件，不会静默截断为 0。
- 所有 Mapper/XML、原生锁查询和数据库复合外键显式包含可信 `tenant_id`，并覆盖 HTTP、任务、缓存、导入导出和对象存储命名空间攻击面。

## 4. API、事件、迁移与回退

- OpenAPI 冻结销售出库、退款入库、策略发布、余额/流水查询和余额重建边界，错误码区分幂等冲突、来源状态、负库存、并发版本、策略缺失和租户拒绝。
- `inventory.stock.changed.v1` 包含事件 ID、Schema 版本、仓库、SKU、变动类型、精确数量、变动后数量、策略版本和关联标识；事实与 Outbox 同事务提交。
- Flyway V11 建立策略、命令、余额、不可变流水、异常、审计和 Outbox 七张库存表及复合租户约束；V12 建立 `9200500–9200505` 权限号段。
- V11/V12 已写入 `contracts/t2/gate4a/migration-checksums.json`；封印后禁止修改，失败修复只能新增前向迁移。
- 应用回退只允许停止消费者/入口并回退应用版本；禁止删除已成功迁移表或覆盖库存流水。兼容窗口内旧应用忽略新表，新应用只消费已建立完整策略的仓库。

## 5. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | Foundation 57 + Catalog 21 + Order 18 + Sync 19 + Payment 49 + Inventory 18 = 182 tests；0 failure/error/skipped |
| Inventory 核心覆盖率 | PASS | line 42/44 = 95.45%；branch 34/34 = 100%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | V1—V12 完整 Flyway、重复 migrate/validate、租户复合外键、余额方程、唯一来源及不可变触发器；1 integration test |
| 固定合成向量 | PASS | 16/16 映射到执行测试；包含多行原子、幂等冲突、负库存、重建、退款上限和迁移回退 |
| 租户与权限 | PASS | 两个虚构租户、12 个攻击面；越权成功路径 0 |
| Flutter Linux | PASS | 38 tests；同步核心 line 90.94%；Kotlin 与 debug APK 编译通过 |
| Flutter Windows | PASS | 38 tests；独立 Windows 干净执行器完成 analyze、SQLite 与 HTTP 回归 |
| Web 回归 | PASS | audit/build/lint/typecheck/8 tests/许可证门禁通过 |
| 安全与供应链 | PASS | 服务端/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret、IaC、许可证门禁通过 |
| 证据聚合 | PASS | 161 文件；九类上游证据完整并生成 SHA-256 索引 |

证据摘要：

```text
T2-GATE4A EVIDENCE OK: stage=closure files=161 serverTests=182 flutterLinux=38 flutterWindows=38 serverBranch=1.0000 flutterLine=0.9094
```

## 6. GitHub Actions

技术候选 #31957398048 与 closure #31958122232 均为 `run_attempt=1`、总结果 `success`。Closure 运行时间为 2026-08-17 00:16:23—00:25:21（Asia/Shanghai）；下表列示 closure Job。

| Job | Job ID | 结果 | 主要证据 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832127) | 95191832127 | PASS | 基线祖先、RTM、ADR、契约、范围和依赖差异 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832123) | 95191832123 | PASS | 182 测试、覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832054) | 95191832054 | PASS | MySQL 8.4.6、V1—V12、复合外键和不可变约束 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832105) | 95191832105 | PASS | 可信上下文、Mapper 和 12 面租户攻击 |
| [inventory-vectors](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832116) | 95191832116 | PASS | 16 个固定库存策略/事务/恢复向量 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832201) | 95191832201 | PASS | 38 测试、覆盖率、Kotlin、APK、Flutter SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832191) | 95191832191 | PASS | Windows 38 测试与独立 SQLite/HTTP 回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95191832120) | 95191832120 | PASS | audit/build/lint/typecheck/测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95192698952) | 95192698952 | PASS | Trivy、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31958122232/job/95192781933) | 95192781933 | PASS | 九类证据和 161 文件 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、失败测试跳过、阈值降低或绿色占位。

## 7. 主要制品

| Artifact | ID | 大小（B） | GitHub digest |
|---|---:|---:|---|
| `t2-gate4a-immutable-inventory-evidence-bundle` | 9266608639 | 456478111 | `sha256:8321ea34c59ac48283c2231d25035b25314be0b5fd6c2b63fda58cff5c7c12c2` |
| `t2-gate4a-security` | 9266596177 | 228221693 | `sha256:d2fc1e9b6e5eefd64e34d2e416f0a03066bd4d3cc8eefd0be35de9930b3fbd7e` |
| `t2-gate4a-server` | 9266587131 | 153310804 | `sha256:670c9cd8b86e534afd35c9ff37bd1fa4315675ab73c8a77da2c43cf0bebb1d86` |
| `t2-gate4a-pos-linux` | 9266580231 | 74828580 | `sha256:5cc09336afd5824f38f68739b2d186f6212b1e0c88bd576b8629f53c3fba4aac` |
| `t2-gate4a-pos-windows` | 9266534512 | 3067 | `sha256:76c718fcdf515689a979aa5bdd374440411ba5df36c0249baa4aba6d13ebfc75` |
| `t2-gate4a-tenant` | 9266506737 | 16449 | `sha256:17abfd7edc2650788592198d7b3da27c1530b0bd91d71691747a8165b3626b18` |
| `t2-gate4a-mysql` | 9266514501 | 5906 | `sha256:55cd6a299b5e3152f1b089d14d9eb5d1918638e30f86dba1b4c9369e7273986c` |
| `t2-gate4a-web` | 9266516310 | 79795 | `sha256:58dfe214c48ad08c4debf7bb15159a61c362e6548fd9b5f87ac004abea0aeaba` |
| `t2-gate4a-vectors` | 9266498988 | 1436 | `sha256:b8b8e85452144f51e9e36a7b0640b1fec97a387938b8dbd6dcac0742d64fd677` |
| `t2-gate4a-governance` | 9266499292 | 1014 | `sha256:7a77a2d8bd9c5fd1de6f66a78ef20fbb48c5747bd93c0e9f8d58e9184491f8d1` |

## 8. 问题、风险和不可宣称

- 本阶段没有失败 CI，也没有使用单 Job 重跑或自动 retry；本地多行原子向量映射在提交前补强为真实双行用例，避免以单行测试代替多行声明。
- P0：`T2-PAY-002` 缺首接 Provider、授权沙箱、测试终端、官方文档、网络和联系人，保持 `BLOCKED`。
- P0：主认证 Android 实机、打印/扫码/电子秤/钱箱/客显和物理断电证据缺失，`REAL_DEVICE=0`。
- P1：盘点、采购、成本和调拨只有设计契约，没有正式运行时；门店不能据此完成完整进销存闭环。
- P1：库存证据使用虚构租户、合成订单/退款和 CI MySQL，不是容量、长稳、实机或商业验收。
- 外部证据继续为 `sandbox=0`、`realDevice=0`、`pilot=0`；Fake/合成数据没有解除任何阻断。

## 9. 退出建议

建议项目发起人接受 Gate 4A `CONDITIONAL PASS`，并在明确确认后将三项需求从 `VERIFIED` 更新为 `ACCEPTED`。随后推荐采用双轨：Gate 3B 继续等待真实支付资料；内部 Gate 4B / Sprint S6 仅准入盘点与采购收货核心，成本和调拨继续准备，避免一次铺开。

项目发起人确认本报告前，不得把三项需求改为 `ACCEPTED`，不得启动 Provider 网络、盘点、采购、成本、调拨、促销或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
