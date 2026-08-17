package com.jingshanghui.pos.transfer.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 调拨 Mapper 写入参数；tenant_id 在应用服务中从可信上下文补齐。 */
public final class TransferPersistenceParams {
    private TransferPersistenceParams() { }

    public record OrderWrite(String transferId, String tenantId, Long sourceStoreId, String sourceWarehouseId,
                             Long destinationStoreId, String destinationWarehouseId, String requestSha256,
                             String reason, String correlationId, Long creatorUserId, LocalDateTime createdAt) { }
    public record LineWrite(String transferLineId, String tenantId, String transferId, Long skuId,
                            Long requestedUnitId, long conversionNumerator, long conversionDenominator,
                            BigDecimal inputQuantity, Long baseUnitId, BigDecimal requestedQuantity,
                            LocalDateTime createdAt) { }
    public record StatusUpdate(String tenantId, String transferId, String expectedStatus, String nextStatus,
                               long expectedVersion, Long approverUserId, LocalDateTime approvedAt,
                               LocalDateTime dispatchedAt, LocalDateTime closedAt, LocalDateTime updatedAt) { }
    public record CommandWrite(String commandId, String tenantId, String transferId, String commandType,
                               String requestSha256, String status, LocalDateTime createdAt) { }
    public record CommandApplied(String tenantId, String commandId, String status, LocalDateTime appliedAt) { }
    public record DispatchWrite(String dispatchId, String tenantId, String transferId, String sourceEventId,
                                LocalDate businessDate, String correlationId, LocalDateTime postedAt) { }
    public record DispatchLineWrite(String dispatchLineId, String tenantId, String dispatchId,
                                    String transferLineId, Long skuId, Long baseUnitId,
                                    BigDecimal baseQuantity, LocalDateTime createdAt) { }
    public record ReceiptWrite(String receiptId, String tenantId, String transferId, String sourceEventId,
                               boolean finalReceipt, LocalDate businessDate, String correlationId,
                               LocalDateTime postedAt) { }
    public record ReceiptLineWrite(String receiptLineId, String tenantId, String receiptId,
                                   String transferLineId, String dispatchLineId, Long skuId,
                                   Long baseUnitId, BigDecimal baseQuantity, LocalDateTime createdAt) { }
    public record LineProgress(String tenantId, String transferLineId, BigDecimal dispatchedDelta,
                               BigDecimal receivedDelta, BigDecimal differenceDelta, LocalDateTime updatedAt) { }
    public record TransitWrite(String transitLedgerId, String tenantId, String transferId,
                               String transferLineId, String factType, String sourceFactId,
                               BigDecimal quantity, String reasonCode, LocalDate businessDate,
                               String correlationId, LocalDateTime occurredAt) { }
    public record AuditWrite(String auditId, String tenantId, Long storeId, String action,
                             String aggregateType, String aggregateId, Long actorUserId, String commandId,
                             String correlationId, String beforeState, String afterState,
                             String contentSha256, String reason, LocalDateTime createdAt) { }
    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime createdAt) { }
}
