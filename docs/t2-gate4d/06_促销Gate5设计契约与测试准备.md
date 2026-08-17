# Gate 5 促销设计、契约与测试准备

> 状态：`T2-PRM-001`、`T2-PRM-002`、`T2-PRM-003` 均为 `DRAFT`。本文件仅为 Gate 5 准入输入，不创建 Java/Flutter/Vue 运行时、Controller、Mapper、数据库迁移或菜单。

## 1. 需求边界与数据主权

| Requirement ID | 设计范围 | 唯一 Owner |
|---|---|---|
| `T2-PRM-001` | 促销规则版本、适用范围、候选计算、优先级、互斥与叠加 | 未来 `jshpos-promotion` |
| `T2-PRM-002` | 受权手工改价、整单折扣、抹零和金额上限 | 未来 `jshpos-promotion`，权限仍由 Gate 0 IAM/RBAC 提供 |
| `T2-PRM-003` | 成交优惠快照、订单行分摊、退款按原快照恢复 | 促销 Owner 计算；订单 Owner 冻结结果；退款 Owner 只读原快照 |

商品、基础价和门店价仍由商品价格 Owner 管理；促销不得回写价目表。订单金额事实由订单 Owner 冻结；促销不得修改已完成订单。`tenant_id` 只能由可信上下文注入。

## 2. 候选状态机

```text
DRAFT -> VALIDATED -> SCHEDULED -> ACTIVE -> EXPIRED -> RETIRED
   |          |            |          |
   +->REJECTED+->DRAFT      +->SUSPENDED->ACTIVE
```

- 已发布版本不可原地修改；修订必须生成新 `rule_version_id`。
- 同一规则版本只允许单向发布，未来生效按门店业务时区解析。
- `ACTIVE` 只代表进入候选集，是否命中仍取决于商品、门店、渠道、会员条件和预算。
- 取消/暂停只影响新购物篮；已冻结成交快照不被重算。

## 3. 计算顺序与不变量

1. 固定顺序：基础/门店价 → 单品促销 → 组合/满减 → 受权手工改价 → 整单折扣 → 抹零。
2. 候选按 `priority desc, rule_version_id asc` 稳定排序；互斥组只保留首个合法候选。
3. 同一金额层级先执行互斥，再按显式 `stack_policy` 叠加，禁止依赖数据库自然顺序。
4. 所有金额为最小货币单位整数；数量为 `DECIMAL/BigDecimal`，禁止浮点数。
5. 单行成交额不得小于零；整单优惠不得超过参与分摊行的可优惠金额。
6. 整单优惠按可优惠金额比例采用最大余数法分摊，余数顺序固定为 `order_line_no, sku_id`。
7. `sum(line_discount_minor) = order_discount_minor`，且 `sum(line_payable_minor) = order_payable_minor`。
8. 手工改价必须保存改前价、改后价、原因、权限、操作者、审批者和关联标识；不得伪装成普通促销。
9. 退款只读取原成交快照，按原行优惠与已退累计上限恢复，禁止以当前促销重算历史订单。
10. 规则预算、次数和券核销等并发资源必须使用稳定幂等键和原子占用；该能力未准入前一律失败关闭。

## 4. 候选 API、事件与错误语义

设计契约位于 `contracts/t2/gate4d/promotion-gate5-design-v1.yaml`。候选端点包括规则预检、发布、购物篮试算、手工改价授权和成交快照冻结。所有端点当前 `runtimeAllowed=false`、`networkCallsAllowed=0`。

候选事件为 `promotion.rule.published.v1`、`promotion.rule.suspended.v1`、`promotion.quote.calculated.v1`、`promotion.snapshot.frozen.v1`。事件必须包含标准包络、规则版本、购物篮/订单标识、稳定摘要、业务日和关联标识，不携带生产密钥或会员敏感信息。

候选错误码域：`PRM-RULE-*`（规则/版本）、`PRM-STACK-*`（互斥叠加）、`PRM-AMOUNT-*`（金额守恒）、`PRM-AUTH-*`（手工改价权限）、`PRM-SNAPSHOT-*`（冻结/退款恢复）、`PRM-IDEM-*`（幂等与同键异内容）。

## 5. 候选 Flyway 设计（不得在 Gate 4D 创建）

未来迁移只允许新增：`prm_rule`、`prm_rule_version`、`prm_rule_scope`、`prm_rule_condition`、`prm_rule_benefit`、`prm_quote`、`prm_quote_line`、`prm_transaction_snapshot`、`prm_transaction_allocation`、`prm_manual_price_audit`、`prm_event_outbox`。

所有表使用 `(tenant_id, id)` 主键/唯一键和租户复合外键；发布版本、成交快照、分摊和审计只追加。规则 JSON 必须同时保存 `schema_version`、规范化摘要和可查询关键列。未来迁移失败只允许前向修复，不修改已发布迁移。Gate 4D 不分配版本号，也不创建绿色占位表。

## 6. 合成向量与 Gate 5 准入条件

24 个设计向量位于 `contracts/t2/gate4d/test-vectors/promotion-fixed-vectors-design-v1.json`，覆盖单品价、第二件、满减、组合、互斥、叠加、未来生效、业务日边界、手工改价、整单折扣、抹零、最大余数分摊、退款恢复、重复/乱序、并发预算、跨租户和迁移回退。

Gate 5 正式编码前必须重新完成数据主权、状态、不变量、权限、审计、API/事件、Flyway、容量、回退和测试准入，并由项目发起人单独确认。设计文件与静态向量不构成 `VERIFIED`、`ACCEPTED`、试点或商用证据。
