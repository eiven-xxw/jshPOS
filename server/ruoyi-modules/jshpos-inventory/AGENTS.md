# Gate 4B 库存模块附加规则

- 本目录在已接受的 `T2-INV-001/002/004` 上只新增 `T2-INV-003` 盘点运行时和 `T2-PUR-001` 所需的内部库存移动端口；供应商与采购单据属于 `jshpos-procurement`，本目录不得反向写 `pur_*`/`sup_*`。
- `inv_stock_ledger` 是数量权威；余额只能通过账本命令或受控重建改变，禁止通用 CRUD。
- 盘点请求只能引用盘点行并提交计数，SKU 与账面事实来自快照；采购库存命令只能来自进程内受控 Owner 端口，外部请求不得直接构造库存移动。
- 动态盘点必须用 snapshot/cutoff ledger sequence 校准，并通过 `STOCKTAKE_GAIN/LOSS` 入账；禁止覆盖余额。成本、调拨、促销和 Provider 网络运行时继续禁止。
- 核心 Java 类型必须有中文业务注释；数量必须使用 BigDecimal 且规范化为六位。
