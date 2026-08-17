# T2 Gate 5D / Sprint S12 周门禁报告

> 文档编号：JSH-POS-T2-G5D-006
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 5D 分支起点：`12c916c7a4b956a0bdca09ebc3ee6b4e19f9cf63`
> 顺序准入及实现提交：`8bb18a7` → `d6b705e` → `2baad26` → `1474c12`
> 全绿实现候选：`1474c128cb25082d00ed93dd715fea3786fb9ff3`
> 全绿 CI：[T2 Gate 5D Reporting Quality Gates #32037632203](https://github.com/eiven-xxw/jshPOS/actions/runs/32037632203)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 5D 获准的两项需求已严格按 `T2-RPT-001 → T2-RPT-002` 完成独立设计准入、实现、提交和完整门禁验证。RPT-001 在独立候选通过 GitHub 十项门禁并更新为 `VERIFIED` 后才准入 RPT-002，没有一次铺开、倒补准入材料或跨 Owner 直查私表。

本轮建立了来源事件 Inbox、单调检查点、逐事件血缘、销售/收银与库存/成本逐日报表、差异修复、影子重建和安全导出；随后建立 Provider 无关支付/退款事实、内部合成账单、确定性匹配差异、处理状态、处理人、不可变审计链、重建与对账安全导出。

候选提交在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器完成 10 个 Job，全部为 `success`，`run_attempt=1`。建议 Gate 5D 结论为 `CONDITIONAL PASS`，`T2-RPT-001/002` 保持 `VERIFIED`；只有项目发起人明确确认后才能更新为 `ACCEPTED`。

最高证据等级为 `STATIC + UNIT + MYSQL8.4_INTEGRATION + SQLITE_REGRESSION + SYNTHETIC_VECTOR`。Provider 网络调用、真实账单下载、真实 PII、支付沙箱、实机和试点证据均为 0，因此本结论不代表 Alpha、实机验收、试点就绪或可商用。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-RPT-001` | `VERIFIED` | 销售/收银、库存/成本逐日投影；来源血缘；缺口标记；差异任务；影子重建；权限脱敏；审批式安全导出；百万行合成容量 | 真实生产量、真实门店口径签署、对象存储生产配置、长周期增量运行 |
| `T2-RPT-002` | `VERIFIED` | Provider 无关支付/退款事实；内部合成账单；确定性匹配；差异状态/处理人；只追加审计；影子重建；100k+100k+100k 容量 | 外部渠道账单下载、渠道签名、真实差异口径、真实支付沙箱与资金 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B-Prep 真实资料清单 | 缺授权沙箱、测试终端、正式接口、签名/回调/退款/账单和技术联系人 |
| `T2-HWD-001` / `T2-PAR-001` | `BLOCKED` | 内部合成与构建回归 | 主认证机/外设、物理断电、长稳和设计伙伴试点未解阻 |

既有 Gate 0—5C 的 `ACCEPTED` 状态保持不变，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。合成账单没有解除外部支付账单阻断。

## 3. 顺序准入与 Owner 边界

| 顺序 | 需求 | 独立证据 | Owner 边界 | 结果 |
|---:|---|---|---|---|
| 1 | `T2-RPT-001` | 提交 `5884b95`；Run `32034358131` 十项全绿 | Reporting Owner 只消费版本化事件或明确只读端口；投影可丢弃重建且不得回写权威事实 | `VERIFIED_RPT001` 后才准入 RPT-002 |
| 2 | `T2-RPT-002` | 提交 `1474c12`；Run `32037632203` 十项全绿 | PaymentReporting 只保存 Provider 无关事实、合成账单、差异投影和审计；不得调用 Provider 或覆盖 Payment/Refund | `VERIFIED`，等待发起人确认 |

复杂投影、幂等 Inbox、重建与审计持久化均为 `XML_ONLY/READ_PROJECTION`，SQL 显式携带 `tenant_id`。Controller 只负责协议、权限和可信上下文入口；核心实体、规则、服务、端口和迁移均保留中文注释。

## 4. 核心不变量与安全控制

- 每条投影保存来源事件 ID、摘要、Schema/投影版本、租户、组织/门店、业务日和检查点；同键同摘要幂等，同键异摘要拒绝。
- 乱序、晚到和缺失形成可审计缺口或补算；影子重建完成摘要校验后原子切换，旧投影可丢弃但权威事实不可修改。
- 金额、数量、币种、时区、业务日和舍入沿用 Owner 冻结值，报表层不重新发明交易、库存、成本或优惠算法。
- 对账优先使用冻结业务引用，再使用受约束候选键；金额/币种/类型不一致显式列为差异，不以报表值覆盖支付或退款事实。
- 差异处理状态只能经受权命令推进，操作人来自可信上下文；每次状态转换写入不可变审计链，禁止 UPDATE/DELETE 历史审计。
- 查询、任务、缓存、临时文件、对象键和下载令牌均包含租户命名空间及数据范围；跨租户、跨门店和对象替换失败关闭。
- 导出执行独立权限、审批阈值、范围/行数上限、白名单字段、脱敏、水印、短期单次令牌、到期清理和 CSV 公式注入防护。
- Provider SDK/HTTP、真实回调、渠道账单下载、沙箱/生产密钥、真实资金与真实 PII 均未进入运行时或证据。

## 5. CI 发现的问题与修复记录

| Run | 结果 | 发现 | 处理 |
|---|---|---|---|
| [#32033071016](https://github.com/eiven-xxw/jshPOS/actions/runs/32033071016) | `cancelled` / MySQL 红 | V32 列注释误用了 MySQL 不接受的 `COMMENT='…'` | 在迁移尚未发布或接受前修正为 `COMMENT '…'`，重算摘要并保留失败证据 |
| [#32033937246](https://github.com/eiven-xxw/jshPOS/actions/runs/32033937246) | `cancelled` / MySQL 红 | 测试把最高版本序号误当连续迁移文件数 | 改为断言实际文件集合、二次执行为 0、Flyway validate 和最高版本；没有降低门禁 |
| [#32034358131](https://github.com/eiven-xxw/jshPOS/actions/runs/32034358131) | `success` | RPT-001 无遗留失败 | 10 个 Job 完整单次全绿后才准入 RPT-002 |
| [#32037632203](https://github.com/eiven-xxw/jshPOS/actions/runs/32037632203) | `success` | RPT-002 无失败 | 10 个 Job 首次完整运行全绿 |

失败/取消 Run、日志、提交和报告均保留。没有降低覆盖率或安全阈值、跳过测试、自动重跑掩盖 Flaky、修改封存 tag、修改已发布迁移或创建绿色占位。

## 6. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | 437 tests，0 failure/error/skipped；Reporting 52 tests |
| Reporting 核心覆盖率 | PASS | line 238/247 = 96.36%；branch 183/202 = 90.59%；高于 90%/85% 阈值 |
| MySQL 8.4.6 | PASS | Gate 0 + Reporting 6 个实际迁移文件到 V35；重复 migrate=0、validate、租户复合键与不可变触发器通过 |
| 容量 | PASS | 1,000,000 条经营投影；100,000 支付/退款事实 + 100,000 合成账单 + 100,000 对账投影/查询 |
| 租户、权限与导出攻击 | PASS | 两个虚构租户、多组织/门店/终端；56 个攻击面；Provider 网络和真实 PII 均为 0 |
| 固定故障矩阵 | PASS | 33 个重复、乱序、晚到、缺失、同键异内容、重建、篡改和导出场景 |
| Flutter POS | PASS | Linux/Windows 锁定依赖、analyze 与既有 71 项回归；SQLite/Gate 5A—5C 覆盖率门禁保持 |
| Android/Kotlin | PASS | Kotlin 编译和 debug APK 构建通过；不等于主认证机实机验收 |
| Web | PASS | audit/build/lint/typecheck 与 11 项契约回归通过；安全导出和差异审计工作台可用 |
| 安全与供应链 | PASS | Secret、IaC、服务端/Flutter HIGH/CRITICAL、双 SBOM 和许可证门禁通过 |

## 7. GitHub Actions Job 与制品

全绿运行 `#32037632203` 为 `run_attempt=1`，从 2026-08-17 14:02:45Z 至 14:09:29Z，耗时约 6 分 44 秒。

| Job | Job ID | 结果 |
|---|---:|---|
| governance | 95411110331 | PASS |
| server | 95411110339 | PASS |
| mysql-migration-capacity | 95411110278 | PASS |
| tenant-export-security | 95411110360 | PASS |
| synthetic-vectors | 95411110296 | PASS |
| pos-linux | 95411110228 | PASS |
| pos-windows | 95411110254 | PASS |
| admin-web | 95411110336 | PASS |
| security-sbom-license | 95412106563 | PASS |
| evidence | 95412187443 | PASS |

| Artifact | ID | 大小（B） | GitHub digest |
|---|---:|---:|---|
| `t2-gate5d-evidence-index` | 9291280954 | 11,586 | `sha256:839f9532f734ad84d449bcbf4d904ae5898d6851705061698e111cead82d4ffa` |
| `t2-gate5d-security` | 9291274904 | 88,490 | `sha256:6606a1221d163e81e4428af919bd4822aeeb0f246e4c5df31b9064e22de4b135` |
| `t2-gate5d-server` | 9291267158 | 154,978,109 | `sha256:8dd9e7ea3fea7202e04d94e51f06405846ae7538318fcc48ecdd824e5430f12c` |
| `t2-gate5d-pos-linux` | 9291255073 | 74,835,859 | `sha256:c930a619b1f020fa09631e970334c53e3eb384f9e3abc3c05a38613e90047589` |
| `t2-gate5d-pos-windows` | 9291232524 | 5,200 | `sha256:1726ad10d8e44e8f57be437056ea476b5327481e8834038db68a7f3b72feebc2` |
| `t2-gate5d-mysql` | 9291215432 | 5,751 | `sha256:3db644b881607aa36dbd48dcb4bb8ece71fc772c015e657f769b100d0f4ed513` |
| `t2-gate5d-web` | 9291194265 | 79,913 | `sha256:064dc402249729ddd4743c19744c4f43caa55cec2598010a0b39f964faf9877b` |
| `t2-gate5d-tenant` | 9291185855 | 52,457 | `sha256:30746df0b8c7cfafb78df64589d16deabeb7764a40da1550e6f5d4519d19b60f` |
| `t2-gate5d-governance` | 9291179672 | 1,031 | `sha256:ad18cb0347d48ce4f6b0e2a2e801638f23f563cf8f2eefed3603a24aaaeba552` |
| `t2-gate5d-vectors` | 9291179655 | 1,995 | `sha256:fd6914d71dec608ca9cd3e143080c770e57a469c64469159161828bea1a968e4` |

10 个制品合计 230,060,391 B。每个生产 Job 只上传一份制品，最终 Job 只上传逐文件 SHA-256 证据索引，没有重复上传 APK、JAR、SBOM 或全量输入包。

## 8. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 继续 `BLOCKED`；真实 Provider 账单、SDK/HTTP、回调、终端、沙箱/生产密钥和资金均未验证。
- P0：主认证 Android、两种打印、扫码、电子秤、钱箱、客显、物理断电、升级和多日长稳未验证；构建通过不是 `REAL_DEVICE`。
- P0：设计伙伴及真实试点未解阻，三类业态只使用合成数据，`PILOT=0`。
- P1：真实 PII 为 0，尚未验证生产 KMS/HSM、真实数据保留/删除和运营导出审批。
- P1：对象存储、备份恢复、服务端/Web/APK 灰度升级及 RPO/RTO 尚未进入独立 Gate 验收。
- 外部证据持续为 `sandbox=0`、`realDevice=0`、`pilot=0`；合成结果不得解除任何阻断。

## 9. 退出建议

建议项目发起人接受 Gate 5D `CONDITIONAL PASS`，并在明确确认后将 `T2-RPT-001`、`T2-RPT-002` 从 `VERIFIED` 更新为 `ACCEPTED`。

下一内部阶段建议为 Gate 6A / Sprint S13，按 `T2-TRM-001 → T2-BAK-001` 收口终端身份治理与备份恢复；`T2-UPG-001` 本阶段只进行契约、矩阵和故障夹具准备，真实 APK 安装/回滚继续被 `T2-HWD-001` 阻断。这样可以先消除 Alpha 候选前的恢复性风险，不会用 CI 或模拟设备冒充实机证据。

本报告、下一步指令和 RTM 证据封存提交后必须再运行完整 Gate 5D CI；若封存复跑不是全绿，本报告自动失效。发起人确认前，不得把两项需求改为 `ACCEPTED`，不得启动 Gate 6、支付 Provider 网络或 Alpha/UAT，不得宣称可试点或可商用。
