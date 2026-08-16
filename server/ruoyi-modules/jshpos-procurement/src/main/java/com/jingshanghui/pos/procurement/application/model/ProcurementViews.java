package com.jingshanghui.pos.procurement.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 采购查询投影；商业价格快照不参与库存成本计算。 */
public final class ProcurementViews {

    private ProcurementViews() {
    }

    public record Supplier(String supplierId, String code, String name, String status, long version) {
    }

    public record OrderHead(String orderId, String supplierId, Long storeId, String warehouseId,
                            LocalDate expectedDate, String status, int overReceiptToleranceBps,
                            Long creatorUserId, Long approverUserId, LocalDateTime approvedAt, long version) {
    }

    public record OrderLine(String orderLineId, String orderId, Long skuId, Long purchaseUnitId,
                            long conversionNumerator, long conversionDenominator,
                            BigDecimal orderedQuantity, BigDecimal receivedQuantity,
                            long unitPriceMinor, int taxRateBps) {
    }

    public record OrderDetail(OrderHead head, List<OrderLine> lines) {
        public OrderDetail { lines = List.copyOf(lines); }
    }

    public record ReceiptHead(String receiptId, String orderId, String sourceEventId, Long storeId,
                              String warehouseId, String status, String correlationId,
                              LocalDateTime confirmedAt, long version) {
    }

    public record ReceiptLine(String receiptLineId, String receiptId, String orderLineId,
                              Long skuId, Long baseUnitId, BigDecimal receivedQuantity,
                              BigDecimal baseQuantity, BigDecimal returnedQuantity,
                              long conversionNumerator, long conversionDenominator) {
    }

    public record ReceiptDetail(ReceiptHead head, List<ReceiptLine> lines) {
        public ReceiptDetail { lines = List.copyOf(lines); }
    }

    public record ReturnHead(String purchaseReturnId, String receiptId, String sourceEventId,
                             String status, String reason, Long requesterUserId, Long approverUserId,
                             LocalDateTime postedAt, long version) {
    }

    public record ReturnLine(String returnLineId, String purchaseReturnId, String receiptLineId,
                             Long skuId, Long baseUnitId, BigDecimal returnQuantity,
                             BigDecimal baseQuantity) {
    }
}
