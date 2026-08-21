package com.jingshanghui.pos.returns.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Return Owner XML Mapper 的具名不可变参数对象。 */
public final class ReturnPersistenceParams {
    private ReturnPersistenceParams() { }

    /** 退货申请头写入。 */
    public record ReturnWrite(String returnId, String tenantId, String idempotencyKey, String requestSha256,
                              String requestCommandId,
                              String orderId, Long storeId, String terminalId, String refundShiftId,
                              String warehouseId, LocalDate businessDate, String settlementKind,
                              String paymentId, String originalCashPaymentId, String promotionSnapshotId,
                              String promotionSnapshotSha256, String reasonCode, Long requesterUserId,
                              String correlationId, LocalDateTime occurredAt) { }

    /** 退货行原始数量写入。 */
    public record LineWrite(String returnLineId, String tenantId, String returnId, String orderLineId,
                            Long skuId, Long unitId, BigDecimal requestedQuantity) { }

    /** Promotion Owner 行分摊回写到 Saga 检查点。 */
    public record AllocationUpdate(String tenantId, String returnId, String orderLineId,
                                   long grossAmountMinor, long recoveredDiscountMinor,
                                   long refundableAmountMinor, BigDecimal cumulativeQuantity,
                                   long cumulativePayableAmountMinor) { }

    /** 只追加状态历史。 */
    public record HistoryWrite(String historyId, String tenantId, String returnId, String eventId,
                               String fromStatus, String toStatus, long aggregateVersion,
                               Long actorUserId, String reasonCode, LocalDateTime occurredAt) { }

    /** Owner 结果 Inbox，eventId 与摘要共同保证至少一次消费只有一次效果。 */
    public record InboxWrite(String eventId, String tenantId, String ownerCode, String aggregateId,
                             String payloadSha256, LocalDateTime receivedAt) { }

    /** 待投递跨 Owner 事件。 */
    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime availableAt) { }

    /** 申请命令幂等绑定。 */
    public record IdempotencyWrite(String tenantId, String commandType, String idempotencyKey,
                                   String requestSha256, String aggregateId, LocalDateTime createdAt) { }
}
