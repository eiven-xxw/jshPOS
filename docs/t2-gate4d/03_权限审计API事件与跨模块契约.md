# Gate 4D 权限、审计、API、事件与跨模块契约

## 1. 权限和数据范围

| 权限 | 说明 | 数据范围 |
|---|---|---|
| `transfer:order:create` | 创建调拨草稿 | 来源和目的门店均可见 |
| `transfer:order:submit` | 提交草稿 | 来源和目的门店均可见 |
| `transfer:order:approve` | 审批 | 来源和目的门店可见；创建人不得自批 |
| `transfer:dispatch:post` | 发出 | 来源和目的门店均可见 |
| `transfer:receipt:post` | 收货 | 来源和目的门店均可见 |
| `transfer:difference:approve` | 差异审批 | 来源和目的门店均可见 |
| `transfer:order:cancel` | 发出前取消 | 来源和目的门店均可见 |
| `transfer:order:read` | 查询与在途对账 | 来源和目的门店均可见 |

路由权限只控制展示，应用服务必须再次校验可信用户、租户、两侧门店范围和状态。审批人与创建人职责分离；差异处置必须记录原因、数量、操作者和关联标识。

## 2. API

OpenAPI 位于 `contracts/t2/gate4d/openapi-transfer-v1.yaml`，正式根路径为 `/api/v1/inventory/transfers`。创建以 `transferId` 幂等，状态命令以 `commandId` 幂等，发出/收货以 `eventId` 幂等；所有写入均带 `correlationId`。API 不接受 `tenantId`、业务日、成本、库存前后量或调拨状态，业务日由可信门店时区和日切规则计算。Controller 只做协议适配，领域状态和数量规则位于 `TransferRules`/`TransferService`。`GET /{transferId}/transit-reconciliation` 只读重算在途流水并报告投影漂移，禁止自动回写修复。

## 3. 内部端口

- `AuthoritativeInventoryMovementPort`：调拨 Owner 只提交已落库的 `TRANSFER_DISPATCH/TRANSFER_RECEIPT` 权威事实，库存 Owner 生成 `TRANSFER_OUT/TRANSFER_IN`。
- `TransferCostSourcePort`：成本 Owner 通过发出行或收货行读取同租户、已过账、数量一致的调拨来源；端口不接收 `tenant_id` 或客户端成本。
- 成本 Owner 查询原发出 `inv_cost_ledger`，目的收货继承 `unit_cost_minor`；不存在、跨租户、数量超界或来源摘要冲突均失败关闭。

## 4. 事件

调拨 Outbox 发布 `inventory.transfer.created.v1`、`inventory.transfer.submitted.v1`、`inventory.transfer.approved.v1`、`inventory.transfer.dispatched.v1`、`inventory.transfer.received.v1`、`inventory.transfer.difference-approved.v1`、`inventory.transfer.cancelled.v1`。库存与成本 Owner 继续分别发布 `inventory.stock.changed.v1` 和 `inventory.cost.changed.v1`。

事件只含 ID、状态、两侧仓库、数量摘要、业务日、聚合版本和关联标识，不含租户密钥、商品全量、人员隐私或客户端成本。Outbox 与本命令业务事实同事务写入。

## 5. 促销设计准备

`contracts/t2/gate4d/schemas/promotion-design.v1.schema.json` 只冻结 `T2-PRM-001..003`：规则版本、候选集合、互斥组、稳定优先级、手工改价权限、整单折扣/抹零、成交快照、行优惠分摊金额守恒和退款按原快照还原。`x-runtime-allowed=false`，本 Sprint 不创建运行时。
