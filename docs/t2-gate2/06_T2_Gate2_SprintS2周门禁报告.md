# T2 Gate 2 / Sprint S2 周门禁报告

> 文档编号：JSH-POS-T2-G2-S2-006  
> 日期：2026-08-16  
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`  
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`  
> Gate 0 封板提交：`cf2ef29bd74c5d0f8fa5845a689305ebb56c7ef2`  
> Gate 1 封板起点：`6a94bc6af2938fba6b9a1af123eb94b6312af9b2`  
> 最终实现候选：`921306d99bbb97cc265b24d14298f6b79ab3741d`  
> GitHub Actions：[T2 Gate 2 Sprint S2 Quality Gates #31942899817](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817)  
> 当前结论：`GATE2 CONDITIONAL PASS / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 2 获准的八项班次、购物篮、现金、挂取单、订单快照、幂等 Outbox 与正式 POS SQLite 本地交易需求已完成实现和分级验证，RTM 当前均为 `VERIFIED`。同一候选 SHA 在 GitHub Ubuntu、Windows 和 MySQL 8.4.6 干净环境执行九个 Job，最终全部通过；证据聚合器复核 111 个文件并生成 SHA-256 索引。

本轮形成的是“本地现金交易闭环”的内部工程证据：购物篮、订单快照、现金收款、现金流水、打印任务、Outbox、审计和幂等记录按规定事务原子提交，进程在提交前终止时全量回滚、提交后终止时事实持久。它不包含正式远程同步、第三方支付、退款、库存、促销、真实硬件、物理断电或试点证据。

因此本报告建议 `CONDITIONAL PASS`，但不自行把需求改为 `ACCEPTED`。`T2-SYN-001` 继续 `DRAFT`；`T2-HWD-001`、`T2-PAY-002`、`T2-PAR-001` 继续 `BLOCKED`；`T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`。系统仍不得宣称 Alpha、可试点或可商用。

## 2. 需求状态与验证边界

| Requirement ID | 状态 | 本轮已验证 | 未包含或保留边界 |
|---|---|---|---|
| `T2-POS-001` | `VERIFIED` | 可信租户/门店/终端/收银员开班、期初现金、业务日、重复命令 | 实机终端登录与厂商 SDK |
| `T2-POS-002` | `VERIFIED` | 精确数量、最小货币单位整数、确定性购物篮、异常回滚 | 真实扫码、秤码和促销计算 |
| `T2-POS-003` | `VERIFIED` | 现金实收/找零守恒、班次归属、审计与同事务流水 | 电子支付、撤销和退款 |
| `T2-POS-004` | `VERIFIED` | 挂单/取单幂等、版本事件、租户/门店/终端/收银员/班次/业务日冻结 | 跨店取单与云端取单 |
| `T2-POS-005` | `VERIFIED` | 理论现金重算、实有/差异、独立主管审批、审批金额与版本绑定 | 钱箱盘点实机与财务商业验收 |
| `T2-ORD-001` | `VERIFIED` | 已提交订单及行快照不可变、金额行和=订单总额、成交上下文冻结 | 税与促销本轮固定为 0；售后未准入 |
| `T2-ORD-002` | `VERIFIED` | 同键同摘要返回原结果、异摘要拒绝、正式 Outbox 与订单事实原子提交 | Outbox 发送、服务端 Inbox 和远程 ACK |
| `T2-OFF-001` | `VERIFIED` | 新设计 `local_*` Schema、迁移封印、WAL/FULL、SQLite FULL、kill/reopen、quick_check | Android 物理断电与弱机文件系统行为 |

T1 的 `syn_*` Fake 表没有转成生产表；Gate 2 正式 SQLite 以独立 `local_*` 数据主权、约束和迁移序列重新设计。`T2-SYN-001` 本轮只冻结 Inbox/Outbox、游标、冲突和恢复契约，网络运行时计数保持 0。

## 3. 架构与实现结果

- 服务端新增模块化单体 `jshpos-order`，Controller 只做协议适配，订单、班次与现金规则留在领域/应用层；没有修改 RuoYi 系统模块承载业务逻辑。
- Flyway `V202608160005/006` 建立班次、独立差异审批、不可变订单快照、现金支付、现金流水、状态历史、打印任务、Outbox、幂等、审计与权限；迁移 SHA-256 进入封印账本。
- Flutter POS 使用正式 `local_*` SQLite 模型，可信设备会话冻结租户、门店、终端和收银员；完成开班、精确购物篮、挂取单、现金成交、审批与交班本地应用服务。
- 购物篮、订单、现金、流水、打印、Outbox、审计和幂等在单一事务中形成全有或全无结果；已成交快照禁止修改。
- 金额统一使用最小货币单位整数；数量使用六位小数精度的十进制文本/`BigDecimal`，禁止浮点数；Java 与 Flutter 使用同一命令摘要黄金向量。
- 64 位平台 ID 在 API 边界序列化为十进制字符串；`tenant_id` 只来自可信上下文，并覆盖 Mapper、原生 SQL、任务、缓存、导出、对象存储和本地设备绑定攻击面。
- 正式远程同步传输、支付 Provider、退款、库存、采购、成本和促销代码均为 0。

跨运行时命令摘要黄金值：

```text
60337986451e5a511783f4d77eaac27598fef47f997336a4bbb599c25fd68e5a
```

## 4. 量化测试与质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | Foundation 57 + Catalog 21 + Order 18 = 96 tests；0 failure/error/skipped |
| Order 核心覆盖率 | PASS | line 100/108 = 92.59%；branch 59/60 = 98.33%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | Gate 0—2 六版 Flyway、重复 migrate/validate、复合租户外键、现金不变量及不可变触发器；1 integration test |
| Flutter/SQLite Linux | PASS | 24 tests；核心 line 728/776 = 93.81%；format/analyze 无错误 |
| Flutter/SQLite Windows | PASS | 24 tests；独立干净执行器完成 analyze、SQLite 和进程终止回归 |
| 崩溃恢复 | PASS | `killBeforeCommit=ROLLED_BACK`、`killAfterCommit=DURABLE`、`quickCheck=ok`，Linux/Windows 各一次 |
| 租户与权限 | PASS | 2 个虚构租户、8 项定向 Java 测试、9 个攻击面；越权成功数 0 |
| 契约 | PASS | 43 个 JSON Schema 与 T0/T2 OpenAPI；Java/Flutter 状态、错误码、幂等键和摘要一致 |
| Web 回归 | PASS | 8 tests；audit/build/lint/typecheck/许可证通过 |
| 安全与供应链 | PASS | 服务端/Flutter 双 SBOM；HIGH/CRITICAL 漏洞、Secret、IaC 为 0；14 个受限许可证组件已精确复核，无未批准 GPL/AGPL |
| RTM 与范围 | PASS | 106 条 RTM；Gate 0/1 `ACCEPTED`、Gate 2 八项 `VERIFIED`、Sync `DRAFT`、网络实现 0、外部证据 0 |

证据聚合最终摘要：

```text
T2-GATE2 EVIDENCE OK: files=111 serverTests=96 mysqlTests=1 flutterLinux=24 flutterWindows=24 serverBranch=0.9833 flutterLine=0.9381
```

## 5. 关键故障注入与不变量结论

- 重复提交：同一命令/幂等键/摘要不产生第二订单、第二现金效果或第二 Outbox；相同键不同摘要被拒绝。
- 磁盘写入失败：`SQLITE_FULL` 注入使订单、现金、流水、打印、Outbox、审计和幂等均为 0 个部分事实。
- 进程终止：提交前强杀后回滚，提交后强杀并重新打开数据库后事实、Outbox 与 quick_check 均正常。
- 金额篡改：订单总额、行金额、应收、实收、找零、现金流水及摘要不一致均被应用层和数据库约束拒绝。
- 业务日切换：班次业务日冻结；挂取单必须保持同租户、门店、终端、收银员、班次和业务日，不允许跨班次完成。
- 租户攻击：API payload 不接受 tenant_id；服务端 Mapper 和 SQLite 设备绑定均 fail-closed，跨租户 ID、任务、缓存、导出和对象命名空间攻击无成功路径。
- 主管审批：审批人与收银员必须不同，且审批精确绑定理论/实有/差异金额及班次版本，旧审批不可重放。

## 6. Android 与本地 POS 制品

GitHub Ubuntu 使用 Flutter `3.47.0`、Java 21 完成 Kotlin 边界和 debug APK 编译：

```text
app-debug.apk size = 162603100 bytes
SHA-256 = 434731c7238bf7317f4079493d88fff419adc43a2e14d33b6a35c634a047feec
```

该 APK 只证明可编译和可形成制品，不代表签名 release、性能优化、厂商 ROM 兼容、真实扫码/打印/秤/钱箱/客显、物理断电或 `REAL_DEVICE` 验收。

## 7. GitHub Actions 与制品

运行时间：2026-08-16 18:54:23—19:02:39（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验内容 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527236) | 95154527236 | PASS | 基线祖先、RTM、ADR、契约、迁移封印和禁入边界 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527240) | 95154527240 | PASS | 完整 Admin reactor、96 测试、覆盖率、JAR 与聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527243) | 95154527243 | PASS | 固定 MySQL 8.4.6、六版迁移、validate 和数据库不变量 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527275) | 95154527275 | PASS | 双租户九面攻击、可信操作者、审批和 Mapper guard |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527295) | 95154527295 | PASS | Flutter/SQLite、覆盖率、kill/reopen、Kotlin、APK、SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527259) | 95154527259 | PASS | Windows 干净执行器、24 测试和独立进程终止夹具 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95154527338) | 95154527338 | PASS | audit、build、lint、typecheck、8 项回归和许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95155227406) | 95155227406 | PASS | Trivy 0.72.0、双 SBOM、漏洞、Secret、IaC 和许可证策略 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31942899817/job/95155298026) | 95155298026 | PASS | 上游制品、测试、覆盖率、APK、RTM、证据等级和 111 文件 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、阈值降低、失败测试跳过或绿色占位。

| Artifact | ID | 大小（B） | GitHub archive SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t2-gate2-sprint-s2-evidence-bundle` | 9262583659 | 455680338 | `2bc0157aaded4d097e1a4c0c47b371c0fd5916dd04714a0ae9ef9e547d04207a` | 2026-09-15 11:02:14 |
| `t2-gate2-security` | 9262574898 | 227823714 | `4a57d753cbf8e11b346b184f8c1322df8c5d3d331f598a431761dda63e5116b6` | 2026-09-15 11:01:36 |
| `t2-gate2-pos-linux` | 9262567095 | 74826731 | `c225ce7d9dc836f913ca1c9e953f0008fe725dbd675475d27efa957b21b9ce62` | 2026-09-15 11:01:00 |
| `t2-gate2-server` | 9262565858 | 152917871 | `6edaef7defd6a4f8679a0c14cdd611e03f2ffea18995034cc33c685b8182ffde` | 2026-09-15 11:00:51 |
| `t2-gate2-pos-windows` | 9262524779 | 2415 | `9c4010e22da741315c2a46064f215c034b107e7e753af02e67ea91432442cbb1` | 2026-09-15 10:57:17 |
| `t2-gate2-mysql` | 9262506139 | 5683 | `cf29156bf8848b55dca94bea635d93330915d03a580a476b43ff6639d9b1cb7f` | 2026-09-15 10:55:35 |
| `t2-gate2-web` | 9262505688 | 79789 | `dddc695786e2bfb9ca5da4681baae259e3c9a1972de59884fbc9db1b2ea55821` | 2026-09-15 10:55:33 |
| `t2-gate2-tenant` | 9262499055 | 16050 | `03562db2759e6d4507c54b3ce7691777a51b85906e1b22a483d9a56ed2c081b5` | 2026-09-15 10:54:57 |
| `t2-gate2-governance` | 9262494614 | 1040 | `4099d36b02fdb03c98064bb6ac4cfcb485a9889347ba21e22401b54e86f4c313` | 2026-09-15 10:54:32 |

## 8. 失败记录与修复

### 8.1 MySQL 租户复合外键

首轮运行 [#31942057526](https://github.com/eiven-xxw/jshPOS/actions/runs/31942057526) 在 MySQL 8.4 创建 Gate 2 复合外键时失败。根因是新表 `tenant_id` 使用 `ascii_bin`，而 Gate 0/1 主权表使用 `utf8mb4_0900_ai_ci`，MySQL 判定引用列不兼容。修复提交 `92a25e6` 让 Gate 2 租户列继承既有主权表字符集，并更新迁移 SHA-256 封印；没有删除外键或放宽 SQL 模式。后续两次独立 MySQL 容器均通过。

### 8.2 Flutter machine 证据解析

第二轮运行 [#31942426693](https://github.com/eiven-xxw/jshPOS/actions/runs/31942426693) 的八个生产与安全前置 Job 全部通过，但 evidence Job 将 Flutter machine 输出中的合法 VM-service JSON 数组误当成 package:test 对象，触发解析异常。修复提交 `921306d` 只统计协议对象并忽略非测试扩展数组，先用该轮 Linux/Windows 原始制品各 24 项测试回归，再启动完整九 Job 新运行。失败轮未计为通过，也未单独重跑最后一步冒充全绿。

## 9. 风险、阻断与不可宣称

- P0 外部阻断：`T2-PAY-002` 缺授权沙箱商户、终端、接口文档、回调网络和技术联系人；任何 Fake、现金或本地状态机均不能解除。
- P0 外部阻断：`T2-HWD-001` 缺主认证 Android 型号、SDK/固件、样机和外设；APK 编译及桌面进程 kill 不替代 `REAL_DEVICE` 或物理断电。
- P1 外部阻断：`T2-PAR-001` 缺 5 家目标伙伴及至少 3 家试点意愿、数据授权和旧系统对账条件。
- P1 范围保留：`T2-SYN-001` 仍为 `DRAFT`，本地 Outbox 尚未发送，ACK 丢失、游标恢复和服务端 Inbox 仅有冻结契约，无正式网络实现。
- P2 工程项：当前为 162.6 MB debug APK，不是 release 体积/启动/弱机性能指标；在 S6 前由 POS Owner 完成 release 构建、混淆拆分和认证机性能基准。
- P2 工具链项：GitHub 日志包含 pinned Action 的 Node 20 兼容警告及 Flutter SDK 内部 Gradle `ApkVariant` 弃用警告；由 DevOps/POS Owner 在工具链升级窗口验证后升级，禁止为消警而跳过固定版本复现。
- `T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`，但新增依赖仍须即时许可证审查。

Gate 2 已准入范围内没有开放 P0/P1 产品缺陷；上述外部阻断和后续范围不能被解释为已验收。外部证据计数保持 `sandbox=0`、`realDevice=0`、`pilot=0`。

## 10. 退出建议与下一步边界

建议项目发起人接受 `GATE2 CONDITIONAL PASS`，并在明确确认后把八项 Gate 2 需求由 `VERIFIED` 更新为 `ACCEPTED`。

下一 Sprint 应按既定路线进入“S3 同步收口与 Gate 3 准备”：优先逐项准入 `T2-SYN-001` 的正式远程 Inbox/Outbox、ACK/游标/冲突/恢复；支付沙箱仍未解阻时，Gate 3 只允许 Provider 无关状态机、端口、契约和合成测试准备，`T2-PAY-002` 保持 `BLOCKED`，不得实现真实网络 Provider，也不得把 `T2-PAY-003`、`T2-REF-001` 或 `T2-REC-001` 宣称通过。

项目发起人确认本报告前，不得更新 Gate 2 为 `ACCEPTED`，不得启动正式远程同步、第三方支付、退款、库存、采购、成本、促销或后续 Gate 编码。
