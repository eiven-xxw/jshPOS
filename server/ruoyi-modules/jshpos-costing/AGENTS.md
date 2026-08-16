# jshpos-costing 模块约束

- 本模块是库存领域成本子模块，独占 `inv_cost_*` 策略、流水、余额、重建、审计和 Outbox；禁止直接更新 `inv_stock_*`、`pur_*` 或历史成本流水。
- tenant_id 只能来自 `TrustedTenantContext`；Controller、DTO 和 `AuthoritativeCostPostingPort` 都不得接收租户字段。
- 成本输入只接受库存 Owner 已插入流水；采购成本只接受采购 Owner 已确认事实。客户端不得提交单位成本、库存数量、采购状态或资金事实。
- 数量使用六位 `BigDecimal`；成本使用以最小货币单位计量的六位 `BigDecimal`；舍入固定 `HALF_EVEN`；禁止 float/double。
- 复杂锁、来源、聚合和重建 SQL 必须位于 Mapper XML，显式列和 tenant_id；成本流水由数据库触发器禁止更新/删除。
- `T2-TRF-001` 只允许设计契约，禁止调拨运行时；Provider 网络、应付、发票、总账、促销和后续 Gate 继续禁止。
- 核心类型、字段、公式、顺序和失败语义必须有有效中文注释。
