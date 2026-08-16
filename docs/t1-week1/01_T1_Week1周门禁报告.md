# T1 Week 1 周门禁报告

> 文档编号：JSH-POS-T1-W1-001
> 日期：2026-08-16
> 基线：annotated tag `t0-baseline-2026-08-16`
> 实现提交：`b960af4f32d896379c0b198f7ecdc354b63621ad`
> GitHub Actions：[T1 Week 1 STATIC FAKE Gates #31919845546](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546)
> 结论：`WEEK1 CONDITIONAL PASS / AWAITING SP CONFIRMATION`

## 1. 管理结论

项目发起人批准的 Week 1 契约、故障夹具与 CI 门禁已经完成并在远端固定环境一次通过。本周只产生 `STATIC` 与 `FAKE` 证据，未修改 `server`、`admin-web`、`pos-flutter` 或正式业务模块，未实现正式订单、支付、库存、促销，未访问支付沙箱、真实设备、真实外设、真实商户数据或生产密钥。

本报告的 `PASS` 仅表示 Week 1 内部风险夹具和证据门禁通过。它不表示对应 T1 需求全部完成，不表示任何设备/支付机构已接入或认证，也不表示系统具备商业发布资格。因此 9 个当前工作项继续保持 `IN_PROGRESS`，7 个外部实证项继续保持 `BLOCKED`；未经项目发起人确认，不进入 Week 2。

## 2. 范围与需求状态

| 状态 | 数量 | Requirement ID | 本周处理 |
|---|---:|---|---|
| `ACCEPTED` | 2 | `T1-GOV-001`、`T1-SCP-001` | 启动治理和范围已获确认 |
| `IN_PROGRESS` | 9 | `T1-HWD-001`、`T1-OFF-001`、`T1-SYN-001`、`T1-TEN-001`、`T1-PAY-001`、`T1-DPK-001`、`T1-UPG-001`、`T1-SEC-001`、`T1-CI-001` | 仅 Week 1 STATIC/FAKE 子集通过 |
| `READY` | 1 | `T1-UAT-001` | 未执行，留待 T1 退出评审 |
| `BLOCKED` | 7 | `T1-HWD-002`、`T1-PRN-001`、`T1-SCN-001`、`T1-SCL-001`、`T1-IO-001`、`T1-PAY-002`、`T1-PAR-001` | 未开发、未运行、未宣称通过 |
| `DEFERRED` | 2 | `T1-JSH-001`、`T1-LIC-001` | 未提前接入或虚构结论 |

## 3. Week 1 交付清单

### 3.1 契约

在 `contracts/poc/t1/` 新增 8 份 JSON Schema 2020-12 契约：

1. 支付候选 Provider Profile；
2. 支付 CREATE/QUERY/REFUND/NOTIFY 合成操作封套；
3. 设备能力、超时、幂等、错误和恢复结果；
4. 通用确定性故障脚本；
5. 合成同步事件；
6. 合成商品/价格数据包；
7. App/Schema 升级与回退用例；
8. `STATIC/FAKE` 机器证据封套。

全仓契约检查共解析 10 份 JSON Schema 和 T0 OpenAPI 骨架，结果 `PASS`。

### 3.2 支付候选档案

建立 10 家候选的 `STATIC/CANDIDATE_ONLY` 档案：银联商务、通联支付、拉卡拉、易宝、汇付天下、富友、收钱吧、嘉联、杉德、随行付。

本周仅选择银联商务、通联、拉卡拉、易宝、汇付五家运行统一 Fake 语义；选择依据只是公开资料可定位性和故障契约覆盖，不是商务签约、费率、服务质量或商业接入排序。所有候选的 `sandboxStatus` 均保持 `BLOCKED`。

### 3.3 故障夹具

共形成 7 个固定 seed 的合成夹具文件：

| 领域 | 主要故障 |
|---|---|
| 设备 | timeout、disconnect、busy、unsupported、unknown result；覆盖打印、扫码、秤、钱箱、客显虚拟能力 |
| 支付 | 超时转 UNKNOWN、重复通知、通知早于响应、失败无效果、退款 UNKNOWN 后查询收敛 |
| 离线原子性 | 事务前、事实后、意图后、Outbox 后、提交后崩溃点；只接受 0/0/0 或 1/1/1 合成结果 |
| 同步 | 重复、乱序、ACK 丢失、游标中断；合成事件按 ID 幂等并按序收敛 |
| 租户 | Mapper、原生 SQL、后台任务、缓存、导出、对象存储六类越权输入 |
| 数据包 | 正常包、坏摘要、坏签名、不兼容 Schema；失败必须保持旧活动版本 |
| 升级 | 正常、下载损坏、迁移失败、健康检查失败、前向兼容；禁止数据 Schema 反向回滚 |

所有输入都明确标记 `synthetic=true` 或 `evidenceLevel=FAKE`，执行器不导入网络库。

## 4. 自动化结果

### 4.1 FAKE 断言

| Requirement ID | 结果 | 断言数 | 结论边界 |
|---|---:|---:|---|
| `T1-HWD-001` | PASS | 17 | 只证明统一合成契约和五类虚拟能力错误/恢复映射 |
| `T1-PAY-001` | PASS | 29 | 只证明五家 Profile 可套用统一 Fake 状态语义 |
| `T1-OFF-001` | PASS | 5 | 只证明纯内存合成事务模型，尚非 SQLite/进程/断电实证 |
| `T1-SYN-001` | PASS | 12 | 只证明固定事件夹具的重复/乱序收敛 |
| `T1-TEN-001` | PASS | 6 | 只证明攻击用例和预期拒绝契约，不代表真实 Mapper/SQL 已验证 |
| `T1-DPK-001` | PASS | 8 | 只证明合成包判定和活动版本不变量 |
| `T1-UPG-001` | PASS | 6 | 只证明虚构 App/Schema 矩阵，不代表 APK/SQLite 实际升级 |
| 合计 | PASS | 83 | 证据等级仅为 `FAKE` |

另有 5 个测试方法全部通过；未使用自动重跑隐藏失败。

### 4.2 GitHub Actions

运行时间：2026-08-16 09:33:34—09:34:49（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 核验内容 |
|---|---:|---:|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097830577) | 95097830577 | PASS | annotated baseline、RTM 生命周期、变更范围、证据生成 |
| [contracts](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097830583) | 95097830583 | PASS | 全仓契约解析、T1 Schema 与确定性夹具 |
| [fake-faults](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097847438) | 95097847438 | PASS | 5 个测试、7 个 Fake 探针、83 条断言、证据等级校验 |
| [device-contract](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097830585) | 95097830585 | PASS | 固定 Flutter 3.47.0、锁定依赖、analyze、现有稳定设备契约测试 |
| [security](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097830547) | 95097830547 | PASS | 校验 Trivy 0.72.0 后执行本周目录 Secret 与 Workflow HIGH/CRITICAL 门禁 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31919845546/job/95097930385) | 95097930385 | PASS | STATIC/FAKE 分级、下载复核、manifest 与 SHA-256 封装 |

