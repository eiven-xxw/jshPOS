# Gate 4A 权限、审计、API、事件与后续契约

## 1. 权限与审计

| 权限 | 用途 |
|---|---|
| `inventory:balance:read` | 查看门店仓库存投影 |
| `inventory:movement:apply` | 从受控订单/退款事件应用库存效果 |
| `inventory:policy:publish` | 发布不可变负库存策略版本 |
| `inventory:rebuild` | 对比或受控重建余额投影 |
| `inventory:ledger:read` | 读取不可变流水 |

服务端除菜单权限外必须校验可信租户和门店数据范围。审计保存操作者、命令、来源、策略版本、前后数量、请求摘要和关联标识；跨租户统一返回不可见或拒绝。

## 2. API 与事件

OpenAPI 位于 `contracts/t2/gate4a/openapi-inventory-v1.yaml`。销售/退货应用接口只接收 `eventId`、来源 ID、仓库 ID 与关联 ID；SKU 和数量来自 Owner 端口。事件统一为 `inventory.stock.changed.v1`，负库存另发 `inventory.negative.detected.v1`，均含 Schema、租户内聚合 ID、维度、delta、after、policyVersion 和 correlationId。

## 3. Gate 4B 设计占位（不实现）

- `T2-INV-003`：盘点只允许 `SNAPSHOTTED -> COUNTING -> PENDING_APPROVAL -> POSTED` 后生成盘盈/盘亏流水。
- `T2-PUR-001`：采购收货只提交权威收货行、基础数量与幂等来源，不直接写余额。
- `T2-CST-001`：成本事件引用库存流水，使用 `DECIMAL`，不得阻塞数量账本提交或反写数量。
- `T2-TRF-001`：调拨发出/收货使用两个可对账命令和在途事实，不用单次跨库余额覆盖。
