# T1 Week 2 周门禁报告

> 文档编号：JSH-POS-T1-W2-001
> 日期：2026-08-16
> 基线：annotated tag `t0-baseline-2026-08-16`
> 实现提交：`bab6bed456f9206ddce2201eaadb98951fd06db9`
> Week 2 CI：[T1 Week 2 Internal STATIC FAKE Gates #31921090603](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603)
> Week 1 回归：[T1 Week 1 STATIC FAKE Gates #31921090607](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090607)
> 结论：`WEEK2 CONDITIONAL PASS / AWAITING SP CONFIRMATION`

## 1. 管理结论

项目发起人批准的 Week 2 内部风险探针已完成并在 GitHub 固定环境一次通过。执行范围严格限定为 SQLite 合成事务、Inbox 幂等与游标恢复、两个虚构租户攻击、临时 10k/100k 合成数据包、虚构 App/Schema 升级以及五家支付 Fake 回归。

本周没有修改 `server`、`admin-web`、`pos-flutter`、设备生产适配代码或任何正式业务模块；没有创建正式订单、支付、退款、库存、促销、会员或结算表/API/页面；没有网络支付调用、沙箱凭据、真实设备、真实外设、未脱敏商户数据或生产密钥。

本周 `PASS` 只说明内部 `STATIC/FAKE` 风险不变量在当前合成模型中成立。相关需求继续保持 `IN_PROGRESS`，7 个外部项继续 `BLOCKED`。未经项目发起人确认，不进入 Week 3 或 T2。

## 2. RTM 状态

| 状态 | 数量 | 本周解释 |
|---|---:|---|
| `ACCEPTED` | 2 | T1 治理与范围已确认 |
| `IN_PROGRESS` | 9 | 其中 8 项执行 Week 2；`T1-HWD-001` 只保留 Week 1 结果 |
| `READY` | 1 | `T1-UAT-001` 留待 T1 退出评审 |
| `BLOCKED` | 7 | 主认证机、双打印、扫码、秤、钱箱客显、支付沙箱、设计伙伴均未解阻 |
| `DEFERRED` | 2 | 鲸熵汇和商业发布前许可证处置 |

## 3. 交付内容

### 3.1 SQLite 原子事务

- 使用 `syn_fact`、`syn_intent`、`syn_outbox` 三个可删除合成表；
- `WAL + synchronous=FULL + foreign_keys=ON`；
- 子进程在事务前、fact 后、intent 后、outbox 后、commit 前和 commit 后调用 `os._exit`；
- 每个故障点 20 次，共 120 次；恢复结果只允许 0/0/0 或 1/1/1；
- 未出现半事务或 integrity failure。

该结果是进程崩溃 Fake，不是 Android 实机物理断电结论。

### 3.2 Inbox 幂等与游标恢复

- 10,000 个合成事件 × 20 个固定 seed，共 200,000 个唯一事件；
- 含随机重复、乱序、ACK 丢失重投和同 eventId 异 payload 后共 400,790 次投递；
- 500 个异 payload 冲突全部识别；
- Inbox/effect/cursor 事务三个崩溃点各 20 次，共 60 次恢复；
- 丢失事件 0、重复效果 0、游标回退 0。

### 3.3 虚构租户攻击

覆盖 API、Mapper、原生 SQL、后台任务、缓存、导出和对象存储 7 类入口、28 个攻击向量，每向量 3 次，共 84 次：

- 拒绝 84；
- 审计缺失 0；
- 跨租户内容/存在性泄漏 0；
- 缓存污染 0；
- 错误响应统一为不泄露存在性的 `NOT_FOUND`。

这些是隔离合成边界，不代表 RuoYi 正式 Mapper/SQL 模块已经验收。

### 3.4 五家支付 Fake 回归

- 候选档案仍为 10 家，Fake 仍为银联商务、通联、拉卡拉、易宝、汇付；
- 每家 30 类 × 2 个变体，共 60 个用例；五家共 300 个；
- 覆盖成功、失败、PROCESSING、UNKNOWN、查询收敛、回调先到/重复/乱序、错误签名/重放、幂等同参/异参、退款、对账差异、限流、熔断和 `UNSUPPORTED`；
- Provider 核心名称分支 0，网络调用 0，沙箱调用 0。

五家 Fake 通过不表示五家机构已经接入、签约、联调或可商用。

### 3.5 10k/100k 合成数据包

- 10k 全量验证 10 次；100k 全量验证 5 次；
- 20 个增量包顺序验签和切换；
- 截断、坏摘要、坏测试 MAC、跨租户、未知 Schema、旧包重放和版本缺口共拒绝 210 次；
- 切换提交前故障 20 次，半版本暴露 0；
- 断点续传后摘要一致；
- 包内容和临时文件仅在隔离临时目录生成。

GitHub Runner 趋势：100k 单次校验最大 0.062 秒、Python 峰值内存 3.639 MiB、临时文件/100k 源文件比 2.151。该数据明确标记为 `FAKE_CI_TREND_ONLY_NOT_ANDROID_CERTIFICATION`，不能用于主认证机性能承诺。

测试签名采用公开固定 `HMAC_SHA256_FAKE_TEST_VECTOR_ONLY`，只是故障向量，不是生产签名方案。

### 3.6 虚构升级与安全回退

- A/N → B/N+1 正常升级 30 次；
- 截断、坏摘要、坏测试 MAC、空间不足预检安全阻断 80 次；
- 迁移 step1 kill、step2 kill、SQL 失败共 60 次，均可重入并前向恢复；
- 新 App 健康检查失败后应用回退 20 次，Schema 不反向回滚；
- 旧 App 面对不兼容新 Schema 安全阻断 20 次；
- 已提交合成事实摘要变化 0。

升级包是不可安装的合成字节，不是 APK、厂商静默安装或实机认证。

## 4. 量化结果

| Requirement ID | 领域 | 断言 | 核心迭代 | 结果 |
|---|---|---:|---:|---|
| `T1-OFF-001` | SQLite atomic | 240 | 120 | PASS |
| `T1-SYN-001` | Inbox/sync | 480 | 200,060 | PASS |
| `T1-TEN-001` | Tenant isolation | 338 | 84 | PASS |
| `T1-PAY-001` | Payment Fake | 303 | 300 | PASS |
| `T1-DPK-001` | Data package | 558 | 265 | PASS |
| `T1-UPG-001` | Upgrade/rollback | 960 | 210 | PASS |
| 合计 | `FAKE` | 2,879 | 201,039 | PASS |

另有 `T1-SEC-001`、`T1-CI-001` 对 22 个 Week 2 契约、夹具和源文件输入执行 `STATIC` 边界检查，均为 PASS。

## 5. GitHub Actions 结果

Week 2 运行时间：2026-08-16 10:05:58—10:06:59（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验范围 |
|---|---:|---:|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95100967275) | 95100967275 | PASS | T0 tag、RTM、Week 1 回归边界、16 Schema、Week 2 准入和单元模型 |
| [security](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95100967180) | 95100967180 | PASS | Trivy 0.72.0 校验、Secret 与 Workflow HIGH/CRITICAL 门禁 |
| [sqlite-inbox](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95100991952) | 95100991952 | PASS | 120 次 SQLite kill、200k 唯一事件和 60 次游标崩溃恢复 |
| [tenant-payment](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95100991953) | 95100991953 | PASS | Week 1 回归、84 次租户攻击、300 个支付 Fake |
| [package-upgrade](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95100991951) | 95100991951 | PASS | 10k/100k、210 次包拒收、20 次切换故障、210 次升级矩阵 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090603/job/95101045468) | 95101045468 | PASS | 分域证据合并、完整性和 SHA-256 清单 |

