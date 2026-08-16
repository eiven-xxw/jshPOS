package com.jingshanghui.pos.procurement.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购应用命令；tenant_id 和操作者由可信上下文注入。 */
public final class ProcurementCommands {

    private ProcurementCommands() {
    }

    public record CreateSupplier(String supplierId, String code, String name, String correlationId) {
    }

    public record ChangeSupplierState(String supplierId, String state, String reason, String correlationId) {
    }

    public record CreateOrder(String orderId, String supplierId, Long storeId, String warehouseId,
                              LocalDate expectedDate, int overReceiptToleranceBps,
                              List<OrderLine> lines, String correlationId) {
        public CreateOrder {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record OrderLine(String orderLineId, Long skuId, Long unitId, BigDecimal orderedQuantity,
                            long unitPriceMinor, int taxRateBps) {
    }

    public record ApproveOrder(String orderId, String correlationId) {
    }

    public record SubmitOrder(String orderId, String correlationId) {
    }

    public record CloseOrder(String orderId, String reason, String correlationId) {
    }

    public record CreateReceipt(String receiptId, String orderId,
                                List<ReceiptLine> lines, String correlationId) {
        public CreateReceipt {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record ConfirmReceipt(String receiptId, String eventId, String correlationId) {
    }

    public record ReceiptLine(String receiptLineId, String orderLineId, BigDecimal receivedQuantity) {
    }

    public record CreateReturn(String purchaseReturnId, String receiptId,
                               List<ReturnLine> lines, String reason, String correlationId) {
        public CreateReturn {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record SubmitReturn(String purchaseReturnId, String correlationId) {
    }

    public record ApproveReturn(String purchaseReturnId, String eventId, String correlationId) {
    }

    public record ReturnLine(String returnLineId, String receiptLineId, BigDecimal returnQuantity) {
    }
}
