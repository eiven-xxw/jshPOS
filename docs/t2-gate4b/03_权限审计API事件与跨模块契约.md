# Gate 4B 权限、审计、API、事件与跨模块契约

## 1. 权限与职责分离

- 盘点：创建/计数、提交、复核、批准入账、查看分别授权；复核人和批准人不得与创建人相同，批准人不得与复核人相同。
- 采购：供应商维护、采购单创建、提交、审批、收货、退货申请、退货审批和查看分别授权；采购审批人与创建人分离，退货审批人与申请人分离。
- 所有服务端操作再次校验可信 tenant、门店范围和聚合所属关系；前端菜单权限不构成最终授权。

## 2. 审计

计数修订、盘点提交/复核/入账、供应商状态、采购提交/审批、收货确认和退货入账必须记录 actor、before/after、reason、correlation、request hash 和 UTC 时间。已确认收货、已入账退货、库存流水、计数事实和审计只追加不可变。

## 3. API 与事件

正式 OpenAPI 位于 `contracts/t2/gate4b/openapi-stocktake-procurement-v1.yaml`。正式事件为 `inventory.stocktake.posted.v1`、`procurement.purchase-order.approved.v1`、`procurement.receipt.confirmed.v1`、`procurement.return.posted.v1`；库存数量效果仍同时形成 `inventory.stock.changed.v1`。

采购只能调用进程内 `AuthoritativeInventoryMovementPort`。端口不接受 tenant_id，租户来自当前可信上下文；端口只接受 Owner 已验证的来源、行、SKU、基础单位、精确基础数量和业务日。

## 4. 成本与调拨设计契约

`T2-CST-001` 将消费采购收货/退货和库存流水的不可变引用，使用 `DECIMAL(19,6)`，不得反写数量账。`T2-TRF-001` 将以发出、在途、收货的独立幂等事实表达，禁止单次跨仓余额覆盖。两者本阶段 `x-runtime-allowed=false`。