全部 Job 和适用步骤均为 `success`，没有用 `continue-on-error`、跳过测试或降低安全阈值。

## 5. 证据制品核验

| 制品 | Artifact ID | 大小 | GitHub 归档 SHA-256 | 到期时间（UTC） |
|---|---:|---:|---|---|
| `t1-week1-evidence-bundle` | 9256021974 | 3,076 B | `657298db8ec9cd52128141ab273a740184b4dfe98c63772bfc8cee61424d98fe` | 2026-09-15 01:34:45 |
| `t1-week1-fake-evidence` | 9256008112 | 1,093 B | `715d849f9c930b49113b36c1a9984c1919908b72da2fc5fdb246cfedd91db298` | 2026-09-15 01:33:51 |
| `t1-week1-static-evidence` | 9256005611 | 1,570 B | `9cd6517ba820ad9de4f94c9808c016279a37a48a1a0471694e4a32b8ad02d6ad` | 2026-09-15 01:33:41 |

独立下载 `t1-week1-evidence-bundle` 后复算归档 SHA-256 与 GitHub 摘要一致。包内清单：

| 文件 | 大小 | SHA-256 | 证据等级 |
|---|---:|---|---|
| `evidence-manifest.json` | 528 B | `a8a3fa97ec9d570879e53e909d665b34f62cb189c9e98ad35bb53d0ea230461f` | 清单 |
| `fake-evidence.json` | 2,082 B | `6b6e89151f76c76705421ab0de949b918e376301497db40f509994952b2ab54a` | `FAKE` |
| `static-evidence.json` | 15,100 B | `13bd29c8dd7db48f13bdbdc0769baf0cd2ee93c4693a5b026ee3564875f9ef6d` | `STATIC` |

