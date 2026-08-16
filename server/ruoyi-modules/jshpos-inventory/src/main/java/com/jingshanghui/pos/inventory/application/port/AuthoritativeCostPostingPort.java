package com.jingshanghui.pos.inventory.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存 Owner 在同一事务内提交已落库库存流水的成本处理端口。
 *
 * <p>端口故意不包含 tenant_id 和单位成本；租户由可信上下文提供，采购成本由采购 Owner 解析。</p>
 */
public interface AuthoritativeCostPostingPort {

    CostPostingResult applyPostedLedger(PostedInventoryLedger ledger);

    /** 已插入且不可变的库存流水事实；数量方向已经体现在 quantityDelta。 */
    record PostedInventoryLedger(String inventoryLedgerId, long inventoryLedgerSequence,
                                 String stockDimensionKey, String warehouseId, Long storeId,
                                 Long skuId, Long baseUnitId, String movementType,
                                 BigDecimal quantityBefore, BigDecimal quantityDelta,
                                 BigDecimal quantityAfter, String sourceType, String sourceId,
                                 String sourceLineId, String sourceEventId,
                                 String reversalOfInventoryLedgerId, LocalDate businessDate,
                                 String correlationId, LocalDateTime occurredAt) {
    }

    /** 成本处理结果；重复提交只返回原成本流水，不产生第二效果。 */
    record CostPostingResult(String inventoryLedgerId, String costLedgerId,
                             long costLedgerSequence, boolean duplicate,
                             boolean costEstimated, BigDecimal varianceAmountMinor) {
    }
}
