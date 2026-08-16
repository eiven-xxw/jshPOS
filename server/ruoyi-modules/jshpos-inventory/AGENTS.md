# Gate 4A 库存模块附加规则

- 本目录只实现 `T2-INV-001`、`T2-INV-002`、`T2-INV-004`，不得出现盘点、采购、成本、调拨、促销或 Provider 网络运行时。
- `inv_stock_ledger` 是数量权威；余额只能通过账本命令或受控重建改变，禁止通用 CRUD。
- 请求不得包含 tenant_id、SKU、数量或支付状态；这些值只来自可信上下文和 Owner 只读端口。
- 核心 Java 类型必须有中文业务注释；数量必须使用 BigDecimal 且规范化为六位。
