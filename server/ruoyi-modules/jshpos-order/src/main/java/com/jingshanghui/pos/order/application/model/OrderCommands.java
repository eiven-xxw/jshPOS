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

    /** POS 同步开班命令；shiftId 是本地事务已经冻结的业务身份，服务端不得重新生成。 */
    public record OpenSyncedShift(String commandId, String idempotencyKey, String shiftId, Long storeId,
                                  String terminalId, String cashierId, LocalDate businessDate,
                                  String storeTimezone, long openingCashMinor, long configVersion,
                                  Instant occurredAt) {
    }

    public record ApproveDifference(String commandId, String idempotencyKey, String shiftId,
                                    long actualCashMinor, long expectedVersion,
                                    String reasonCode, String reasonText, Instant occurredAt) {
    }

    public record CloseShift(String commandId, String idempotencyKey, String shiftId,
                             long actualCashMinor, long expectedVersion, String approvalId,
                             Instant occurredAt) {
    }

    /**
     * POS 同步关班命令；localExpectedVersion 是本地关班事实冻结前的班次版本。
     * 服务端可能已因已同步成交、退款等权威现金事实推进版本，不能把该本地版本
     * 直接当作服务端更新条件，但必须验证服务端版本没有落后于它。
     */
    public record CloseSyncedShift(String commandId, String idempotencyKey, String shiftId,
                                   long actualCashMinor, long localExpectedVersion, String approvalId,
                                   Instant occurredAt) {
    }

    /** 班次非销售现金动作；amountMinor 始终为正，方向由 movementType 决定。 */
    public record RecordCashMovement(String commandId, String idempotencyKey, String movementId,
                                     String shiftId, String movementType, long amountMinor,
                                     long expectedVersion, String reasonCode, String reasonText,
                                     String authorizationRef, Instant occurredAt) {
    }

    /** 非销售开钱箱请求；本需求不包含任何真实外设执行。 */
    public record RequestNoSaleDrawer(String commandId, String idempotencyKey, String drawerEventId,
                                      String shiftId, long expectedVersion, String reasonCode,
                                      String reasonText, String authorizationRef, Instant occurredAt) {
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
