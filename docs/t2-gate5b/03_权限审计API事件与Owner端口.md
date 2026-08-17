# 权限、审计、API、事件与 Owner 端口

## 1. 可信上下文与权限

- `tenant_id/store_id/terminal_id/cashier_id/business_date` 来自已认证设备和服务端可信上下文，不接收客户端覆盖；
- 现金成交要求 `pos:sale:cash`，人工优惠还要求已验证且未过期的审批事实；
- 服务端消费要求可信设备身份、门店数据范围与事件签名/摘要；
- 退货申请、审批、退款观察、库存入账和人工修复使用不同权限，修复不能修改历史事实。

## 2. 审计

审计至少保存 `tenant/store/terminal/operator/approver/businessDate/correlationId/idempotencyKeyHash/before/after/reason/snapshotId/contentHash`。Secret、支付凭据、完整个人信息和原始 Token 不得进入日志或审计。

## 3. 版本化契约

- `order.completed.v2`：携带订单金额、促销快照身份/摘要/报价指纹/规则包版本；
- `order.promotion-bound.v1`：Order Owner 验证并落库后的不可变事实；
- `return.requested.v1`、`promotion.refund-allocated.v1`、`refund.observed.v1`、`inventory.sale-return-applied.v1`、`return.completed.v1`；
- 所有命令/事件包含稳定 `eventId/commandId/idempotencyKey/correlationId/aggregateVersion/schemaVersion`。

## 4. Owner 端口

端口只暴露领域意图与只读快照，不暴露 Mapper：

- `PromotionSnapshotQueryPort.requireForOrder(...)`；
- `PromotionRefundAllocationPort.allocateOriginalSnapshot(...)`；
- `ProviderNeutralRefundPort.submitOrObserve(...)`；
- `InventoryReturnPort.applySucceededReturn(...)`；
- `ReturnOrchestrationRepository` 管理 Saga、Inbox/Outbox 与步骤摘要。

同一进程也必须通过这些端口；禁止 import 其他模块 Mapper 或在 XML 中跨 Owner `UPDATE/DELETE`。
