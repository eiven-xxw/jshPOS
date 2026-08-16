package com.jingshanghui.pos.inventory.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 盘点持久化意图参数；tenantId 均由应用层可信上下文注入。 */
public final class StocktakePersistenceParams {

    private StocktakePersistenceParams() {
    }

    public record HeadWrite(String stocktakeId, String tenantId, Long storeId, String warehouseId,
                            boolean blindCount, BigDecimal recountThreshold, String correlationId,
                            Long creatorUserId, LocalDateTime snapshotAt, LocalDateTime createdAt) {
    }

    public record LineWrite(String lineId, String tenantId, String stocktakeId, String dimensionKey,
                            String warehouseId, Long skuId, Long baseUnitId, BigDecimal snapshotQuantity,
                            long snapshotLedgerSequence, LocalDateTime createdAt) {
    }

    public record CountWrite(String countId, String tenantId, String stocktakeId, String lineId,
                             int revisionNo, BigDecimal countedQuantity, Long counterUserId,
                             String deviceId, String reason, String correlationId, LocalDateTime countedAt) {
    }

    public record LineCountUpdate(String tenantId, String stocktakeId, String lineId,
                                  BigDecimal countedQuantity, int expectedRevision,
                                  Long counterUserId, LocalDateTime countedAt) {
    }

    public record LineCutoffUpdate(String tenantId, String stocktakeId, String lineId,
                                   BigDecimal adjustedBookQuantity, long cutoffLedgerSequence,
                                   BigDecimal varianceQuantity, LocalDateTime updatedAt) {
    }

    public record HeadStatusUpdate(String tenantId, String stocktakeId, String expectedStatus,
                                   String nextStatus, long expectedVersion, Long reviewerUserId,
                                   Long approverUserId, LocalDateTime cutoffAt,
                                   LocalDateTime postedAt, String adjustmentEventId,
                                   LocalDateTime updatedAt) {
    }

    public record AdjustmentWrite(String adjustmentId, String tenantId, String stocktakeId,
                                  String lineId, String sourceEventId, String movementType,
                                  BigDecimal quantity, BigDecimal signedVariance,
                                  LocalDateTime createdAt) {
    }
}
