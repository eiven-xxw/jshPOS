# T1 Week 3 周门禁报告

> 文档编号：JSH-POS-T1-W3-001
> 日期：2026-08-16
> 基线：annotated tag `t0-baseline-2026-08-16`
> 实现提交：`045794b182fc15383db06771c3d10082b08e044d`
> Week 3 CI：[T1 Week 3 Internal STATIC FAKE Gates #31922149455](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455)
> Week 2 回归：[T1 Week 2 Internal STATIC FAKE Gates #31922149442](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149442)
> Week 1 回归：[T1 Week 1 STATIC FAKE Gates #31922150040](https://github.com/eiven-xxw/jshPOS/actions/runs/31922150040)
> 结论：`WEEK3 CONDITIONAL PASS / AWAITING SP CONFIRMATION`

## 1. 管理结论

项目发起人批准的 Week 3 内部交叉故障与稳定恢复探针已经完成。GitHub 固定环境中，Week 3 六个 Job、Week 2 六个回归 Job 和 Week 1 六个回归 Job 全部通过，均为第一次运行成功，没有跳过测试、`continue-on-error` 或降低安全阈值。

本周仅修改治理、PoC 契约、隔离合成探针、测试和 CI。没有修改 `server`、`admin-web`、`pos-flutter`、设备生产适配或基础设施；没有创建正式订单、支付、退款、库存、促销、会员或结算表/API/页面；没有支付网络调用、沙箱凭据、真实设备、真实外设、未脱敏商户数据或生产密钥。

本周结论只证明当前内部 `STATIC/FAKE` 模型的交叉故障不变量。相关需求继续保持 `IN_PROGRESS`，7 个外部实证项继续 `BLOCKED`。未经项目发起人确认，不进入 Week 4、T1 退出评审、T2 或正式业务开发。

## 2. RTM 状态

| 状态 | 数量 | 解释 |
|---|---:|---|
| `ACCEPTED` | 2 | T1 治理与范围已确认 |
| `IN_PROGRESS` | 9 | 8 项执行/回归 Week 3；`T1-HWD-001` 只保留 Week 1 Fake 结果 |
| `READY` | 1 | `T1-UAT-001` 留待 T1 退出评审，不代表可进入 T2 |
| `BLOCKED` | 7 | 主认证机、外设、支付沙箱和设计伙伴仍缺真实输入 |
| `DEFERRED` | 2 | 鲸熵汇资料和商业发布前许可证处置 |

## 3. 交付与量化结果

### 3.1 Outbox/Inbox 交叉故障

- 12 个固定 seed，每个 5,000 个事件，共 60,000 个唯一合成事件；
- 组合注入 claim 后未发送、服务端已收但 ACK 丢失、ACK 后本地提交丢失、乱序、重复和批次重启；
- 共 480 次数据库关闭/重开，最终形成 60,012 次重复投递；
- Outbox 剩余积压 0、丢失事件 0、重复业务效果 0、游标未收敛 0；
- 最大积压排空 20 轮；SQLite 使用 `WAL + synchronous=FULL`。

该结果模拟进程故障和持久化重开，不是 Android 实机物理断电证据。

### 3.2 五家支付 Fake 收敛

- 五家已选 Fake Provider，每家 40 例，共 200 例；核心模型不含 Provider 名称分支；
- 覆盖支付 `UNKNOWN` 查询/回调收敛、重复与乱序回调、同 callbackId 异 payload 冲突；
- 覆盖退款 `UNKNOWN` 查询/回调收敛以及远端单边、本地单边、金额差异、状态差异四类合成对账；
- 回调冲突拒绝 25 次，对账差异正确分类 100 项；
- 自动二次扣款 0、网络调用 0、沙箱调用 0、凭据读取 0。

Fake 通过不表示任何支付机构已经签约、接入、联调或可商用。

### 3.3 租户攻击回归

原样复跑 Week 2 的两个虚构租户、7 类入口、28 个攻击向量、每向量 3 次：攻击拒绝 84、跨租户泄漏 0、审计缺失 0、缓存污染 0。该结果仍是隔离边界，不代表正式 RuoYi Mapper/SQL/任务/缓存已经验收。

### 3.4 数据包中断与切换恢复

- 6 个固定 seed，每轮 10,000 条合成记录，共校验 60,000 条；
- 下载分块中断/重启 66 次，续传后摘要全部一致；
- 切换前/后子进程 kill 共 12 次，半版本暴露 0；
- 旧包重放拒绝 6、跨租户包拒绝 6、临时文件残留 0。

包和签名输入均为临时合成数据，不能形成 Android 设备性能或生产签名结论。

### 3.5 升级兼容与前向修复

- 5 个固定 seed × 6 次，共 30 轮；
- 有待同步数据时兼容 App-only 升级通过 30 次，待同步状态下不兼容 Schema 升级安全阻断 30 次；
- 模拟迁移已提交但新 App 健康失败，应用回退 30 次，Schema 逆回滚 0；
- 旧客户端/新 Schema 兼容窗口验证 30 次，窗口外安全阻断 30 次；
- 前向修复重复进入 60 次且只产生一个修复事实，已提交合成事实摘要变化 0。

App/Schema 均为虚构状态，不是 APK 安装、厂商升级接口或实机验收。

### 3.6 Security、PII、依赖和失败 seed

- 对 3 份 Week 3 Schema、5 组夹具、8 份源/测试文件执行范围、Secret、PII、网络依赖、第三方 Python import 和非合成表扫描；
- PII 检测器自测覆盖手机号、身份证号和邮箱形式，仓库输入/证据中检出 0；
- 未修改 Maven、pnpm、Flutter、Gradle 等依赖清单；Trivy HIGH/CRITICAL 漏洞、Secret 和 Workflow 配置门禁通过；
- `observedFailedSeeds=[]`、`fixedFailedSeeds=[]`、`untracked=0`。本轮没有观察到失败 seed，因此如实保留空集合；后续任何失败必须先入台账再修复和固定回归。

## 4. 证据汇总

| Requirement ID | 领域 | 断言 | 迭代 | 结果 |
|---|---|---:|---:|---|
| `T1-OFF-001` | Outbox recovery | 96 | 60,000 | PASS |
| `T1-SYN-001` | Inbox/Outbox convergence | 96 | 120,012 | PASS |
| `T1-PAY-001` | Payment/Refund/Reconciliation Fake | 475 | 200 | PASS |
| `T1-TEN-001` | Tenant attack regression | 338 | 84 | PASS |
| `T1-DPK-001` | Package resume/switch | 60 | 6 | PASS |
| `T1-UPG-001` | Upgrade compatibility/repair | 360 | 30 | PASS |
| 合计 | `FAKE` | **1,425** | **180,332** | PASS |

另有 `T1-SEC-001`、`T1-CI-001` 共 41 项 `STATIC` 断言通过。STATIC/FAKE 证据均明确声明限制，未出现 `SANDBOX_PASS`、`REAL_DEVICE_PASS`、物理断电或商业通过声明。

## 5. GitHub Actions 结果

Week 3 运行时间：2026-08-16 10:33:00—10:34:54（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验范围 |
|---|---:|---:|---|
| [security](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103662702) | 95103662702 | PASS | 依赖差异、Trivy 漏洞/Secret/Workflow HIGH/CRITICAL |
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103662746) | 95103662746 | PASS | T0 tag、RTM、Week 1/2 边界、19 Schema、STATIC/PII 和单测 |
| [sync-convergence](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103687176) | 95103687176 | PASS | 60k 唯一事件、ACK 丢失、480 次重启和积压恢复 |
| [package-upgrade](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103687186) | 95103687186 | PASS | 下载中断、12 次切换 kill、升级兼容和前向修复 |
| [payment-tenant](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103687188) | 95103687188 | PASS | 五家支付 200 例与 84 次租户攻击回归 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149455/job/95103842795) | 95103842795 | PASS | 分域证据合并、等级、失败 seed 和 SHA-256 manifest |

同一提交的 Week 2 [#31922149442](https://github.com/eiven-xxw/jshPOS/actions/runs/31922149442) 与 Week 1 [#31922150040](https://github.com/eiven-xxw/jshPOS/actions/runs/31922150040) 各六个 Job 全部通过。

## 6. 制品与独立复核

| 制品 | Artifact ID | 大小 | GitHub 归档 SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t1-week3-evidence-bundle` | 9256700315 | 4,370 B | `fa7d6db6327494125ae1d9b2f78fe43381afd45c088581eee46d196d67ca43c4` | 2026-09-15 02:34:51 |
| `t1-week3-sync-evidence` | 9256697721 | 1,238 B | `f87a63e49a5a67dd52fb101d701657d1515325140cd2bac1393a146ee9dd9935` | 2026-09-15 02:34:39 |
| `t1-week3-package-upgrade-evidence` | 9256680036 | 1,494 B | `aec33a27a19b6641e01ded43f4368861ccac08d00ada14029c3c21c4a7617c54` | 2026-09-15 02:33:25 |
| `t1-week3-payment-tenant-evidence` | 9256679678 | 1,524 B | `c2c7093a5a40ea68cce2c4fa52d161cb3676009c9462eee6cc9b69c4a1fd302d` | 2026-09-15 02:33:24 |
| `t1-week3-dependency-evidence` | 9256678163 | 355 B | `12e8d1a623caed6ecbd8d37de78c005cbf4e5fe95d1c7a19dac0b301933bf09b` | 2026-09-15 02:33:18 |
| `t1-week3-static-evidence` | 9256675890 | 1,758 B | `b00cb58a1900471b64be448c9b8ce195ebc939f968ce4892385b8a793929acfb` | 2026-09-15 02:33:08 |

独立下载证据包后复算归档摘要，与 GitHub API 的 artifact digest 完全一致。包内：

| 文件 | 大小 | SHA-256 | 等级 |
|---|---:|---|---|
| `evidence-manifest.json` | 671 B | `a11f0e2b8b489965e175e311323a79822976dc42a28cf50484e58e428b1144c9` | manifest |
| `fake-evidence.json` | 4,669 B | `9102aa326f148872a41e70f30ac5cbdf1ce209660619867a3bff271ba4791c2d` | `FAKE` |
| `static-evidence.json` | 4,345 B | `3e6c5b61172cb430342058ebf4d385ecc548ecd016c680bb1ccb61855ab39ded` | `STATIC` |

manifest 中两份文件摘要与解包复算一致，三份文件的 `commitSha` 均为 `045794b182fc15383db06771c3d10082b08e044d`。

## 7. 继续阻断与未解决风险

- `T1-HWD-002`：主认证 Android 厂商、型号、固件、SDK、授权和样机未落实；
- `T1-PRN-001`、`T1-SCN-001`、`T1-SCL-001`、`T1-IO-001`：双打印、扫码、电子秤、钱箱和客显无实机资料；
- `T1-PAY-002`：支付沙箱账号、终端、接口资料和技术联系人未提供；
- `T1-PAR-001`：5 家设计伙伴、至少 3 家试点意愿和数据授权未落实；
- 物理断电、Android 性能、APK 安装/静默升级、真实网络、支付回调/退款/对账均未验证；
- 鲸熵汇继续 `DEFERRED`；正式订单、支付、退款、库存、促销及 T2 继续禁止。

因此，Week 3 可以判定内部风险探针通过，但当前尚不具备 T1 全量退出 `GO` 条件，更不能据此进入商业开发。

## 8. Week 4 建议与下一步指令

依据四周计划，Week 4 只做重复验证、清理和 T1 退出评审材料，不新增业务能力。由于实机、外设、支付沙箱和设计伙伴仍阻断，只能形成“内部 STATIC/FAKE 风险基线是否可接受”的结论，不能把 T1 全部需求改为 `ACCEPTED`。

在收到项目发起人确认前停止 Week 4。建议下一步指令：

```text
我确认《T1 Week 3 周门禁报告》，同意按 CONDITIONAL GO 进入 T1 Week 4 收口与退出评审准备。

继续以 t0-baseline-2026-08-16 为唯一技术基线，不新增正式业务能力，只允许 RTM 已准入的 STATIC/FAKE 重复验证、清理和退出材料：
1. 在 GitHub Ubuntu 与 Windows 两类干净执行器复跑 Week 1—3 全部自动化，核验固定 seed、制品和摘要可重现；
2. 审计并清理临时捷径、未使用夹具、潜在 Secret/PII、网络入口和非合成数据，禁止降低现有门禁；
3. 汇总七类 PoC 的已验证结论、未验证结论、证据等级、P0/P1/P2 风险和 RTM 差距；
4. 生成 T1 证据总清单、制品/SBOM/许可证/安全摘要、失败 seed 总账和可重复运行手册；
5. 更新风险台账、成本与架构建议，形成 T1 退出评审报告和对 T2 的 Go/Conditional Go/No-Go 建议；
6. 实机、外设、支付沙箱和设计伙伴继续 BLOCKED，不创建绿色占位，不将相关需求改为 ACCEPTED；
7. 完成后提交 Week 4 周门禁暨 T1 退出评审准备报告，等待我确认。

继续禁止正式订单、支付、库存、促销业务和 T2；不得用 Fake 替代 SANDBOX、REAL_DEVICE、物理断电、试点或商业验收。未经我确认不得结束 T1 或启动 T2。
```