同一提交的 Week 1 六个 Job 在 [#31921090607](https://github.com/eiven-xxw/jshPOS/actions/runs/31921090607) 全部通过，包括固定 Flutter 3.47.0 设备契约回归。所有适用步骤均为 `success`，没有 `continue-on-error`、测试跳过或安全阈值降低。

## 6. 制品与独立复核

| 制品 | Artifact ID | 大小 | GitHub 归档 SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t1-week2-evidence-bundle` | 9256377042 | 4,582 B | `e9d6d26706305a5e48af44e91b5d87b6000a00dd5e7e44b911ec16815a8633de` | 2026-09-15 02:06:56 |
| `t1-week2-package-upgrade-evidence` | 9256374449 | 1,606 B | `235a6ff2ac19f39b74a753d1dd52fc7df7efd6de45f5096556c875b89552dd00` | 2026-09-15 02:06:42 |
| `t1-week2-sqlite-inbox-evidence` | 9256373965 | 1,390 B | `467ed52780c66b1efe7b9c9338be4aebb586dcdf70df500b0e065ba8be5e439a` | 2026-09-15 02:06:39 |
| `t1-week2-tenant-payment-evidence` | 9256370417 | 1,402 B | `4c66e0ba4371d96f87d5e8a5b7e7845153b680ff0e0c58b5724430edeca8ac9f` | 2026-09-15 02:06:21 |
| `t1-week2-static-evidence` | 9256368433 | 1,809 B | `1f47470d3b91232fa7cbf1520395190e716149898034da3dde80748417f02443` | 2026-09-15 02:06:10 |

独立下载 `t1-week2-evidence-bundle` 后，复算归档 SHA-256 与 GitHub 摘要一致。包内：

| 文件 | 大小 | SHA-256 | 等级 |
|---|---:|---|---|
| `evidence-manifest.json` | 587 B | `0dade42d60b194f9c3e0e852041b6f06d876702adddf9267702181f3c8dfa47a` | 清单 |
| `fake-evidence.json` | 4,727 B | `a628e5aff59abccc9a9376077e2449477f4576cf6be91da4eded602b59e1f7a7` | `FAKE` |
| `static-evidence.json` | 4,874 B | `eadc65395ca5868480c10c5f9e6f689c71843e4b89baff7ab40f8d2672c894b8` | `STATIC` |

manifest、解包文件和实现提交 `bab6bed456f9206ddce2201eaadb98951fd06db9` 一致。

## 7. 继续阻断和未解决风险

- `T1-HWD-002`：主认证 Android 厂商/型号/固件/SDK/样机未落实；
- `T1-PRN-001`、`T1-SCN-001`、`T1-SCL-001`、`T1-IO-001`：双打印、扫码、秤、钱箱、客显没有实机；
- `T1-PAY-002`：支付合作方沙箱账号、终端、文档和联系人未提供；
- `T1-PAR-001`：设计伙伴名单、试点意愿和数据授权未落实；
- 物理断电、Android 安装、主机性能、真实网络、支付回调、真实对账均未验证；
- 鲸熵汇继续 `DEFERRED`；
- 正式订单、支付、退款、库存、促销及 T2 仍禁止。

## 8. Week 3 建议与下一步指令

Week 3 建议只做已准入内部探针的交叉故障和稳定恢复：Outbox ACK 丢失与积压恢复、支付 UNKNOWN/回调/退款/对账交叉 Fake、数据包下载/切换进程故障和重放、待同步状态下升级与兼容窗口、固定失败 seed 回归、安全/PII 二次门禁。任何真实设备或支付沙箱只有在资料齐备并单项解除 RTM 阻断后才能进入独立证据轨道。

在收到项目发起人确认前停止 Week 3 编码。建议下一步指令：

```text
我确认《T1 Week 2 周门禁报告》，同意按 CONDITIONAL GO 进入 T1 Week 3。

继续以 t0-baseline-2026-08-16 为唯一技术基线，只允许 RTM 已准入的内部 STATIC/FAKE 交叉故障与恢复探针：
1. 扩展 SQLite Outbox 出队前后、服务端已收但 ACK 丢失、进程重启和积压恢复；
2. 扩展 Inbox/Outbox 在重复、乱序、网络抖动、重启叠加时的最终收敛和固定失败 seed 回归；
3. 扩展五家支付 Fake 的 UNKNOWN→查询/回调收敛、重复乱序回调、退款 UNKNOWN 和合成对账差异；
4. 扩展数据包下载中断+重启、切换 kill、旧包重放、跨租户包和临时文件回收；
5. 扩展待同步状态下升级、迁移失败+应用回退、旧客户端/新 Schema 兼容窗口和前向修复；
6. 重跑租户攻击、Secret/PII、依赖和证据分级门禁，将所有失败 seed 固定加入回归；
7. 实机、外设和支付沙箱资料未齐备前继续 BLOCKED，不创建绿色占位；
8. 完成后提交 Week 3 周门禁报告等待确认。

继续禁止正式订单、支付、库存、促销业务和 T2；不得用 Fake 替代 SANDBOX、REAL_DEVICE、物理断电或商业验收。
```
