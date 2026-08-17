# jshpos-transfer 模块约束

- 本模块独占 `inv_transfer_*` 调拨单、行、命令、发出/收货事实、在途流水、差异处置、审计和 Outbox；禁止直接写 `inv_stock_*`、`inv_cost_*`、`pur_*`。
- `tenant_id` 只能来自 `TrustedTenantContext`；Controller、DTO、内部 Owner 端口均不得接受租户字段。
- 发出与收货必须分别调用 `AuthoritativeInventoryMovementPort`，使用独立稳定 eventId；禁止跨仓余额覆盖或绕开库存 Owner。
- 调拨成本来源只通过只读 `TransferCostSourcePort` 提供权威发出/收货关系；客户端不得提交单位成本，目的仓必须继承来源发出成本。
- 在途流水只追加并可重建；发出后禁止原地取消。少收、破损、拒收和在途损失必须显式审批，超收必须拒绝。
- 数量使用六位 `BigDecimal`；复杂锁、聚合、状态更新和对账 SQL 位于 Mapper XML，显式列和 tenant_id；禁止 `SELECT *`。
- 核心聚合、状态、命令、服务、Mapper、事务边界和复杂 SQL 必须有中文注释。
- 促销、支付 Provider 网络、会员、报表、应付、发票、总账、批次成本和复杂 WMS 运行时继续禁止。
