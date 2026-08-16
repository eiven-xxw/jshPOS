package com.jingshanghui.pos.order.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class OrderCommands {

    private OrderCommands() {
    }

    public record OpenShift(String commandId, String idempotencyKey, Long storeId, String terminalId,
                            String cashierId,
                            LocalDate businessDate, String storeTimezone, long openingCashMinor,
                            long configVersion, Instant occurredAt) {
    }

    public record ApproveDifference(String commandId, String idempotencyKey, String shiftId,
                                    long actualCashMinor, long expectedVersion,
                                    String reasonCode, String reasonText, Instant occurredAt) {
    }

    public record CloseShift(String commandId, String idempotencyKey, String shiftId,
                             long actualCashMinor, long expectedVersion, String approvalId,
                             Instant occurredAt) {
    }

    public record CashOrder(String commandId, String idempotencyKey, String orderId, String localOrderNo,
                            Long storeId, String terminalId, String shiftId, String cashierId, LocalDate businessDate,
                            String storeTimezone, long catalogVersion, long priceVersion,
                            String industryTemplateVersion, long grossAmountMinor,
                            long receivableAmountMinor, long tenderedAmountMinor,
                            List<Line> lines, Instant occurredAt) {
        public CashOrder {
            lines = List.copyOf(lines);
        }
    }

    public record Line(String lineId, int lineNo, Long skuId, String skuCode, String barcode,
                       String productName, Long unitId, String unitCode, String quantity,
                       long unitPriceMinor, long grossAmountMinor, long payableAmountMinor,
                       String priceSource) {
    }
}
