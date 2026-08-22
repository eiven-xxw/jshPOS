package com.jingshanghui.pos.inventory.application.model;

import java.math.BigDecimal;
import java.util.List;

/** 盘点应用命令；tenant_id 与操作者只来自可信上下文。 */
public final class StocktakeCommands {

    private StocktakeCommands() {
    }

    public record Create(String stocktakeId, String warehouseId, List<Long> skuIds,
                         boolean blindCount, BigDecimal recountThreshold, String correlationId) {
        public Create {
            skuIds = skuIds == null ? List.of() : List.copyOf(skuIds);
        }
    }

    public record RecordCount(String stocktakeId, String lineId, String countId,
                              BigDecimal countedQuantity, String deviceId,
                              String reason, String correlationId) {
    }

    public record Submit(String stocktakeId, String correlationId) {
    }

    public record Review(String stocktakeId, String decision, String reason, String correlationId) {
    }

    public record Approve(String stocktakeId, String eventId, String correlationId,
                          List<LotAdjustment> lotAdjustments) {
        public Approve {
            lotAdjustments = lotAdjustments == null ? List.of() : List.copyOf(lotAdjustments);
        }

        public Approve(String stocktakeId, String eventId, String correlationId) {
            this(stocktakeId, eventId, correlationId, List.of());
        }
    }

    /** 盘点差异的批次拆分，方向必须与盘点行冻结差异一致。 */
    public record LotAdjustment(String stocktakeLineId, String lotId, BigDecimal baseQuantity,
                                String movementType) { }
}
