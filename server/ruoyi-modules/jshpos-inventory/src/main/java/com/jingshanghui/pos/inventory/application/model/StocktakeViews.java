package com.jingshanghui.pos.inventory.application.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 盘点头、行和命令结果投影，不向外暴露 tenant_id。 */
public final class StocktakeViews {

    private StocktakeViews() {
    }

    public record Head(String stocktakeId, Long storeId, String warehouseId, String status,
                       boolean blindCount, BigDecimal recountThreshold, String adjustmentEventId,
                       String correlationId, Long creatorUserId, Long reviewerUserId,
                       Long approverUserId, LocalDateTime snapshotAt, LocalDateTime cutoffAt,
                       LocalDateTime postedAt, long version) {
    }

    public record Line(String lineId, String stocktakeId, String dimensionKey, String warehouseId,
                       Long skuId, Long baseUnitId, BigDecimal snapshotQuantity, long snapshotLedgerSequence,
                       BigDecimal countedQuantity, BigDecimal adjustedBookQuantity, long cutoffLedgerSequence,
                       BigDecimal varianceQuantity, int countRevision, Long lastCounterUserId) {
    }

    public record Detail(Head head, List<Line> lines) {
        public Detail {
            lines = List.copyOf(lines);
        }
    }
}
