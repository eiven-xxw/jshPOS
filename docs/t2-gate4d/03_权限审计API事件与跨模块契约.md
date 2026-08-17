# Gate 4D 权限、审计、API、事件与跨模块契约

## 1. 权限和数据范围

| 权限 | 说明 | 数据范围 |
|---|---|---|
| `inventory:transfer:create` | 创建调拨草稿 | 来源和目的门店均可见 |
| `inventory:transfer:submit` | 提交草稿 | 来源门店可见 |
| `inventory:transfer:approve` | 审批 | 来源和目的门店可见；创建人不得自批 |
| `inventory:transfer:dispatch` | 发出 | 来源门店可见 |
| `inventory:transfer:receive` | 收货 | 目的门店可见 |
| `inventory:transfer:difference` | 差异审批 | 来源和目的门店均可见 |
| `inventory:transfer:cancel` | 发出前取消 | 来源门店可见 |
| `inventory:transfer:query` | 查询与对账 | 至少一侧门店可见，明细导出需双方范围 |

路由权限只控制展示，应用服务必须再次校验可信用户、租户、两侧门店范围和状态。审批人与创建人职责分离；差异处置必须记录原因、数量、操作者和关联标识。

## 2. API

OpenAPI 位于 `contracts/t2/gate4d/openapi-transfer-v1.yaml`。所有写命令携带 ULID `commandId` 和 `correlationId`，不接受 `tenantId`、成本、库存前后量或调拨状态。Controller 只做协议适配，领域状态和数量规则位于 `TransferRules`/`TransferService`。

## 3. 内部端口

- `AuthoritativeInventoryMovementPort`：调拨 Owner 只提交已落库的 `TRANSFER_DISPATCH/TRANSFER_RECEIPT` 权威事实，库存 Owner 生成 `TRANSFER_OUT/TRANSFER_IN`。
- `TransferCostSourcePort`：成本 Owner 通过发出行或收货行读取同租户、已过账、数量一致的调拨来源；端口不接收 `tenant_id` 或客户端成本。
- 成本 Owner 查询原发出 `inv_cost_ledger`，目的收货继承 `unit_cost_minor`；不存在、跨租户、数量超界或来源摘要冲突均失败关闭。

## 4. 事件

调拨 Outbox 发布 `inventory.transfer.created.v1`、`submitted.v1`、`approved.v1`、`dispatched.v1`、`received.v1`、`difference-opened.v1`、`difference-resolved.v1`、`cancelled.v1`。库存与成本 Owner 继续分别发布 `inventory.stock.changed.v1` 和 `inventory.cost.changed.v1`。

事件只含 ID、状态、两侧仓库、数量摘要、业务日、聚合版本和关联标识，不含租户密钥、商品全量、人员隐私或客户端成本。Outbox 与本命令业务事实同事务写入。

## 5. 促销设计准备

`contracts/t2/gate4d/schemas/promotion-design.v1.schema.json` 只冻结 `T2-PRM-001..003`：规则版本、候选集合、互斥组、稳定优先级、手工改价权限、整单折扣/抹零、成交快照、行优惠分摊金额守恒和退款按原快照还原。`x-runtime-allowed=false`，本 Sprint 不创建运行时。
