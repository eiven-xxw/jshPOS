# T2 Gate 5A / Sprint S9 周门禁报告

> 文档编号：JSH-POS-T2-G5A-006  
> 日期：2026-08-17  
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`  
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`  
> Gate 5A 分支起点：`63537b5cb7ceeb1fe6b04107b53e2e68941b25ad`  
> 顺序准入提交：`fa2aec0` → `0012ebc` → `ee4165f` → `caddb2a`  
> 最终技术候选：`beb06c203a8e1ae47f32a1189d738efe67ff8650`  
> 首轮失败 CI：[T2 Gate 5A Quality Gates #31997642023](https://github.com/eiven-xxw/jshPOS/actions/runs/31997642023)  
> 第二轮失败 CI：[T2 Gate 5A Quality Gates #31998379856](https://github.com/eiven-xxw/jshPOS/actions/runs/31998379856)  
> 修复后全绿 CI：[T2 Gate 5A Quality Gates #31998613262](https://github.com/eiven-xxw/jshPOS/actions/runs/31998613262)  
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 5A 获准的三项需求已严格按 `T2-PRM-001 → T2-PRM-002 → T2-PRM-003` 顺序完成设计准入、独立实现、独立提交和总门禁验证。最终候选在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器运行十个 Job，全部通过；没有降低覆盖率或安全阈值、跳过失败测试、自动重跑掩盖 Flaky、修改已发布迁移或创建绿色占位。

本轮建立了版本化且可解释的确定性基础促销、受权人工改价/整单优惠/抹零、不可变成交优惠快照、金额守恒分摊和按原快照累计退款恢复内核。建议将三项需求保持为 `VERIFIED` 并提交 Gate 5A `CONDITIONAL PASS`，等待项目发起人确认后方可更新为 `ACCEPTED`。

本结论是组件与契约级通过，不是端到端交易通过。现有 POS 现金结算与服务端订单 Owner 尚未在同一正式编排中消费促销快照，旧结算路径仍按零优惠生成订单；该差距登记为下一阶段 P0，不能以本报告宣称促销成交、退货退款、Alpha、试点或商用已经闭环。

最高证据等级为 `STATIC + UNIT + MYSQL_INTEGRATION + SQLITE_INTEGRATION + CROSS_RUNTIME_VECTOR + SYNTHETIC_PROPERTY`。外部证据仍为 `sandbox=0`、`realDevice=0`、`pilot=0`，Provider 网络调用为 0。

## 2. 需求状态与边界

| Requirement ID | 状态 | 本轮已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-PRM-001` | `VERIFIED` | 不可变规则版本、发布/暂停、七类基础规则、作用域、稳定排序、互斥/叠加、解释、签名离线包、Java/Dart 同向量 | 大规模预算、券、积分、会员等级、动态定价、真实门店规则运营 |
| `T2-PRM-002` | `VERIFIED` | 手工改价、整单固定/比例优惠、现金抹零、权限阈值、职责分离、签名策略、只追加审计、同键异内容拒绝 | 真实员工权限矩阵、门店操作长稳、线上审批工作流 |
| `T2-PRM-003` | `VERIFIED` | 成交快照、来源分摊、最大余数稳定顺序、金额守恒、部分/末次退款和累计上限 | POS/订单/现金/退款/库存的正式跨 Owner 端到端编排 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B 真实资料核验清单 | 缺首接 Provider 授权沙箱、测试终端、正式接口和技术联系人；网络调用为 0 |
| `T2-MEM-001..002` | `DRAFT` | 无 | 未准入会员运行时，禁止真实个人信息 |
| `T2-RPT-001..002` | `DRAFT` | 无 | 未准入报表运行时；支付报表受 `T2-PAY-002` 阻断 |

既有 Gate 0—4D 状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。Fake 与合成证据没有解除任何外部阻断。

## 3. 顺序准入与实现证据

| 顺序 | 需求 | 准入与完成证据 | 结果 |
|---:|---|---|---|
| 1 | `T2-PRM-001` | `fa2aec0` 完成设计准入；`0012ebc` 完成规则引擎、生命周期、离线包和跨端向量 | `VERIFIED_PRM1` 后才准入 PRM-002 |
| 2 | `T2-PRM-002` | `ee4165f` 完成人工优惠策略、鉴权/复核、只追加审计、SQLite V4 和同向量 | `VERIFIED_PRM2` 后才准入 PRM-003 |
| 3 | `T2-PRM-003` | `caddb2a` 完成快照、来源分摊、累计退款恢复、SQLite V5 和 10,000 组属性测试 | `VERIFIED`，等待总门禁与发起人确认 |

代码历史与变更日志同时证明三项没有一次铺开后补设计。每项状态转换前均由准入脚本检查数据主权、状态、不变量、权限、审计、API/事件、迁移、容量、回退和测试证据。

## 4. 架构、数据主权与工程规范

- 新增模块化单体 `jshpos-promotion`，独占规则、版本、作用域、优惠报价、人工优惠审计、规则包、成交快照、分摊和退款分摊账本；领域逻辑未进入 Controller、通用工具类或 RuoYi 系统模块。
- 基础价和门店价仍由 Price Owner 管理；Promotion Owner 只读价格输入并生成优惠报价/快照，不回写价目表。订单、支付、库存、成本历史事实均未被促销模块直接修改。
- `tenant_id` 只来自可信服务端/设备绑定上下文；覆盖 Mapper、XML、任务、缓存、导入导出、对象存储、规则包、快照和退款恢复攻击面。
- 简单 `prm_rule` CRUD 使用 MyBatis-Plus；复杂锁定、汇总、幂等竞争恢复和不可变账本 SQL 使用 Mapper XML。Record、领域模型、参数对象和持久化实体保持分层；核心代码、实体与 Schema 具备中文注释。
- 服务端与 Flutter POS 共用同一规则语义、错误码、稳定排序、摘要和黄金向量。离线包采用版本、能力清单、摘要、签名校验和 A/B 原子切换；损坏或能力不兼容时失败关闭。

## 5. 计算顺序、精度与不变量

- 计算顺序冻结为：基础/门店价 → 单品促销 → 组合/满减 → 受权手工改价 → 整单折扣 → 抹零。
- 候选按 `priority desc, ruleVersionId asc` 稳定排序；互斥与叠加只能使用显式策略，不依赖数据库自然顺序或客户端本地时间。
- 金额使用最小货币单位整数；数量和换算使用缩放整数/`BigDecimal`，禁止 `float`/`double`。单行实付与整单实付均不得为负。
- 订单级优惠使用稳定最大余数法，余数按 `orderLineNo, skuId, lineId` 分配；强制满足 `行原价 = 行优惠 + 行实付`、`整单原价 = 整单优惠 + 整单实付` 及逐行合计守恒。
- 手工优惠冻结改前/改后值、原因、阈值、操作人、审批人、终端、业务日、策略版本和关联标识；需要审批时禁止本人自批。
- 规则、人工策略、成交快照、分摊和退款分摊均只追加。退款只读原成交快照和累计已退量，不按当前规则重算历史成交；同幂等键同内容返回原结果，同键异内容拒绝。
- MySQL V20—V23 和 POS SQLite V3—V5 已封印摘要；候选封存后只能新增前向迁移，不能回写已发布脚本或删除事实。

## 6. 两轮真实失败、根因与修复

首轮运行 `#31997642023` 不计为通过。MySQL 8.4.6 实迁移发现 V20/V22/V23 使用 `DEFAULT UTC_TIMESTAMP(3)`，该表达式不是合法的列默认值语法。修复为在 CI 明确 UTC 会话下使用 `DEFAULT CURRENT_TIMESTAMP(3)`，同步重算尚未发布候选迁移的 SHA-256；未更改业务状态机或时间口径。

第二轮运行 `#31998379856` 同样不计为通过。MySQL 8.4.6 发现 V20 列名 `nth_value` 与窗口函数保留关键字冲突。修复为 `nth_item_value` 并同步 Mapper 显式映射及迁移摘要，没有使用转义绕开兼容风险。

两次修复均发生在 Gate 5A 迁移尚未封存、未发布、未被接受的候选阶段。没有跳过 MySQL 门禁、降低约束或仅重跑失败 Job；最终对十个 Job 进行完整、单次重跑并全部通过。

## 7. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | CI 聚合 309 tests，0 failure/error/skipped |
| Promotion 核心覆盖率 | PASS | Java line 584/627 = 93.14%；branch 577/665 = 86.77%；阈值 90%/85% |
| Flutter POS | PASS | analyze 与 51 tests 通过；生产代码行 824/888 = 92.79%；Linux/Windows 双执行器通过 |
| MySQL 8.4.6 | PASS | V1—V23 实际 migrate/validate；V20—V23、复合租户约束、不变触发器和前向修复通过 |
| SQLite | PASS | V1→V5 升级、租户/终端约束、只追加保护、损坏与兼容回归通过 |
| 跨端固定向量 | PASS | 24/24；Java 与 Dart 逐字段结果及摘要一致 |
| 属性测试 | PASS | 固定 seed 10,000 组；金额守恒、累计退款上限和稳定顺序通过 |
| 租户与权限攻击 | PASS | 两个虚构租户、21 个攻击面；越权成功路径 0，Provider 网络入口 0 |
| 契约与 RTM | PASS | 69 个 Schema/OpenAPI 契约；唯一 Requirement ID、状态和证据规则通过 |
| 安全与供应链 | PASS | Secret、IaC、HIGH/CRITICAL、服务端/Flutter SBOM 和许可证门禁通过 |

## 8. GitHub Actions 与制品

最终候选运行 `#31998613262` 为 `run_attempt=1`、总结果 `success`、总时长约 9m24s，共 10 个 Job、10 个制品。

| Job | Job ID | 结果 | 主要证据 |
|---|---:|---|---|
| governance | 95294724552 | PASS | 基线祖先、顺序准入、RTM VERIFIED、ADR、范围和迁移封印 |
| server | 95294724607 | PASS | 309 测试、Promotion 覆盖率、Admin JAR、聚合 SBOM |
| mysql-migration | 95294724652 | PASS | MySQL 8.4.6 V1—V23 与 Gate 5A 约束 |
| tenant-security | 95294724574 | PASS | 可信上下文、权限与 21 面攻击矩阵 |
| cross-runtime-vectors | 95294724698 | PASS | 24 个 Java/Dart 固定向量与 10,000 组属性测试 |
| pos-linux | 95294724614 | PASS | Flutter、SQLite、Kotlin、debug APK、覆盖率 |
| pos-windows | 95294724600 | PASS | Windows Flutter 干净执行器回归 |
| admin-web | 95294724616 | PASS | audit/build/lint/typecheck/测试/许可证 |
| security-sbom-license | 95295865247 | PASS | Trivy、双 SBOM、Secret/IaC/许可证 |
| evidence | 95295975997 | PASS | 九类上游证据、237 文件与 SHA-256 索引 |

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate5a-promotion-evidence-bundle` | 9277804814 | 458,517,670 B | `sha256:997e21eb83b8f1316fa5775cf9379d5d1b6da3a2f35714cbd58eae709ef5a832` |
| `t2-gate5a-security` | 9277784071 | 229,236,302 B | `sha256:889dba70dc9bd8681262eb0cd75979ea5195cd9b4b10ed704daf4e83a0bca9a9` |
| `t2-gate5a-pos-linux` | 9277770183 | 74,832,308 B | `sha256:fd0a4cb9dc76ded3cf54d3734f218f00583c07eba984d9f181a38196325181da` |
| `t2-gate5a-server` | 9277747962 | 154,316,858 B | `sha256:f45e3016d5c78bcf5cee331ddc3467fb22dbd647e23f97d9a63b098b58939c28` |
| `t2-gate5a-pos-windows` | 9277697209 | 4,151 B | `sha256:bda6df46435c83e88f9b1905695a180877e529289706d68b9eb805698355e5f9` |
| `t2-gate5a-mysql` | 9277678453 | 6,163 B | `sha256:99492ad8b9cc21c2e122358a0645d119e5db3f2523b425cc1dfb53efdd6d5885` |
| `t2-gate5a-web` | 9277670142 | 79,783 B | `sha256:6e276d748d6465780a206dfcdb2a16abea6fdf5050235c11a830f212167c5e30` |
| `t2-gate5a-tenant` | 9277662672 | 27,087 B | `sha256:633a5766a04d7d6bc2dacb4e6e07a64857769b636a6e6e1c9cf2def94f55c159` |
| `t2-gate5a-governance` | 9277648274 | 1,153 B | `sha256:17367f32c6cfafacdd5837a07c85bbaa85e86d2b18b2867a06a441333a38a63f` |
| `t2-gate5a-vectors` | 9277648215 | 974 B | `sha256:42b63d74f7455cfeb1036aae4d430bf47d98891607369b86ae2ddd28abf62254` |

证据聚合摘要：

```text
T2-GATE5A EVIDENCE OK: files=237 serverTests=309 vectors=24 property=10000 paymentNetwork=0
```

## 9. 风险、阻断与不可宣称

- P0：Promotion Owner 已能冻结并恢复优惠，但 POS 现金结算和 Order Owner 尚未原子消费该快照；现有正式结算路径仍生成零优惠订单。进入会员/报表前必须先完成跨 Owner 交易集成和崩溃恢复验证。
- P0：`T2-PAY-002` 继续 `BLOCKED`；缺授权沙箱、测试终端、正式接口、验签/回调/退款/账单资料和技术联系人，本轮 Provider 网络调用为 0。
- P0：主认证 Android 实机及打印、扫码、电子秤、钱箱、客显和物理断电证据缺失，`REAL_DEVICE=0`。
- P1：当前规则能力未包含优惠券、积分、储值、会员等级、预算抢占和动态定价；不得从已存在的扩展字段推断这些能力已完成。
- P1：规则容量、并发和退款结果来自合成数据与 CI，不是生产历史迁移、真实门店长稳、财务审计或商业验收。
- P1：设计伙伴仍未解阻，三类业态只使用合成样本；`PILOT=0`。
- 外部状态继续为 `sandbox=0`、`realDevice=0`、`pilot=0`；不得用 Fake 或合成数据解除阻断。

## 10. 退出建议

建议项目发起人接受 Gate 5A `CONDITIONAL PASS`，并在明确确认后将 `T2-PRM-001`、`T2-PRM-002`、`T2-PRM-003` 从 `VERIFIED` 更新为 `ACCEPTED`。

下一内部阶段不建议直接铺开会员或报表。应先建立 Gate 5B 的交易集成准入项，完成 POS 报价/人工优惠/成交快照/现金落单/Outbox 的本地原子闭环，升级服务端订单金额不变量并绑定不可变促销快照，完成原单退货退款的跨 Owner 恢复编排。会员与基础报表本阶段最多进行设计、契约和合成用例准备。

项目发起人确认本报告前，不得把三项需求改为 `ACCEPTED`，不得启动 Gate 5B、会员、报表、支付 Provider 网络或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
