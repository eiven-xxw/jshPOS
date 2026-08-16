package com.jingshanghui.pos.costing.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Costing Mapper 使用的具名不可变参数，避免数量、成本和同类型标识错位。 */
public final class CostingPersistenceParams {

    private CostingPersistenceParams() {
    }

    public record PolicyWrite(String policyVersionId, String tenantId, Long storeId,
                              String warehouseId, LocalDateTime effectiveFrom,
                              Long publisherUserId, LocalDateTime publishedAt) {
    }

    public record BalanceSeed(String tenantId, String costDimensionKey, String costScopeId,
                              String warehouseId, Long storeId, Long skuId,
                              String currencyCode, String policyVersionId,
                              LocalDateTime updatedAt) {
    }

    public record BalanceUpdate(String tenantId, String costDimensionKey,
                                BigDecimal costQuantity, BigDecimal costAmountMinor,
                                BigDecimal averageUnitCostMinor, BigDecimal lastUnitCostMinor,
                                long lastCostLedgerSequence, long lastInventoryLedgerSequence,
                                String policyVersionId, long expectedVersion,
                                LocalDateTime updatedAt) {
    }

    public record LedgerWrite(String costLedgerId, String tenantId, String costDimensionKey,
                              String costScopeId, long costLedgerSequence,
                              String inventoryLedgerId, long inventoryLedgerSequence,
                              String warehouseId, Long skuId, String currencyCode,
                              String movementType, BigDecimal quantityBefore,
                              BigDecimal quantityDelta, BigDecimal quantityAfter,
                              BigDecimal costAmountBeforeMinor, BigDecimal costAmountDeltaMinor,
                              BigDecimal costAmountAfterMinor, BigDecimal unitCostMinor,
                              BigDecimal averageUnitCostAfterMinor, String valuationMethod,
                              boolean costEstimated, BigDecimal varianceAmountMinor,
                              String sourceType, String sourceId, String sourceLineId,
                              String sourceEventId, String sourceSha256,
                              String policyVersionId, String reversalOfCostLedgerId,
                              LocalDate businessDate, Long actorUserId,
                              String correlationId, LocalDateTime occurredAt) {
    }

    public record AuditWrite(String auditId, String tenantId, Long storeId,
                             String actionCode, String aggregateType, String aggregateId,
                             Long actorUserId, String commandId, String correlationId,
                             String beforeValue, String afterValue, String requestSha256,
                             String reasonCode, LocalDateTime occurredAt) {
    }

    public record OutboxWrite(String eventId, String tenantId, String eventType,
                              String aggregateId, long aggregateVersion,
                              String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime availableAt) {
    }

    public record RebuildWrite(String rebuildId, String tenantId, String costDimensionKey,
                               String warehouseId, Long storeId, Long skuId, Long actorUserId,
                               String correlationId, BigDecimal previousQuantity,
                               BigDecimal rebuiltQuantity, BigDecimal previousAmountMinor,
                               BigDecimal rebuiltAmountMinor, long ledgerCount,
                               boolean changed, LocalDateTime completedAt) {
    }
}
