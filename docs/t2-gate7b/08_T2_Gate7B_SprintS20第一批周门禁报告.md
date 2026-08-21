# T2 Gate 7B / Sprint S20 第一批周门禁报告

## 1. 决策建议

`CONDITIONAL PASS / AWAITING SPONSOR CONFIRMATION`。

`T2-POS-010 → T2-POS-011 → T2-ORD-004` 已严格串行完成设计准入、正式实现和
独立 `VERIFIED`。建议项目发起人逐项复核后决定是否更新为 `ACCEPTED`。
本报告不授权第二批、Gate 7C—7E、外部执行、完整 Alpha 或生产发布。

## 2. 串行准入与提交链

| Requirement | 设计/实现候选 | 独立 CI | 封存闭环 | 当前状态 |
| --- | --- | --- | --- | --- |
| `T2-POS-010` | `ca0770bced7f868e539326862ee752a569cd9272` | Run `32487652575`，6 Job PASS | `12ca1183f10b6886333b6a2d466655be48a6aebe` / Run `32488566595` PASS | `VERIFIED` |
| `T2-POS-011` | `0159a34` 首轮失败并保留；修复候选 `f19406dbb8c8a31818969766cbb6f548320d9f22` | Run `32494347095`，6 Job PASS | `94a315ee2aa0304e7a02dfb20182eef0e0281e7a` / Run `32495308502` PASS | `VERIFIED` |
| `T2-ORD-004` | 设计 `f9fa5e0`；实现 `c81595821f01651e0f07f87b8678aad5b9c9797b` | Run `32499856217`，7 类门禁 PASS | 本报告封存提交须复跑闭环 CI | `VERIFIED` |

前项独立验证和封存闭环均通过后才准入后项。没有并行铺开、补写设计或删除
失败历史。POS-011 首轮失败源于全迁移测试期望版本未同步到已成功执行的 V53；
修复仅更新测试期望，没有修改已发布迁移、跳过测试、降低阈值或重跑失败 Job。

## 3. 第一批交付范围

- 班次现金存入、取出、安全投库与非销售钱箱请求使用具名、只追加、受权、可审计事实；真实钱箱执行仍失败关闭。
- 成交时冻结语义收据和原始打印任务；预览、补打原因、授权、次数与审计可重放；真实打印执行仍失败关闭。
- 未完成交易可取消或作废；成交事实不可回退，只能进入原单退货退款或显式补偿路由。
- SQLite V9—V11 与 MySQL V52—V54 均为前向迁移；本地原子事务、云端 Inbox/Outbox、Owner 边界、租户隔离和只追加数据库保护已经验证。
- Flutter POS 使用正式应用端口，不直接拼装领域事实；服务端 Controller/Sync 不跨 Owner Mapper 修改事实。

## 4. 质量门禁与量化结果

最终候选 Run `32499856217` 的治理、服务端、MySQL、Web、Flutter Ubuntu、
Flutter Windows 和安全七类门禁均成功，制品详见《T2-ORD-004 独立验证报告》。

- Flutter 全量：158 项通过；Gate 2 核心作用域 91.43%，Gate 6D POS 作用域 90.69%，均不低于 90%。
- Android/Kotlin：`:app:compileDebugKotlin` 与 debug APK 构建成功；仅为软件构建证据，不等于实机认证。
- MySQL 8.4：空库完整迁移至 V54、Flyway 校验、数据库约束与重复执行通过；SQLite 迁移至 V11 并覆盖中断恢复。
- 服务端：模块化单体全量 `clean verify`、JaCoCo、SBOM 和许可证策略通过。
- 供应链：Web 审计/许可证、Server/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret 和工作流配置扫描通过。
- 缺陷账：P0 = 0，P1 = 0；没有 Flaky 自动重跑、跳过失败用例、降低安全或覆盖率阈值。

## 5. 状态与证据边界

| 项目 | 状态/数值 |
| --- | --- |
| `T2-POS-010` / `T2-POS-011` / `T2-ORD-004` | `VERIFIED / VERIFIED / VERIFIED`，尚未 `ACCEPTED` |
| `T2-EXG-001` / `T2-PAY-004` / Gate 7C—7E | `DRAFT` |
| `T2-PAY-002` / `T2-HWD-001` / `T2-PRN-001` / `T2-PAR-001` | `BLOCKED` |
| `T2-UAT-001` / `T2-REL-001` | `DRAFT` |
| `T2-JSH-001` / `T2-LIC-001` | `DEFERRED` |
| Provider 网络 / 真实资金 / 真实设备命令 / 真实外设命令 | `0 / 0 / 0 / 0` |
| 伙伴联系 / 现场试点 / 完整 Alpha / 生产部署 | `0 / 0 / 0 / 0` |

软件预览、Fake Device Adapter、Android 构建和合成数据均不得替代
`SANDBOX`、`REAL_DEVICE`、`PILOT`、`FULL_ALPHA` 或商业验收证据。

## 6. Go/No-Go

- 第一批内部软件范围：`CONDITIONAL PASS`，等待项目发起人确认。
- 第二批或 Gate 7C：`NO-GO`，未获得启动授权。
- 外部支付、真实硬件/打印、伙伴、完整 Alpha、生产与商业声明：`NO-GO`。
- 若本报告封存提交的完整闭环 CI 失败，本报告自动退回 `IN_PROGRESS/NO-GO`，必须修复并重新完整执行，不得以局部重跑替代。
