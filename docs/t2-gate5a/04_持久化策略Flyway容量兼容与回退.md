# Gate 5A 持久化策略、Flyway、容量、兼容与回退

> 文档编号：JSH-POS-T2-G5A-004
> 决策依据：ADR-027、ADR-028

## 1. 服务端表策略登记

| table_name | owner_module | strategy | model / repository_mapper | tenant_source | mutation_boundary / reason |
|---|---|---|---|---|---|
| `prm_rule` | jshpos-promotion | `MP_ENTITY` | `PromotionRuleEntity` / `PromotionRuleRepository` + `PromotionRuleMapper` | TrustedTenantContext | 简单规则身份 CRUD；仅草稿期可更新，禁止物理删除 |
| `prm_rule_version` | jshpos-promotion | `XML_ONLY` | `RuleVersionWrite/View` / `PromotionRepository` + XML | TrustedTenantContext | 版本状态条件更新、发布锁与内容不可变 |
| `prm_rule_scope` | jshpos-promotion | `XML_ONLY` | `RuleScopeWrite` / XML | TrustedTenantContext | 已发布作用域只追加，不暴露通用更新 |
| `prm_rule_benefit` | jshpos-promotion | `XML_ONLY` | `RuleBenefitWrite` / XML | TrustedTenantContext | 金额/比例/组合参数随版本冻结 |
| `prm_rule_package` | jshpos-promotion | `XML_ONLY` | `PackageWrite/View` / XML | TrustedTenantContext | 规范化内容、摘要和签名只追加，版本指针状态切换 |
| `prm_rule_package_item` | jshpos-promotion | `XML_ONLY` | `PackageItemWrite/PublishedRuleRow` / XML | TrustedTenantContext | 冻结包与规则版本成员及 AST 摘要，使服务端和 POS 按同一包计算 |
| `prm_quote` | jshpos-promotion | `XML_ONLY` | `QuoteWrite/View` / XML | TrustedTenantContext | 报价事实与请求摘要幂等，不物理删除 |
| `prm_quote_line` | jshpos-promotion | `XML_ONLY` | `QuoteLineWrite/View` / XML | TrustedTenantContext | 逐行金额事实只追加 |
| `prm_adjustment` | jshpos-promotion | `XML_ONLY` | `AdjustmentWrite/View` / XML | TrustedTenantContext | 命中/排除解释和调整只追加 |
| `prm_manual_price_audit` | jshpos-promotion | `XML_ONLY` | `ManualAuditWrite/View` / XML | TrustedTenantContext | 人工优惠授权与审计不可修改 |
| `prm_transaction_snapshot` | jshpos-promotion | `XML_ONLY` | `SnapshotWrite/View` / XML | TrustedTenantContext | 每订单冻结摘要，不可修改 |
| `prm_transaction_allocation` | jshpos-promotion | `XML_ONLY` | `AllocationWrite/View` / XML | TrustedTenantContext | 优惠分摊只追加 |
| `prm_refund_allocation_ledger` | jshpos-promotion | `XML_ONLY` | `RefundAllocationWrite/View` / XML | TrustedTenantContext | 累计退款恢复账本只追加、稳定幂等 |
| `prm_command_result` | jshpos-promotion | `XML_ONLY` | `CommandWrite/View` / XML | TrustedTenantContext | 同键同摘要返回原结果，同键异摘要拒绝 |
| `prm_audit_event` | jshpos-promotion | `XML_ONLY` | `AuditWrite` / XML | TrustedTenantContext | 审计只追加 |
| `prm_event_outbox` | jshpos-promotion | `XML_ONLY` | `EventWrite` / XML | TrustedTenantContext | Outbox 只追加，状态按条件更新 |

任何 Mapper SQL 必须显式列名、租户条件和稳定排序；正式模块不新增 SQL 注解。应用和领域层只依赖 Repository/端口。

## 2. Flyway

- `V202608170020__gate5a_promotion.sql`：仅创建 PRM-001 所需 12 张 `prm_*` 表、索引、复合租户外键、不变性触发器和完整中文表/字段 COMMENT。
- `V202608170021__gate5a_permissions.sql`：仅写入 `9200900—9200908` 的 PRM-001 菜单/API 权限；`9200909—9200914` 只保留编号，不提前写入。
- PRM-001 验证并提交后，PRM-002 才允许用 V22 新增人工优惠审计表和 `9200909—9200911` 权限；PRM-003 验证后才允许用 V23 新增快照、分摊、退款恢复账本和 `9200912—9200914` 权限。
- 发布前在 MySQL 8.4.6 完整执行当时已准入的全部迁移、重复 validate/migrate、`information_schema` 中文注释、复合租户外键、不可变触发器和前向修复验证。
- closure 时封存 V20—V23 和 POS V3—V5 的 SHA-256；封存后禁止修改，修复只能新增前向迁移。

## 3. POS SQLite V3

V3 只新增 PRM-001 的双槽包、活动指针、报价、报价行和规则调整表；并以前向 rebuild 方式放宽 Gate 2 中“优惠恒为 0”的订单/行约束，改为金额守恒约束。PRM-002/003 验证前不得出现人工授权、成交快照、分摊或退款恢复表；其后分别通过 V4、V5 前向迁移加入。迁移先关闭外键、事务内复制和重建、恢复外键后执行 `foreign_key_check` 与快照摘要验证；任何失败保留原文件供恢复，不能半迁移启动收银。

历史 V1/V2 字符串和校验和不得修改。V3 迁移必须支持空库、含挂单/完成单、进程终止前后和重复启动恢复测试。

## 4. 容量与性能

- 单规则版本最多 64 个条件节点、256 个作用域值、32 个组合组件；单包最多 5,000 个规则版本，未支持能力在包安装时拒绝。
- 单报价最多 500 行、2,000 个候选调整；组合求解最多 10,000 次候选评估，超过即 `PRM-RULE-COMPLEXITY`。
- 20 行常规 POS 黄金向量在 CI 参考环境统计趋势，目标本地 P95 < 50ms；100 行压力目标 < 500ms，但没有实机前不形成设备承诺。
- 报价、快照和审计按业务日分段索引；在线保留期与归档属于后续运维配置，归档不能删除订单仍可退款的快照。

## 5. 兼容与回退

- 规则包声明 `engineMin/engineMax`，旧客户端遇到新算子失败关闭；服务端保留至少一个旧引擎兼容窗口。
- 发布回退通过暂停当前版本、切换上一发布版本和生成新包，不修改历史规则、报价、快照或分摊。
- 应用回退不能回滚成功 Schema；关闭新入口并使用兼容读路径。迁移、投影或包异常通过新增前向迁移/修复包处理。
- 已开始支付或已冻结订单继续使用原报价与快照，不能在结算中途切换规则版本。
