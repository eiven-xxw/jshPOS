package com.jingshanghui.pos.returns.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Return Owner 对外和协调器使用的只读视图。 */
public final class ReturnViews {
    private ReturnViews() { }

    /** 可恢复 Saga 当前检查点。 */
    public record ReturnView(String returnId, String orderId, Long storeId, String terminalId,
                             String refundShiftId, String warehouseId, LocalDate businessDate,
                             String settlementKind, String paymentId, String originalCashPaymentId,
                             String promotionSnapshotId, String promotionSnapshotSha256, String status,
                             Long grossAmountMinor, Long recoveredDiscountMinor, Long refundableAmountMinor,
                             String promotionEventId, String paymentEventId, String inventoryEventId,
                             Long requesterUserId, Long approverUserId, String reasonCode,
                             String correlationId, long recordVersion, List<ReturnLineView> lines,
                             LocalDateTime updatedAt, boolean duplicate) {
        public ReturnView { lines = List.copyOf(lines); }
    }

    /** 行级原事实、本次数量和 Promotion Owner 恢复结果。 */
    public record ReturnLineView(String returnLineId, String orderLineId, Long skuId, Long unitId,
                                 BigDecimal requestedQuantity, Long grossAmountMinor,
                                 Long recoveredDiscountMinor, Long refundableAmountMinor,
                                 BigDecimal cumulativeQuantity, Long cumulativePayableAmountMinor) { }
}
