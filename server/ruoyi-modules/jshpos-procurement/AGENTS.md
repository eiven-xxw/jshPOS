# jshpos-procurement 模块约束

- 本模块拥有 `sup_*`、`pur_*` 的供应商、采购单、收货和原收货退货事实。
- `tenant_id` 只能来自 `TrustedTenantContext`；Controller/DTO/命令不得接收租户字段。
- 采购库存效果只允许调用 `AuthoritativeInventoryMovementPort`，禁止直接更新 `inv_stock_balance` 或写 `inv_stock_ledger`。
- 采购单不改变库存；确认收货使用 `PURCHASE_RECEIPT_IN`，原收货退货使用 `PURCHASE_RETURN_OUT`。
- 数量使用六位定点小数，单位换算只用冻结的整数分子/分母；金额使用最小货币单位整数。
- Gate 4C 只允许增加采购成本来源只读端口；它必须从已确认收货/已入账原收货退货与冻结采购单价、单位换算中产生事实，不得接受调用方自报价格，不得写 `inv_cost_*`。
- `T2-TRF-001` 仍只可有设计、契约和测试向量，不得创建运行时类、表、API 或任务。
- 核心类型、字段和不变量必须有中文注释；复杂 SQL 放 XML。
