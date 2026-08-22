package com.jingshanghui.pos.inventory.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** MyBatis XML 使用的批次库存写入参数。 */
public final class LotInventoryPersistenceParams {
    private LotInventoryPersistenceParams() { }

    public record CommandWrite(String eventId, String tenantId, String requestSha256, String sourceType,
                               String sourceId, String warehouseId, Long storeId, String correlationId,
                               Long actorUserId, LocalDateTime createdAt) { }
    public record CommandApplied(String tenantId, String eventId, int affectedLines, LocalDateTime appliedAt) { }
    public record IdentityWrite(String lotId, String tenantId, Long storeId, String warehouseId, Long skuId,
                                Long baseUnitId, String supplierLotCode, String internalLotCode,
                                LocalDate productionDate, LocalDate receivedDate, LocalDate expiryDate,
                                String policyVersionId, int nearExpiryDays, String contentSha256,
                                LocalDateTime createdAt) { }
    public record BalanceSeed(String tenantId, String lotId, LocalDateTime updatedAt) { }
    public record BalanceUpdate(String tenantId, String lotId, BigDecimal onHandQuantity,
                                long lastLedgerSequence, long expectedVersion, LocalDateTime updatedAt) { }
    public record BalanceRebuild(String tenantId, String lotId, BigDecimal onHandQuantity,
                                 long lastLedgerSequence, LocalDateTime updatedAt) { }
    public record LedgerWrite(String ledgerId, String tenantId, String lotId, long ledgerSequence,
                              BigDecimal quantityBefore, BigDecimal quantityDelta, BigDecimal quantityAfter,
                              String movementType, String sourceType, String sourceId, String sourceLineId,
                              String sourceEventId, LocalDate businessDate, Long actorUserId,
                              String correlationId, LocalDateTime occurredAt) { }
    public record AllocationWrite(String allocationId, String tenantId, String allocationType, String sourceId,
                                  String sourceLineId, String originalSourceId, String originalSourceLineId,
                                  String lotId, Long skuId, BigDecimal quantity, String policyVersionId,
                                  LocalDate expiryDate, String sourceEventId, LocalDateTime createdAt) { }
    public record ExpiryProjectionWrite(String tenantId, String lotId, String expiryStatus,
                                        LocalDate asOfBusinessDate, int nearExpiryDays,
                                        BigDecimal onHandQuantity, long lastLedgerSequence,
                                        LocalDateTime updatedAt) { }
    public record AuditWrite(String auditId, String tenantId, Long storeId, String actionCode,
                             String aggregateId, Long actorUserId, String commandId, String correlationId,
                             String requestSha256, String reasonCode, LocalDateTime occurredAt) { }
    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime availableAt) { }
    public record LotPackageWrite(String releaseId, String tenantId, Long storeId, String warehouseId,
                                  long packageVersion, long previousVersion, String sourceSha256,
                                  String payloadSha256, byte[] payloadBytes, String signingKeyId,
                                  byte[] signatureBytes, int recordCount, LocalDateTime generatedAt) { }
}
