package com.jingshanghui.pos.procurement.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Replenishment Owner 请求 Procurement Owner 创建采购草稿的唯一写入端口。 */
public interface ReplenishmentPurchaseDraftPort {

    DraftResult createReplenishmentDraft(DraftCommand command);

    /** 所有值来自已冻结并复核的建议；实现仍须重新校验可信租户、门店和供应商。 */
    record DraftCommand(String purchaseOrderId, String suggestionId, String supplierId,
                        Long storeId, String warehouseId, LocalDate expectedDate,
                        String orderLineId, Long skuId, Long purchaseUnitId,
                        long conversionNumerator, long conversionDenominator,
                        BigDecimal orderedQuantity, long unitPriceMinor, int taxRateBps,
                        String correlationId) {
    }

    record DraftResult(String purchaseOrderId, String state, boolean duplicate) {
    }
}
