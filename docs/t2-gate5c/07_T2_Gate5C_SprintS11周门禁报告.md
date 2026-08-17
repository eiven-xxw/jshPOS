# T2 Gate 5C / Sprint S11 周门禁报告

> 文档编号：JSH-POS-T2-G5C-007
> 日期：2026-08-17
> 唯一不可变技术基线：annotated tag `t2-prep-baseline-2026-08-16`
> 基线 peeled commit：`557ba270479935d6b44968cf70b47033f7d3d656`
> Gate 5C 分支起点：`2b55ad6154b75bce1ff19c68a50e025afe7f1e93`
> 顺序准入提交：`4c5dbdf` → `5b3ffad` → `d618213` → `a2e3ba4`
> 全绿实现候选：`96b826b64d463db73bad4a428fbe27c1c8c68160`
> 全绿 CI：[T2 Gate 5C Member Privacy Points Quality Gates #32025473259](https://github.com/eiven-xxw/jshPOS/actions/runs/32025473259)
> 当前结论：`CONDITIONAL PASS / VERIFIED / AWAITING CONFIRMATION`

## 1. 管理结论

Gate 5C 获准的两项需求已严格按 `T2-MEM-001 → T2-MEM-002` 完成独立设计准入、实现、提交和完整门禁验证。MEM-001 在独立提交达到 `VERIFIED` 后才准入 MEM-002，没有一次铺开或倒补准入材料。

本轮建立了会员最小主体、统一身份、同意与隐私请求、可逆合并/拆分、去敏事件和 POS 最小令牌缓存；随后建立了只追加等级历史、不可变积分流水、FEFO 批次、冻结原分配结算、显式债务、成交/退货/到期冲正、乐观锁投影和全量重建。

实现候选在 GitHub Ubuntu、Windows 与 MySQL 8.4.6 干净执行器完成 10 个 Job，全部为 `success`，`run_attempt=1`。建议将 `T2-MEM-001/002` 继续保持 `VERIFIED` 并提交 Gate 5C `CONDITIONAL PASS`；只有项目发起人明确确认后才能更新为 `ACCEPTED`。

最高证据等级为 `STATIC + UNIT + MYSQL_INTEGRATION + SQLITE_INTEGRATION + SYNTHETIC_VECTOR`。外部证据仍为 `sandbox=0`、`realDevice=0`、`pilot=0`，真实 PII 与 Provider 网络调用均为 0。因此本结论不代表 Alpha、实机验收、试点就绪或可商用。

## 2. 需求状态与边界

| Requirement ID | 状态 | 已验证 | 未验证/保留边界 |
|---|---|---|---|
| `T2-MEM-001` | `VERIFIED` | 最小会员主体；HMAC 精确检索；版本化 AES-GCM 身份；同意/撤回；隐私请求历史；脱敏展示；可逆合并/拆分；去敏 Outbox；SQLite V7 最小令牌摘要缓存 | 真实 PII 运营审计、KMS/HSM、真实短信、外部会员平台、实机丢失/换机演练 |
| `T2-MEM-002` | `VERIFIED` | 等级只追加历史；积分只追加账本；FEFO；冻结原分配；显式债务；累计冲正上限；精确 DECIMAL；门店业务日；操作/审批分离；幂等、重建与前向修复 | POS 离线积分消费、真实会员政策运营、真实门店并发和长期权益对账 |
| `T2-RPT-001/002` | `DRAFT` | 权威事实到可重建投影、权限脱敏、导出和合成验收设计输入 | 未准入任何 `rpt_*` 表、Controller、Mapper、Job 或正式报表运行时 |
| `T2-PAY-002` | `BLOCKED` | Gate 3B-Prep 真实资料清单 | 缺授权沙箱、测试终端、正式接口、签名/回调/退款/账单和技术联系人 |

既有 Gate 0—5B 的 `ACCEPTED` 状态保持不变；`T2-HWD-001`、`T2-PAR-001` 保持 `BLOCKED`，`T2-JSH-001`、`T2-LIC-001` 保持 `DEFERRED`。Fake 与合成证据没有解除任何外部阻断。

## 3. 顺序准入和 Owner 边界

| 顺序 | 需求 | 独立提交 | 主要边界 | 结果 |
|---:|---|---|---|---|
| 1 | `T2-MEM-001` | `5b3ffad` | Member Owner 独占会员、身份、同意、隐私、命令结果和会员事件；PII 不进入 POS 明文缓存、日志、审计或事件 | `VERIFIED_MEM001` 后才准入 MEM-002 |
| 2 | `T2-MEM-002` | `a2e3ba4`，修复至 `96b826b` | Member Owner 独占等级、积分、批次、分配和投影；Order/Return 仅通过稳定命令或版本化事件提供来源事实 | `VERIFIED_MEM002`，等待发起人确认 |

复杂积分、身份和隐私持久化均为 `XML_ONLY`，所有 SQL 显式携带 `tenant_id`；简单框架 CRUD 仍可使用 MyBatis-Plus。Controller 只做协议和权限入口，不承载领域算法；核心实体、规则、服务、端口、迁移均保留中文注释。

## 4. 隐私、积分与恢复不变量

- 身份值经规范化后只以 HMAC 摘要检索、以带密钥版本的 AES-GCM 密文保存；缺密钥、版本未知、认证失败全部失败关闭。
- POS SQLite 只保存 `member_ref`、令牌 SHA-256、脱敏标签、等级代码、权益摘要、快照版本和到期/撤回时间；不保存手机号、卡号、OpenID、身份密文或积分余额。
- `tenant_id` 只来自可信上下文；门店权限和业务日由服务端 `StoreService` 计算，客户端不能提交租户、操作人或业务日。
- 积分金额使用 `DECIMAL(19,6)`/`BigDecimal` 且拒绝隐式舍入；账户的可用、冻结、债务均不得为负。退货扣回不足形成显式债务，后续正向积分先还债。
- 等级、积分和分配事实只追加；数据库触发器拒绝更新/删除。余额投影可从流水重建，批次更新与账户更新使用乐观锁。
- 冻结按 FEFO；消费或解冻严格沿用原冻结分配。退货只引用原获赠/消费流水，累计冲正不得超过原始额度，策略版本必须沿用原事实。
- 人工积分调整和等级变更要求租户管理员、结构化原因、独立审批人及审批引用，操作人与审批人不得相同。
- 同幂等键同内容返回原结果，同键异内容拒绝；重复、乱序、晚到、投影损坏、迁移失败均使用稳定事实、全量重建或新前向迁移修复，不回写历史。

## 5. CI 发现的真实问题与修复

| Run | 结果 | 真实问题 | 处理 |
|---|---|---|---|
| [#32024927799](https://github.com/eiven-xxw/jshPOS/actions/runs/32024927799) | `cancelled`（Flutter、MySQL 已红） | 两个旧 Flutter 回归仍断言 SQLite V6/6 条历史；MySQL 测试误将 17 个保留菜单 ID 等同于 17 个不同权限字符串 | 旧回归更新为 V7/7 条；MySQL 改为精确统计保留 ID 行数，保留父菜单与查询动作合法复用 `member:profile:read` |
| [#32025300523](https://github.com/eiven-xxw/jshPOS/actions/runs/32025300523) | `cancelled` | 已含 Flutter 修复，但在 MySQL 断言修复推送后按并发策略取消；MySQL 旧断言仍红 | 保留日志和失败证据，未局部重跑或跳过测试 |
| [#32025473259](https://github.com/eiven-xxw/jshPOS/actions/runs/32025473259) | `success` | 无 | 10 个 Job 完整单次运行全绿，最终候选为 `96b826b64d463db73bad4a428fbe27c1c8c68160` |

失败 Run、Workflow Run、日志、提交和报告均保留。没有降低覆盖率/安全阈值、自动重跑掩盖 Flaky、修改已封存 tag、修改已发布迁移或创建绿色占位。

## 6. 量化质量结果

| 门禁 | 结果 | 量化证据 |
|---|---|---|
| 服务端完整 reactor | PASS | 383 tests，0 failure/error/skipped；会员模块 33 tests |
| Member 核心覆盖率 | PASS | line 95/100 = 95.00%；branch 53/56 = 94.64%；达到既定阈值 |
| MySQL 8.4.6 | PASS | 24 个实际迁移文件到 V202608170031；重复 migrate=0、validate、复合租户键、不可变触发器和 17 个保留菜单 ID 通过 |
| Flutter POS | PASS | Linux/Windows 各 71 tests；analyze 通过；Gate 5C 60/62 = 96.77%；Gate 5A/5B 回归继续高于 90% |
| Android 构建 | PASS | Kotlin 编译和 debug APK 通过；APK 162,603,076 B，SHA-256 `3c173cf1dffc41c464e3e50190ddf60aa0d70b89077280bce82e0bb22cdb7393` |
| 租户、隐私与权限攻击 | PASS | 2 个虚构租户、多组织/门店；34 个攻击面；Provider 网络调用 0、真实 PII 0 |
| 固定故障矩阵 | PASS | 15 个重复、乱序、晚到、冲正、到期、审批、重建和跨租户场景 |
| Web | PASS | audit/build/lint/typecheck 与 8 tests 通过；许可证清单生成 |
| 安全与供应链 | PASS | Secret、IaC、HIGH/CRITICAL 漏洞、服务端/Flutter SBOM 和许可证门禁通过 |
| 总证据 | PASS | 151 个证据文件逐文件 SHA-256；最终索引单独轻量上传 |

## 7. GitHub Actions Job 和制品

全绿运行 `#32025473259` 为 `run_attempt=1`，从 2026-08-17 11:33:12Z 至 11:40:42Z，耗时约 7 分 30 秒。

| Job | Job ID | 结果 |
|---|---:|---|
| governance | 95373928256 | PASS |
| server | 95373928302 | PASS |
| mysql-migration | 95373928258 | PASS |
| tenant-security | 95373928315 | PASS |
| synthetic-vectors | 95373928308 | PASS |
| pos-linux | 95373928170 | PASS |
| pos-windows | 95373928240 | PASS |
| admin-web | 95373928444 | PASS |
| security-sbom-license | 95375491166 | PASS |
| evidence | 95375580928 | PASS |

| Artifact | ID | 大小 | GitHub digest |
|---|---:|---:|---|
| `t2-gate5c-evidence-index` | 9287113602 | 10,408 B | `sha256:d9a28efec7ae72a19c0fc52ca717a6cc4600404fd762d6418926172de2513a9a` |
| `t2-gate5c-security` | 9287105125 | 87,715 B | `sha256:be5b9fa83ec432ec05357f6fd6e3a5c847cd7c972782264927101ee54fc4b1b9` |
| `t2-gate5c-server` | 9287094944 | 154,589,297 B | `sha256:484078b0fd1fad4f2d0f67c61cdac018da848341591f0930b5084f18329a03d6` |
| `t2-gate5c-pos-linux` | 9287044223 | 74,835,858 B | `sha256:0767711feaf1317d0d2c4aa042613d64edf73fca5f87e51dfc858b940096547f` |
| `t2-gate5c-pos-windows` | 9286995563 | 5,185 B | `sha256:4905a68d97694449b724ce687db29be5094628a728c889863c8e2facac733121` |
| `t2-gate5c-mysql` | 9286949252 | 6,138 B | `sha256:efc26c3a93300de65f3de1d73bda65ce4b752eb22d7105cdc6936ccb7d36dec7` |
| `t2-gate5c-web` | 9286945630 | 79,792 B | `sha256:970edcca1711039ca46822bd3aba0f3fd897a0eb7efb4c2b495c392771a25b3a` |
| `t2-gate5c-tenant` | 9286935395 | 26,810 B | `sha256:bcaba471f1f838829ed2c0c26ed1c63cc1318265ae1eb57443481dc76c135ba0` |
| `t2-gate5c-vectors` | 9286919367 | 1,370 B | `sha256:110100ed9986731f14aba6ea377ca92ef1649ee9132229678fa601a776c594f9` |
| `t2-gate5c-governance` | 9286918589 | 1,037 B | `sha256:14be43d94e9fa8cf5fe3fa6ddfd2af91c7c17bab9e7c1a6d0032bddf13136110` |

10 个制品合计 229,643,610 B；每个生产 Job 只保留一份制品，最终 Job 仅上传证据索引，没有重复打包全量 APK、JAR 或 SBOM。

## 8. 证据归档

全绿证据索引已下载至 `C:\Users\Administrator\.codex\archives\jshPOS-actions-20260817-gate5c\32025473259_9287113602_t2-gate5c-evidence-index.zip`。本地文件大小 10,408 B，SHA-256 为 `d9a28efec7ae72a19c0fc52ca717a6cc4600404fd762d6418926172de2513a9a`，与 GitHub Artifact digest 完全一致。

## 9. 风险、阻断与不可宣称

- P0：`T2-PAY-002` 继续 `BLOCKED`；本轮无 Provider SDK/HTTP、真实回调、账单下载、生产密钥或真实资金。
- P0：主认证 Android 实机、打印/扫码/电子秤/钱箱/客显、物理断电和多日长稳未验证；Kotlin/APK 通过不是 `REAL_DEVICE`。
- P0：设计伙伴和真实试点未解阻；三类业态只使用合成数据，`PILOT=0`。
- P1：真实 PII 为 0，因此尚未验证生产 KMS/HSM、保留期执行、真实数据主体请求和线下运营审计。
- P1：报表仍为 `DRAFT`；优惠券、储值、真实短信、营销自动化、外部会员平台、总账、发票等未实现。
- 外部证据持续为 `sandbox=0`、`realDevice=0`、`pilot=0`；Fake 和合成结果不得解除任何阻断。

## 10. 退出建议

建议项目发起人接受 Gate 5C `CONDITIONAL PASS`，并在明确确认后将 `T2-MEM-001`、`T2-MEM-002` 从 `VERIFIED` 更新为 `ACCEPTED`。

下一内部阶段建议为 Gate 5D / Sprint S12，严格按 `T2-RPT-001 → T2-RPT-002` 建立可重建、可对账、受租户和数据范围约束的基础报表。`T2-RPT-002` 只能实现 Provider 无关内部事实与合成账单部分，真实渠道账单继续依赖 `T2-PAY-002` 的独立解阻评审。

本报告、下一步指令和 RTM 证据封存提交后必须再运行完整 Gate 5C CI；若封存复跑不是全绿，本报告自动失效。发起人确认前，不得将两项需求改为 `ACCEPTED`，不得启动 Gate 5D、支付 Provider 网络或后续 Gate 正式编码，不得宣称 Alpha、可试点或可商用。
