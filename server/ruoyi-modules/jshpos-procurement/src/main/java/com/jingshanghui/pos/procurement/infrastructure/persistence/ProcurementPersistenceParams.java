package com.jingshanghui.pos.procurement.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 采购 Mapper 写入参数；tenantId 仅由可信应用服务填充。 */
public final class ProcurementPersistenceParams {

    private ProcurementPersistenceParams() {
    }

    public record SupplierWrite(String supplierId, String tenantId, String code, String name,
                                Long creatorUserId, LocalDateTime at) {
    }
    public record SupplierStateUpdate(String tenantId, String supplierId, String expectedState,
                                      String nextState, long expectedVersion, LocalDateTime at) {
    }
    public record OrderWrite(String orderId, String tenantId, String supplierId, Long storeId,
                             String warehouseId, LocalDate expectedDate, int toleranceBps,
                             String requestSha256, String correlationId, Long creatorUserId, LocalDateTime at) {
    }
    public record OrderLineWrite(String orderLineId, String tenantId, String orderId, Long skuId,
                                 Long purchaseUnitId, long numerator, long denominator,
                                 BigDecimal orderedQuantity, long unitPriceMinor, int taxRateBps,
                                 LocalDateTime at) {
    }
    public record OrderStatusUpdate(String tenantId, String orderId, String expectedState, String nextState,
                                    long expectedVersion, Long approverUserId, LocalDateTime approvedAt,
                                    LocalDateTime at) {
    }
    public record ReceiptWrite(String receiptId, String tenantId, String orderId,
                               Long storeId, String warehouseId, String correlationId,
                               Long actorUserId, LocalDateTime at) {
    }
    public record ReceiptLineWrite(String receiptLineId, String tenantId, String receiptId,
                                   String orderLineId, Long skuId, Long baseUnitId,
                                   BigDecimal receivedQuantity, BigDecimal baseQuantity,
                                   long numerator, long denominator,
                                   LocalDateTime at) {
    }
    public record OrderLineReceivedUpdate(String tenantId, String orderLineId,
                                          BigDecimal receivedQuantity, LocalDateTime at) {
    }
    public record ReceiptConfirm(String tenantId, String receiptId, String sourceEventId,
                                 long expectedVersion, LocalDateTime at) {
    }
    public record ReturnWrite(String purchaseReturnId, String tenantId, String receiptId,
                              String reason, String correlationId, Long requesterUserId, LocalDateTime at) {
    }
    public record ReturnLineWrite(String returnLineId, String tenantId, String purchaseReturnId,
                                  String receiptLineId, Long skuId, Long baseUnitId,
                                  BigDecimal returnQuantity, BigDecimal baseQuantity,
                                  LocalDateTime at) {
    }
    public record ReceiptLineReturnedUpdate(String tenantId, String receiptLineId,
                                            BigDecimal returnedQuantity, LocalDateTime at) {
    }
    public record ReturnStateUpdate(String tenantId, String purchaseReturnId, String expectedState,
                                    String nextState, long expectedVersion, LocalDateTime at) {
    }
    public record ReturnPost(String tenantId, String purchaseReturnId, String sourceEventId,
                             Long approverUserId, long expectedVersion, LocalDateTime at) {
    }
    public record AuditWrite(String auditId, String tenantId, Long storeId, String action,
                             String aggregateType, String aggregateId, Long actorUserId,
                             String commandId, String correlationId, String beforeValue,
                             String afterValue, String requestSha256, String reason,
                             LocalDateTime at) {
    }
    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime at) {
    }
}
