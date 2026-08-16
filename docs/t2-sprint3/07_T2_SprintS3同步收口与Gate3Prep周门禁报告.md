# T2 Sprint S3 同步收口与 Gate 3-Prep 周门禁报告

> 文档编号：JSH-POS-T2-S3-007
> 日期：2026-08-16
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 2 封板起点：`968ae7be34ab144c970e5c92fb7ffbddf60bf5e1`
> 技术实现候选：`9a00bebced8ea9f3705b7d92f9440ff2b3534fd4`
> 技术候选 CI：[T2 Sprint S3 Quality Gates #31946989640](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640)
> 当前结论：`CLOSURE CANDIDATE / VERIFIED / AWAITING CLOSURE CI`

## 1. 管理结论

`T2-SYN-001` 正式 POS Inbox/Outbox 远程同步、逐事件 ACK、结果查询、不透明单调游标、冲突、重试、死信和恢复已完成实现。技术候选在同一 SHA 的 GitHub Ubuntu、Windows 和 MySQL 8.4.6 干净执行器上完成十个 Job，全部通过；证据聚合器验证 127 个文件并建立 SHA-256 索引。

本轮验证的是两个虚构租户、多门店/多终端和合成交易事实的正式同步工程基线。它没有使用支付 Provider、支付沙箱、生产密钥、真实资金、真实商户数据、实机、物理断电或设计伙伴试点。因此建议把 `T2-SYN-001` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，但不自行更新为 `ACCEPTED`。

Gate 3 的 `T2-PAY-001`、`T2-PAY-003`、`T2-REF-001`、`T2-REC-001` 仍为 `DRAFT`；`T2-PAY-002` 仍为 `BLOCKED`。支付运行时、Provider 网络调用、退款业务运行时和真实资金调用均为 0。系统不得宣称 Alpha、可试点或可商用。

## 2. 需求状态与证据边界

| Requirement ID | 状态 | 本轮已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-SYN-001` | `VERIFIED` | POS SQLite V2 Outbox/Inbox、服务端 Inbox/同步事实、稳定幂等键、逐事件结果、ACK、游标、冲突、重试预算、死信、人工修复、积压告警 | Android 物理断电、弱网实机、真实大规模生产流量、领域 Owner 云端投影商业验收 |
| `T2-PAY-001` | `DRAFT` | 状态机与边界设计 | 无正式支付领域实现 |
| `T2-PAY-003` | `DRAFT` | 查询/回调合并契约设计 | 无 Provider 回调或网络调用 |
| `T2-REF-001` | `DRAFT` | 原单、金额/数量上限设计 | 无退款运行时 |
| `T2-REC-001` | `DRAFT` | 支付/退款/账单差异模型设计 | 无真实或沙箱账单 |
| `T2-PAY-002` | `BLOCKED` | 解阻输入清单 | 缺授权沙箱、测试终端、正式文档和联系人 |

`T2-HWD-001`、`T2-PAR-001` 继续 `BLOCKED`；`T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`。外部证据计数保持 `sandbox=0`、`realDevice=0`、`pilot=0`。

## 3. 架构与实现结果

- 服务端新增模块化单体 `jshpos-sync`，Controller 只做协议适配；同步规则、可信设备上下文、Inbox 接收、事实处理、失败记录、游标和修复分层实现，没有把领域逻辑写进 RuoYi 系统模块。
- POS 新增正式 SQLite V2：既有 Gate 2 订单事实与 Outbox Schema 不重写，只增补租约、下次重试、ACK 证据，以及 `local_inbox`、`local_sync_cursor`、`local_sync_dead_letter`、`local_sync_alert`、`local_sync_control`。
- Outbox 使用原 event_id、payload hash、设备序列和关联 ID 重试；单批最多 100 事件、2 MiB，单事件最多 256 KiB；超限、篡改和最终拒绝进入可审计死信。
- 服务端在 `REQUIRES_NEW` 事务先持久 Inbox，再以独立事务形成唯一同步事实；ACK 丢失后同 ID 同 hash 返回原结果/重复，不生成第二业务命令。
- 服务端游标按租户、设备、流隔离；只有已 ACK 页 token 可作为续拉起点，ACK 证据须与签发页完全一致，应用层和 SQL UPSERT 双层阻止回退。
- POS 下行 Inbox、受控投影和 next cursor 在一个 SQLite 事务提交；提交前崩溃不推进游标，提交后 ACK 丢失只重发 ACK。
- `tenant_id` 不出现在客户端请求参数/Header 中，只由服务端可信主体与设备注册表交集注入；覆盖本地库、HTTP、Mapper、原生 SQL、任务、缓存、导出和对象存储 12 个攻击面。
- 同步层只保留不可变传输事实，不越权推导订单/班次领域当前版本；领域 GAP 和云端业务投影必须由后续获准的领域 Owner 应用端口处理。

## 4. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | Foundation 57 + Catalog 21 + Order 18 + Sync 19 = 115 tests；0 failure/error/skipped |
| Sync 核心覆盖率 | PASS | line 60/66 = 90.91%；branch 33/34 = 97.06%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | Gate 0—S3 八版 Flyway、重复 migrate/validate、复合租户外键、游标/事实/安全事件不变量；1 integration test |
| Flutter Linux | PASS | 38 tests；S3 line 512/563 = 90.94%；analyze 0 问题；真实 loopback HTTP |
| Flutter Windows | PASS | 38 tests；独立 Windows 干净执行器完成 analyze、SQLite、HTTP 与故障回归 |
| 同步故障 | PASS | 14 个固定 seed 全部映射到执行测试；重复、乱序、ACK 丢失、超时、重启、游标损坏、kill、积压和兼容窗口 |
| 租户攻击 | PASS | 2 个虚构租户、12 个攻击面、越权成功路径 0 |
| Web 回归 | PASS | 8 tests；audit/build/lint/typecheck/许可证通过 |
| 安全与供应链 | PASS | 服务端/Flutter 双 SBOM；HIGH/CRITICAL 漏洞、Secret、IaC 阻断通过；14 个受限许可证组件精确复核 |
| 证据聚合 | PASS | 127 文件；服务端/MySQL/租户/故障/Linux/Windows/Web/安全九类输入完整 |

证据聚合摘要：

```text
T2-SPRINT3 EVIDENCE OK: stage=admitted files=127 serverTests=115 flutterLinux=38 flutterWindows=38 serverBranch=0.9706 flutterLine=0.9094
```

## 5. 故障与恢复结论

- 服务端已持久但 ACK 丢失：POS 保留并重发原事件；服务端按 event_id/hash 返回重复或原结果，业务效果唯一。
- 进程重启与租约过期：`SENDING` 使用原事件回到 `RETRY`，不会生成新订单或幂等键。
- 重复与乱序：服务端逐事件返回结果，不因一个事件阻断而静默丢弃其他事件；同效果唯一键拒绝第二事实。
- 客户端 UNKNOWN：`ACCEPTED_PENDING` 只能使用原 event_id 查询，禁止重建业务命令。
- 游标/页摘要损坏：POS 在应用前拒收并保持旧游标；服务端拒绝跨流、未 ACK 和回退 token。
- 下行应用被 kill：Inbox、受控投影、游标全量回滚；恢复后重取同页并收敛，历史死信标为 `RESOLVED`。
- 自动重试预算耗尽：事件进入死信并触发人工修复流程，原始事实和证据保留，不通过删除“恢复”。
- 跨租户/设备攻击：客户端不能声明 tenant；错误设备、门店、终端或用户绑定 fail-closed；同事件异 hash 阻断设备并形成不可变安全事件。

## 6. Android 与制品边界

GitHub Ubuntu 使用 Flutter 3.47.0、Java 21 完成 Kotlin 边界与 debug APK：

```text
app-debug.apk size = 162603100 bytes
SHA-256 = 8d16d031c043be6022f841f74628d35b240a76e0c7a91feaac10efb2dc17010d
```

该 APK 只证明可编译和形成受控制品，不代表签名 release、主认证机性能、厂商 ROM、扫码/打印/秤/钱箱/客显、真实弱网或物理断电验收。

## 7. GitHub Actions

技术候选运行：2026-08-16 20:24:54—20:35:07（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 主要证据 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631730) | 95164631730 | PASS | 基线祖先、RTM、契约、范围和依赖差异 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631680) | 95164631680 | PASS | 115 测试、覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631660) | 95164631660 | PASS | 固定 MySQL 8.4.6、八版迁移和数据库约束 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631724) | 95164631724 | PASS | 可信设备/用户、Mapper 与 12 面租户攻击 |
| [sync-fault](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631673) | 95164631673 | PASS | 14 个固定故障 seed 总账 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631700) | 95164631700 | PASS | 38 测试、覆盖率、Kotlin、APK、Flutter SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631746) | 95164631746 | PASS | Windows 38 测试与独立 HTTP/SQLite 回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95164631682) | 95164631682 | PASS | audit/build/lint/typecheck/8 测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95165429840) | 95165429840 | PASS | Trivy 0.72.0、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31946989640/job/95165525875) | 95165525875 | PASS | 九类上游证据和 127 文件 SHA-256 索引 |

Workflow 无 `continue-on-error`，没有自动 retry、阈值降低、失败测试跳过或绿色占位。

## 8. 主要制品

| Artifact | ID | 大小（B） | GitHub digest |
|---|---:|---:|---|
| `t2-sprint3-sync-gate3-prep-evidence-bundle` | 9263690316 | 455894994 | `sha256:3dd397474c585199dfa95db35ccb89a68cf401605dfcca73f5aa0a2c10611903` |
| `t2-sprint3-security` | 9263679216 | 227930061 | `sha256:63ffd618fdf731273a63682ad9b698677dcba1b44b5c03ff44bf201061a96baa` |
| `t2-sprint3-server` | 9263668866 | 153021385 | `sha256:6e8527c0e51a8a38b27b528394f6f87137df1196f62191810c51bacd8130c4d9` |
| `t2-sprint3-pos-linux` | 9263656597 | 74828575 | `sha256:82c41a032980802b5f2635351ed7fa4768928787feced86119b109730abeb0e7` |
| `t2-sprint3-pos-windows` | 9263626715 | 3071 | `sha256:18d6c53b3374da542841853262f2064b14477cd04ef8a8e0e758ed6ca97daad3` |
| `t2-sprint3-mysql` | 9263599034 | 5745 | `sha256:8e9c565f109412a4e18213c150405db763ce0acd8b26dd26ebcf7399794e463d` |
| `t2-sprint3-tenant` | 9263591596 | 16173 | `sha256:8c5739849ad1a8c1431fc228e9d924b60a428430aa8066ec5d004c6f405cce94` |
| `t2-sprint3-web` | 9263600825 | 79791 | `sha256:bc653ccdd0c8fb57215a4934471547b0af95efc6082d30272e1f66f2cbf72ae3` |
| `t2-sprint3-governance` | 9263582928 | 1015 | `sha256:88876dd712be567f0c968a7d6065d02179d5d2a088b4f2dc2d8a13e3cf5ec4b6` |
| `t2-sprint3-fault` | 9263582134 | 1448 | `sha256:5320f1a5d73bf0130e75ad209b10f6b88fc8f5bf4861e7029ebc7c79afdb1ddc` |

## 9. 发现问题与修复记录

- Dart 静态分析发现两个批次异常返回分支缺少 `acked: 0` 字段；补全返回记录后分析 0 问题。
- 首轮 Flutter S3 覆盖率为 79.04%，未降低 90% 阈值；补充全部 HTTP 方法、线协议模型、HTTP 503、篡改和恢复测试后达到 90.94%。
- 收口审阅发现未 ACK token 可被恶意客户端当作续拉起点，以及首次 ACK 并发可能在 UPSERT 层覆盖新游标；增加 ACKED-token 校验、SQL 单调更新和回归测试。
- 设计稿的云端 Inbox/GAP 命名与本 Sprint 实现边界不一致；已按实际状态冻结，并明确领域 GAP/云端业务投影仍归领域 Owner，Sync Mapper 不越权实现。

上述问题均通过新提交和完整门禁处理，没有用重跑、跳测、降阈值或删除约束掩盖。

## 10. 风险、阻断与不可宣称

- P0：支付沙箱缺授权商户、终端、正式接口/回调/账单资料和技术联系人，`T2-PAY-002` 保持 `BLOCKED`。
- P0：主认证 Android 实机、外设 SDK、物理断电和弱网长稳证据缺失，`REAL_DEVICE=0`。
- P1：设计伙伴与试点授权缺失，`PILOT=0`。
- P1：同步层已验证不可变传输事实，但云端订单/班次领域投影及商业对账不在 S3 准入范围内，不能据此宣称端到端商业闭环。
- P2：debug APK 仍约 162.6 MB；release 签名、拆分、混淆和弱机性能在设备认证阶段处理。
- `T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`；新增依赖仍受 SBOM 和许可证门禁约束。

## 11. Gate 3-Prep 结果

Provider 无关支付、查询/回调合并、原单退款和对账模型已完成设计准备，但均未准入运行时。完整解阻清单、两阶段准入策略和可复制的下一步指令见《Gate 3 正式支付开发解阻清单与下一步指令》。

建议下一阶段先对 Gate 3A Provider 无关核心逐项准入；`T2-PAY-002` 必须等授权沙箱资料齐备并经独立评审后才能进入 Gate 3B。禁止同时铺开五家 Provider；先用同一认证套件完成一家沙箱闭环，再复制到后续适配器。

## 12. 退出建议

本提交将封印 Sprint S3 两版 Flyway 和 SQLite V2，并以 `T2-SYN-001=VERIFIED` 运行 closure 质量门禁。closure 全绿后，建议项目发起人接受 `T2-SYN-001 CONDITIONAL PASS`，再以明确指令将其更新为 `ACCEPTED`。

项目发起人确认前，不得把 `T2-SYN-001` 改为 `ACCEPTED`，不得启动 Gate 3 正式支付编码或 Provider 网络调用，不得启动退款、库存、采购、成本、促销或后续 Gate，不得宣称 Alpha、可试点或可商用。
