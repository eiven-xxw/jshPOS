package com.jingshanghui.pos.inventory.application.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 库存模块查询投影与幂等命令结果。 */
public final class InventoryViews {

    private InventoryViews() {
    }

    /** 可由不可变流水重建的数量投影，所有数量固定六位小数。 */
    public record BalanceView(String dimensionKey, String warehouseId, Long skuId, String stockStatus,
                              BigDecimal onHandQuantity, BigDecimal reservedQuantity,
                              BigDecimal frozenQuantity, BigDecimal safetyStockQuantity,
                              long lastLedgerSequence, long recordVersion) {
        public BigDecimal availableQuantity() {
            return onHandQuantity.subtract(reservedQuantity).subtract(frozenQuantity).subtract(safetyStockQuantity);
        }
    }

    /** 已发布且不可变的门店仓负库存策略版本。 */
    public record PolicyView(String policyVersionId, Long storeId, String warehouseId,
                             String negativeStockMode, LocalDateTime effectiveFrom) {
    }

    /** 来源事件的持久化幂等结果。 */
    public record CommandView(String eventId, String requestSha256, String sourceType, String sourceId,
                              String status, int affectedLines, boolean negativeAlert,
                              LocalDateTime appliedAt) {
    }

    /** 某条库存流水的最小查询投影。 */
    public record LedgerView(String ledgerId, long ledgerSequence, String dimensionKey, String warehouseId,
                             Long skuId, String movementType, BigDecimal quantityBefore,
                             BigDecimal quantityDelta, BigDecimal quantityAfter,
                             String sourceType, String sourceId, String sourceLineId,
                             String policyVersionId, LocalDateTime occurredAt) {
    }

    public record ApplyResult(String eventId, String sourceType, String sourceId,
                              int affectedLines, boolean negativeAlert, boolean duplicate) {
    }

    public record RebuildResult(String dimensionKey, BigDecimal projectedQuantity,
                                BigDecimal ledgerQuantity, long ledgerCount,
                                boolean changed) {
    }

    /** 从不可变流水汇总出的受控重建输入。 */
    public record LedgerAggregate(BigDecimal ledgerQuantity, long ledgerCount, long lastLedgerSequence) {
    }
}
