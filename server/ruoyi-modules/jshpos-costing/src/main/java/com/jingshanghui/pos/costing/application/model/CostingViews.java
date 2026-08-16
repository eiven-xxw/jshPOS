package com.jingshanghui.pos.costing.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 成本策略、余额、不可变流水和重建查询投影。 */
public final class CostingViews {

    private CostingViews() {
    }

    public record PolicyView(String policyVersionId, Long storeId, String warehouseId,
                             String scopeType, String currencyCode, int quantityScale,
                             int costScale, String roundingMode, String zeroQuantityMode,
                             LocalDateTime effectiveFrom) {
    }

    /** 可由成本流水重建的仓级 SKU 成本余额，金额以分为高精度单位。 */
    public record BalanceView(String costDimensionKey, String costScopeId, String warehouseId,
                              Long storeId, Long skuId, String currencyCode,
                              BigDecimal costQuantity, BigDecimal costAmountMinor,
                              BigDecimal averageUnitCostMinor, BigDecimal lastUnitCostMinor,
                              long lastCostLedgerSequence, long lastInventoryLedgerSequence,
                              long recordVersion) {
    }

    /** 不可变成本流水；保存出库时快照、差异和关联库存事实。 */
    public record LedgerView(String costLedgerId, long costLedgerSequence,
                             String inventoryLedgerId, long inventoryLedgerSequence,
                             String warehouseId, Long skuId, String currencyCode,
                             String movementType, BigDecimal quantityBefore,
                             BigDecimal quantityDelta, BigDecimal quantityAfter,
                             BigDecimal costAmountBeforeMinor, BigDecimal costAmountDeltaMinor,
                             BigDecimal costAmountAfterMinor, BigDecimal unitCostMinor,
                             BigDecimal averageUnitCostAfterMinor, String valuationMethod,
                             boolean costEstimated, BigDecimal varianceAmountMinor,
                             String sourceType, String sourceId, String sourceLineId,
                             String sourceSha256, String policyVersionId,
                             String reversalOfCostLedgerId, LocalDate businessDate,
                             LocalDateTime occurredAt) {
    }

    public record LedgerAggregate(BigDecimal quantity, BigDecimal amountMinor,
                                  long ledgerCount, long lastCostLedgerSequence,
                                  long lastInventoryLedgerSequence,
                                  BigDecimal lastUnitCostMinor) {
    }

    public record RebuildResult(String rebuildId, String costDimensionKey,
                                BigDecimal previousQuantity, BigDecimal ledgerQuantity,
                                BigDecimal previousAmountMinor, BigDecimal ledgerAmountMinor,
                                long ledgerCount, boolean changed) {
    }
}
