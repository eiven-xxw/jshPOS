# T2 Gate 3A 周门禁报告

> 文档编号：JSH-POS-T2-G3A-006
> 日期：2026-08-16
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 3A 分支起点：`60828acbbe18c30914a076709905eeb525ffeb5f`
> 技术候选：`f987af9d7293b91027e890326cafc8c101b89920`
> 技术候选 CI：[T2 Gate 3A Quality Gates #31953461656](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 3A 获准的 Provider 无关支付核心、查询/观察合并、原单退款和支付/退款/账单对账四项需求已完成设计、实现和内部验证。技术候选在 GitHub Ubuntu、Windows、MySQL 8.4.6 干净执行器运行十个 Job，全部通过；证据聚合器核验 157 个文件并形成 SHA-256 索引。

本轮形成的是 Provider 无关资金事实内核。正式运行时没有 Provider HTTP/SDK、外部回调 Controller、渠道凭据、账单下载器或网络调用；所有渠道观察和账单均为固定合成向量。因此建议将 `T2-PAY-001`、`T2-PAY-003`、`T2-REF-001`、`T2-REC-001` 保持为 `VERIFIED` 并提交 `CONDITIONAL PASS`，但不自行更新为 `ACCEPTED`。

`T2-PAY-002` 继续 `BLOCKED`，外部证据保持 `sandbox=0`、`realDevice=0`、`pilot=0`。本报告不表示渠道已接入、真实资金可用、Alpha、可试点或可商用。

## 2. 需求状态与证据边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-PAY-001` | `VERIFIED` | 支付意图/尝试、稳定请求号、幂等、UNKNOWN、late success、成功不回退、正式审计与 Outbox | Provider 请求、真实扣款、关单/撤销渠道语义 |
| `T2-PAY-003` | `VERIFIED` | 查询/同步响应/回调/账单观察的统一内部契约、重复乱序、同 ID 异 hash、冲突死信 | 外部回调入口、签名验真、重放窗口和渠道查询 |
| `T2-REF-001` | `VERIFIED` | 原成功支付退款、四眼审批、金额/数量占额、并发上限、退款 UNKNOWN 与成功累计 | Provider 退款调用、真实优惠/库存回补、真实退款时效 |
| `T2-REC-001` | `VERIFIED` | 不可变合成账单、支付/退款双源匹配、七类差异、四眼处理闭环 | 真实渠道账单下载、结算日/手续费/拒付商业核对 |
| `T2-PAY-002` | `BLOCKED` | 解阻清单和适配器边界 | 缺授权沙箱、测试终端、正式文档、回调网络和技术联系人 |

`T2-HWD-001`、`T2-PAR-001` 继续 `BLOCKED`；`T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`。既有 Gate 0—2 与 `T2-SYN-001` 的 `ACCEPTED` 状态未改变。

## 3. 架构与实现结果

- 新增模块化单体 `jshpos-payment`；Controller 仅完成协议适配，支付、退款、对账规则留在领域/应用层，没有写入 RuoYi 系统模块或通用工具类。
- 支付意图只从权威订单只读端口建立；金额使用最小货币单位整数，币种、租户、门店、终端、订单和关联标识在资金事实中冻结。
- 支付尝试最多 8 次；存在 `UNKNOWN` 时禁止生成替代尝试，后到成功事实可收敛未知状态，已成功事实不得被失败/取消观察回退。
- Provider 观察先校验主体、金额、币种、请求号、事件 ID 与 payload hash；重复同事实幂等返回，同 ID 异 hash 或身份冲突进入不可变死信。
- 退款必须关联原支付和原订单；请求与审批人分离，锁定支付聚合后重新校验累计金额和逐行数量，防止并发超退。
- 对账同时保留内部资金事实和外部账单事实，不用账单覆盖支付/退款；差异只进入可审计 Case，并以调查、解决、独立审批、关闭完成四眼闭环。
- Flyway V9/V10 建立 13 张资金/退款/对账/审计/Outbox 表与 10 项权限；所有业务 SQL 显式带可信 `tenant_id`，复杂 SQL 使用 Mapper XML，无 `SELECT *`。
- 支付权限使用独立号段 `9200400–9200409`，避免与同步模块 `9200300–9200303` 冲突。
- V9/V10 已写入 `contracts/t2/gate3a/migration-checksums.json`，封印后只允许新增迁移进行前向修复。

## 4. 状态机与不变量结论

- 支付：`CREATED → PROCESSING/UNKNOWN → SUCCEEDED/FAILED/CANCELLED/CLOSED`；成功后仅允许进入部分/全部退款聚合状态，不允许资金事实倒退。
- 退款：`CREATED → PENDING_APPROVAL → PROCESSING/UNKNOWN → SUCCEEDED/FAILED/CANCELLED/CLOSED`；审批人与申请人不得相同。
- 观察合并：只接受已验证的内部观察对象；`UNKNOWN` 只能由原尝试查询、可信观察或账单证据收敛，禁止重建支付命令。
- 对账：账单行、观察、状态历史、审计、退款行和幂等结果为不可变/只追加事实；差异处理不得修改原支付或退款。
- 租户：`tenant_id` 仅来自可信上下文，Mapper、原生 SQL、任务、缓存、导出、对象存储及 HTTP 边界均 fail-closed。

## 5. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | Foundation 57 + Catalog 21 + Order 18 + Sync 19 + Payment 49 = 164 tests；0 failure/error/skipped |
| Payment 核心覆盖率 | PASS | line 156/162 = 96.30%；branch 182/192 = 94.79%；阈值 90%/85% |
| MySQL 8.4.6 | PASS | V1—V10 完整 Flyway、重复 migrate/validate、13 张 Gate 3A 表、双租户复合外键、权限号段和不可变触发器；1 integration test |
| 固定 Fake 契约 | PASS | 16/16 向量映射到执行测试；Provider 网络调用 0 |
| 租户与权限 | PASS | 两个虚构租户、12 个攻击面；越权成功路径 0 |
| Flutter Linux | PASS | 38 tests；S3 同步核心 line 90.94%；Kotlin 与 debug APK 编译通过 |
| Flutter Windows | PASS | 38 tests；独立 Windows 干净执行器完成 analyze、SQLite 与 HTTP 回归 |
| Web 回归 | PASS | audit/build/lint/typecheck/8 tests/许可证门禁通过 |
| 安全与供应链 | PASS | 服务端/Flutter SBOM、HIGH/CRITICAL 漏洞、Secret、IaC、许可证门禁通过 |
| 证据聚合 | PASS | 157 文件；九类上游证据完整并生成 SHA-256 索引 |

证据摘要：

```text
T2-GATE3A EVIDENCE OK: stage=admitted files=157 serverTests=164 flutterLinux=38 flutterWindows=38 serverBranch=0.9479 flutterLine=0.9094
```

## 6. GitHub Actions

技术候选运行时间：2026-08-16 22:41:50—22:55:46（Asia/Shanghai），`run_attempt=1`，总结果 `success`。

| Job | Job ID | 结果 | 主要证据 |
|---|---:|---|---|
| [governance](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433671) | 95180433671 | PASS | 基线祖先、RTM、ADR、契约、范围和依赖差异 |
| [server](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433697) | 95180433697 | PASS | 164 测试、覆盖率、Admin JAR、聚合 SBOM |
| [mysql-migration](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433758) | 95180433758 | PASS | MySQL 8.4.6、V1—V10、复合外键和不可变约束 |
| [tenant-security](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433698) | 95180433698 | PASS | 可信上下文、Mapper 与 12 面租户攻击 |
| [payment-fake](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433714) | 95180433714 | PASS | 16 个固定支付/退款/对账 Fake 向量 |
| [pos-linux](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433666) | 95180433666 | PASS | 38 测试、覆盖率、Kotlin、APK、Flutter SBOM/许可证 |
| [pos-windows](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433685) | 95180433685 | PASS | Windows 38 测试与独立 SQLite/HTTP 回归 |
| [admin-web](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95180433719) | 95180433719 | PASS | audit/build/lint/typecheck/测试/许可证 |
| [security-sbom-license](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95181491776) | 95181491776 | PASS | Trivy、双 SBOM、漏洞/Secret/IaC/许可证 |
| [evidence](https://github.com/eiven-xxw/jshPOS/actions/runs/31953461656/job/95181935495) | 95181935495 | PASS | 九类证据和 157 文件 SHA-256 索引 |

Workflow 不含 `continue-on-error`，没有自动 retry、失败测试跳过、阈值降低或绿色占位。

## 7. 主要制品

| Artifact | ID | 大小（B） | GitHub digest |
|---|---:|---:|---|
| `t2-gate3a-provider-neutral-payment-evidence-bundle` | 9265464339 | 456519364 | `sha256:04c726b97b003066fae9be800a53cd04ab090a031c577fc85baa60a425b29b1d` |
| `t2-gate3a-security` | 9265417148 | 228236774 | `sha256:1cb4b7242e0161bb35208d2ffbec74020ddee446e7e1919333385510aad8a51b` |
| `t2-gate3a-server` | 9265406207 | 153326997 | `sha256:6506d5a06da691e6f1ed63c801f979715286bcf6842f8511c2937bafc662c0ba` |
| `t2-gate3a-pos-linux` | 9265378167 | 74828581 | `sha256:e0a87dc8e67776f5e41cd753603e91ab2312890b60460f266c0cc7f308eb0877` |
| `t2-gate3a-pos-windows` | 9265350232 | 3071 | `sha256:f2a1d9a2604dd52a181b3ff0b0d6460d9fa621e68ae057748c7ef7c4b4bc7b7c` |
| `t2-gate3a-tenant` | 9265349309 | 26774 | `sha256:d0b7de96d52b2f318d56411b33c3cd8124f102a904651c94a06b08b33fad9fd5` |
| `t2-gate3a-mysql` | 9265347156 | 5823 | `sha256:03e8234f9bc28b841732e76fcaffaf81e959b158d261d80aa665525543bcf824` |
| `t2-gate3a-web` | 9265311809 | 79788 | `sha256:52dd6022095583e16857dce55288a5ad175c90124ae1bff8390b2e0f5a99c336` |
| `t2-gate3a-fake` | 9265295622 | 1334 | `sha256:1682699030326be202babb5a603e91ab2312890b60460f266c0cc7f308eb0877` |
| `t2-gate3a-governance` | 9265295446 | 1023 | `sha256:2b9d2d3187bd2f413418e77b20b04a80b49d4a6dfcf818d445e3be31ccd0cf86` |

## 8. 发现问题与修复记录

- 收口前交叉检查发现同步权限已占用 `9200300–9200303`，支付迁移原计划复用了该号段。未放宽冲突检查，支付整体迁移到 `9200400–9200409` 并增加 MySQL 断言。
- 运行 [#31953142730](https://github.com/eiven-xxw/jshPOS/actions/runs/31953142730) 的 MySQL Job 正确发现支付测试类路径只加载 8 个迁移，未包含已封板同步 V7/V8，因期望 10、实际 8 而失败。修复仅在测试作用域引入 `jshpos-sync` 迁移资源，保持生产运行时模块边界；随后启动全新完整运行 #31953461656，V1—V10 与全部十个 Job 通过。
- 失败运行没有被单独重跑或计为通过，也没有把期望值从 10 降为 8、删除约束或降低任何安全/覆盖率阈值。

## 9. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 缺授权沙箱商户、测试终端、正式接口/签名/回调/查询/退款/账单资料和技术联系人；保持 `BLOCKED`。
- P0：主认证 Android 实机、外设 SDK、物理断电和弱网长稳证据缺失，`REAL_DEVICE=0`。
- P1：五家设计伙伴及试点授权尚未形成，`PILOT=0`。
- P1：Gate 3A 没有 Provider 适配器和外部回调入口，不能证明渠道错误码、签名、限流、结算日或真实 UNKNOWN 收敛。
- `T2-JSH-001`、`T2-LIC-001` 继续 `DEFERRED`；新增依赖仍受即时 SBOM 与许可证门禁约束。

## 10. 退出建议

建议项目发起人接受 Gate 3A `CONDITIONAL PASS`，并在明确确认后将四项需求从 `VERIFIED` 更新为 `ACCEPTED`。在 `T2-PAY-002` 独立解阻前，Gate 3B 只能进行资料核验、适配器设计和沙箱测试计划，禁止任何 Provider 网络调用。

Gate 4 直接依赖已接受的 Gate 2，可与 Gate 3B 外部解阻并行，但必须由项目发起人另行准入；建议首波只允许库存不可变流水、销售出入库和库存策略，采购、成本、盘点、调拨继续设计准备，不一次铺开。

项目发起人确认本报告前，不得把四项需求改为 `ACCEPTED`，不得启动 Provider 网络、库存、采购、成本、促销或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
