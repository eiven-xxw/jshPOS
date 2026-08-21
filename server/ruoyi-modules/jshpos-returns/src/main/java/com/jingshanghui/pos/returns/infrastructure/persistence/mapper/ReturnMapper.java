package com.jingshanghui.pos.returns.infrastructure.persistence.mapper;

import com.jingshanghui.pos.returns.infrastructure.persistence.ReturnPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 退货 Saga 锁、累计聚合、状态条件更新和只追加事实 XML Mapper。 */
public interface ReturnMapper {
    int insertOrderGuard(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    String lockOrderGuard(@Param("tenantId") String tenantId, @Param("orderId") String orderId);
    IdempotencyRow findIdempotency(@Param("tenantId") String tenantId,
                                   @Param("commandType") String commandType,
                                   @Param("idempotencyKey") String idempotencyKey);
    int insertIdempotency(IdempotencyWrite value);
    int insertReturn(ReturnWrite value);
    int insertLine(LineWrite value);
    ReturnRow findReturn(@Param("tenantId") String tenantId, @Param("returnId") String returnId);
    ReturnRow lockReturn(@Param("tenantId") String tenantId, @Param("returnId") String returnId);
    List<LineRow> listLines(@Param("tenantId") String tenantId, @Param("returnId") String returnId);
    List<ReservedQuantityRow> sumReservedQuantities(@Param("tenantId") String tenantId,
                                                    @Param("orderId") String orderId);
    long sumReservedRefundAmount(@Param("tenantId") String tenantId,
                                 @Param("orderId") String orderId);
    int approve(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                @Param("expectedVersion") long expectedVersion, @Param("approverUserId") Long approverUserId,
                @Param("promotionEventId") String promotionEventId, @Param("occurredAt") LocalDateTime occurredAt);
    int applyPromotionHeader(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("nextStatus") String nextStatus,
                             @Param("grossAmountMinor") long grossAmountMinor,
                             @Param("recoveredDiscountMinor") long recoveredDiscountMinor,
                             @Param("refundableAmountMinor") long refundableAmountMinor,
                             @Param("paymentEventId") String paymentEventId,
                             @Param("inventoryEventId") String inventoryEventId,
                             @Param("occurredAt") LocalDateTime occurredAt);
    int updateAllocation(AllocationUpdate value);
    int advancePayment(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                       @Param("expectedVersion") long expectedVersion, @Param("expectedStatus") String expectedStatus,
                       @Param("nextStatus") String nextStatus, @Param("inventoryEventId") String inventoryEventId,
                       @Param("occurredAt") LocalDateTime occurredAt);
    int completeInventory(@Param("tenantId") String tenantId, @Param("returnId") String returnId,
                          @Param("expectedVersion") long expectedVersion,
                          @Param("occurredAt") LocalDateTime occurredAt);
    InboxRow findInbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
    int insertInbox(InboxWrite value);
    int insertHistory(HistoryWrite value);
    int insertOutbox(OutboxWrite value);
    int markOutboxDelivered(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                            @Param("deliveredAt") LocalDateTime deliveredAt);

    /** Saga 头持久化投影。 */
    record ReturnRow(String returnId, String requestSha256, String orderId, Long storeId, String terminalId,
                     String refundShiftId, String warehouseId, java.time.LocalDate businessDate,
                     String settlementKind, String paymentId, String originalCashPaymentId,
                     String promotionSnapshotId, String promotionSnapshotSha256, String status,
                     Long grossAmountMinor, Long recoveredDiscountMinor, Long refundableAmountMinor,
                     String promotionEventId, String paymentEventId, String inventoryEventId,
                     Long requesterUserId, Long approverUserId, String reasonCode, String correlationId,
                     long recordVersion, LocalDateTime updatedAt) { }

    /** Saga 行持久化投影。 */
    record LineRow(String returnLineId, String orderLineId, Long skuId, Long unitId,
                   BigDecimal requestedQuantity, Long grossAmountMinor, Long recoveredDiscountMinor,
                   Long refundableAmountMinor, BigDecimal cumulativeQuantity,
                   Long cumulativePayableAmountMinor) { }

    /** 同原订单行的已保留/完成退货数量。 */
    record ReservedQuantityRow(String orderLineId, BigDecimal reservedQuantity) { }

    /** Inbox 幂等投影。 */
    record InboxRow(String eventId, String ownerCode, String aggregateId, String payloadSha256) { }

    /** 申请命令幂等投影。 */
    record IdempotencyRow(String requestSha256, String aggregateId) { }
}
