package com.jingshanghui.pos.order.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class OrderViews {

    private OrderViews() {
    }

    public record ShiftView(String shiftId, Long storeId, String terminalId, Long cashierUserId,
                            String cashierNameSnapshot, LocalDate businessDate, String storeTimezone,
                            long configVersion, String status, String currency, long openingCashMinor,
                            long theoreticalCashMinor, Long actualCashMinor, Long differenceMinor,
                            String approvalId, long recordVersion) {
    }

    public record IdempotencyView(String commandType, String commandId, String idempotencyKey,
                                  String requestSha256, String aggregateId, String resultCode,
                                  String resultJson) {
    }

    public record CashOrderResult(String orderId, String paymentId, String status, String paymentStatus,
                                  long receivableAmountMinor, long tenderedAmountMinor,
                                  long changeAmountMinor, String currency, String snapshotHash,
                                  long recordVersion, String traceId, boolean duplicate) {
    }

    public record OrderView(String orderId, String localOrderNo, Long storeId, String terminalId,
                            String shiftId, Long cashierUserId, LocalDate businessDate, String status,
                            String paymentStatus, String currency, long grossAmountMinor,
                            long receivableAmountMinor, long receivedAmountMinor, String snapshotSha256,
                            String snapshotJson, long recordVersion, LocalDateTime occurredAt) {
    }

    public record ApprovalView(String approvalId, String shiftId, Long approverUserId, String status,
                               long theoreticalCashMinor, long actualCashMinor, long differenceMinor,
                               long expectedShiftVersion) {
    }

    /** 班次现金动作的冻结结果。 */
    public record CashMovementView(String movementId, String shiftId, String movementType,
                                   long signedAmountMinor, String currency, LocalDate businessDate,
                                   long theoreticalCashMinor, long recordVersion) {
    }

    /** 钱箱请求只记录事实，设备执行状态固定为 BLOCKED_EXTERNAL。 */
    public record DrawerEventView(String drawerEventId, String shiftId, String eventType,
                                  String deviceExecutionStatus, LocalDate businessDate,
                                  long theoreticalCashMinor, long recordVersion) {
    }

    /** 支付领域只读端口使用的原订单行快照。 */
    public record PaymentLineView(String lineId, BigDecimal quantity, long payableAmountMinor) {
    }

    /** 库存领域只读端口使用的成交 SKU、基础单位和精确数量快照。 */
    public record InventoryLineView(String lineId, Long skuId, Long unitId, BigDecimal quantity) {
    }
}
