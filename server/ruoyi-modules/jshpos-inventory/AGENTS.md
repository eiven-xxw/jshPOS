# Gate 4B/4C/4D 库存模块附加规则

- 本目录在已接受的 `T2-INV-001/002/004` 上只新增 `T2-INV-003` 盘点运行时和 `T2-PUR-001` 所需的内部库存移动端口；供应商与采购单据属于 `jshpos-procurement`，本目录不得反向写 `pur_*`/`sup_*`。
- `inv_stock_ledger` 是数量权威；余额只能通过账本命令或受控重建改变，禁止通用 CRUD。
- 盘点请求只能引用盘点行并提交计数，SKU 与账面事实来自快照；采购库存命令只能来自进程内受控 Owner 端口，外部请求不得直接构造库存移动。
- 动态盘点必须用 snapshot/cutoff ledger sequence 校准，并通过 `STOCKTAKE_GAIN/LOSS` 入账；禁止覆盖余额。
- Gate 4C 只允许增加不含 tenant_id 的进程内成本提交端口，并在库存流水、余额、成本流水、审计和 Outbox 的同一事务中调用；成本公式与 `inv_cost_*` 写入必须位于 `jshpos-costing`，本目录不得写成本表。
- Gate 4D 仅允许 `jshpos-transfer` 通过进程内受控 Owner 端口提交 `TRANSFER_OUT` 与 `TRANSFER_IN`；两者必须分别形成独立命令和流水，禁止直接跨仓覆盖余额。除此之外的调拨、促销和 Provider 网络运行时继续禁止。
- 核心 Java 类型必须有中文业务注释；数量必须使用 BigDecimal 且规范化为六位。

## Gate 7C S21-F 批次效期附加规则

- `T2-LOT-001` 只对 `COMMUNITY_SUPERMARKET` 中已发布且启用的 SKU 策略生效；便利店、零食折扣店和未配置 SKU 必须保持无批次路径。
- `inv_lot_identity`、`inv_lot_ledger`、`inv_lot_allocation` 是 Inventory Owner 的不可变事实；余额和效期投影只能从批次流水重建。
- 批次拆分必须与同一来源 event/line 的 `inv_stock_ledger` 数量和方向守恒；采购、退货、盘点和调拨只能通过 `AuthoritativeLotMovementPort` 提交。
- FEFO 固定按 `expiry_date, received_date, lot_id` 升序；到期日当日可售，次日起失败关闭，原单退货优先恢复原批次。
- 不得在本目录引入批次成本、库位、波次、质检、召回、自动报损或复杂 WMS 运行时。