manifest 中两份证据的摘要与解包复算一致。

## 6. 阻断项与不可宣称事项

以下内容没有执行、没有绿色占位 Job，也不能引用本周 PASS 对外宣传：

- 未取得明确厂商、型号、Android/固件、CPU/内存、SDK 授权和实机，故主认证机及真实外设仍 `BLOCKED`；
- 内置打印、外置打印、扫码、电子秤、钱箱、客显均未完成 `REAL_DEVICE` 认证；
- 没有任何支付合作方沙箱账号、终端、密钥、技术联系人或受保护环境，`T1-PAY-002` 仍 `BLOCKED`；
- 五家 Fake 通过不等于五家机构接入，十家候选档案不等于十家支持；
- 设计伙伴名单和试点意愿未落实，`T1-PAR-001` 仍 `BLOCKED`；
- 鲸熵汇继续 `DEFERRED`，没有猜测或实现其真实接口；
- 未实现正式订单、支付、退款、库存、促销、会员或结算业务；未进入 T2。

## 7. Week 2 前置判断

建议项目发起人对本报告给出 `CONFIRMED` 或 `REJECTED_WITH_CHANGES`。若确认，仅可按现有 Week 2 计划开展仍处于准入状态的内部风险探针：SQLite 合成事实/意图/Outbox 原子事务、Inbox 幂等与游标恢复、租户旁路攻击、数据包规模与原子切换、App/Schema 升级安全阻断。所有外部项必须继续执行“资料单项齐备、RTM 解阻、独立证据等级”规则。

下一步建议指令见本报告第 8 节；在收到该指令前，停止 Week 2 编码。

## 8. 建议项目发起人下一步指令

```text
我确认《T1 Week 1 周门禁报告》，同意按 CONDITIONAL GO 进入 T1 Week 2。

继续以 t0-baseline-2026-08-16 为唯一技术基线，只允许 RTM 已准入的内部 STATIC/FAKE 风险探针：
1. 建立隔离的 SQLite 合成事实、支付意图与 Outbox 同事务探针，逐注入点验证 0/0/0 或 1/1/1；
2. 建立 Inbox 幂等、同 ID 不同 payload、重复、乱序、ACK 丢失和游标中断恢复探针；
3. 建立两个虚构租户的 API/Mapper/原生 SQL/后台任务/缓存/导出/对象存储攻击探针；
4. 建立 10k/100k 合成数据包、摘要/签名、断点、损坏拒收、兼容与原子切换探针；
5. 建立虚构 App/Schema A→B 升级、迁移失败、健康检查失败、应用回退但数据不反向回滚探针；
6. 保持五家支付 Fake 契约回归；支付沙箱未解阻时禁止网络调用和 SANDBOX 结论；
7. 实机、打印、扫码、电子秤、钱箱、客显在资料齐备前继续 BLOCKED；
8. 扩展 T1 CI、故障 seed、量化证据和 RTM 回填，完成后提交 Week 2 周门禁报告等待确认。

继续禁止正式订单、支付、库存、促销业务和 T2；不得用 Fake 结果替代 SANDBOX、REAL_DEVICE 或商业验收。
```
