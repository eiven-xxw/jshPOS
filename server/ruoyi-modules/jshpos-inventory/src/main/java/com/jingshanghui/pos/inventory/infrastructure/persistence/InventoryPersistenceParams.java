package com.jingshanghui.pos.inventory.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Mapper 写入参数对象。
 *
 * <p>每个 record 对应单一持久化意图，避免多同类型参数错位；tenantId 只由应用层可信上下文填充。</p>
 */
public final class InventoryPersistenceParams {

    private InventoryPersistenceParams() {
    }

    public record PolicyWrite(String policyVersionId, String tenantId, Long storeId, String warehouseId,
                              String negativeStockMode, LocalDateTime effectiveFrom,
                              Long publisherUserId, LocalDateTime publishedAt) {
    }

    public record CommandWrite(String eventId, String tenantId, String requestSha256, String sourceType,
                               String sourceId, String warehouseId, Long storeId, String correlationId,
                               Long actorUserId, LocalDateTime createdAt) {
    }

    public record BalanceSeed(String tenantId, String dimensionKey, String warehouseId, Long skuId) {
    }

    public record BalanceUpdate(String tenantId, String dimensionKey, BigDecimal onHandQuantity,
                                long lastLedgerSequence, long expectedVersion, LocalDateTime updatedAt) {
    }

    public record LedgerWrite(String ledgerId, String tenantId, String dimensionKey, long ledgerSequence,
                              String warehouseId, Long skuId, Long baseUnitId, String stockStatus,
                              String movementType, BigDecimal quantityBefore, BigDecimal quantityDelta,
                              BigDecimal quantityAfter, String sourceType, String sourceId,
                              String sourceLineId, String sourceEventId, String policyVersionId,
                              LocalDate businessDate, Long actorUserId, String correlationId,
                              LocalDateTime occurredAt) {
    }

    public record JournalWrite(String id, String tenantId, Long storeId, String actionCode,
                               String aggregateType, String aggregateId, Long actorUserId,
                               String commandId, String correlationId, String beforeValue,
                               String afterValue, String requestSha256, String reasonCode,
                               LocalDateTime occurredAt) {
    }

    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime availableAt) {
    }

    public record AnomalyWrite(String anomalyId, String tenantId, Long storeId, String warehouseId,
                               Long skuId, String anomalyType, BigDecimal observedQuantity,
                               String policyVersionId, String sourceEventId,
                               LocalDateTime occurredAt) {
    }

    public record CommandApplied(String tenantId, String eventId, int affectedLines,
                                 boolean negativeAlert, LocalDateTime appliedAt) {
    }

    public record RebuildUpdate(String tenantId, String dimensionKey, BigDecimal ledgerQuantity,
                                long ledgerSequence, long expectedVersion, LocalDateTime updatedAt) {
    }
}
