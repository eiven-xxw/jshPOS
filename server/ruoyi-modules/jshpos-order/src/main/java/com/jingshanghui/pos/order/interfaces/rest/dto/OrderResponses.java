package com.jingshanghui.pos.order.interfaces.rest.dto;

import com.jingshanghui.pos.order.application.model.OrderViews.ApprovalView;
import com.jingshanghui.pos.order.application.model.OrderViews.CashMovementView;
import com.jingshanghui.pos.order.application.model.OrderViews.DrawerEventView;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** API boundary DTOs keep platform BIGINT identifiers as decimal strings. */
public final class OrderResponses {

    private OrderResponses() {
    }

    public static ShiftResult shift(ShiftView view, String traceId) {
        return new ShiftResult(view.shiftId(), view.status(), view.businessDate(), view.theoreticalCashMinor(),
            view.actualCashMinor(), view.differenceMinor(), view.recordVersion(), traceId);
    }

    public static ApprovalResult approval(ApprovalView view, String traceId) {
        return new ApprovalResult(view.approvalId(), view.shiftId(), view.approverUserId().toString(), view.status(),
            view.theoreticalCashMinor(), view.actualCashMinor(), view.differenceMinor(),
            view.expectedShiftVersion(), traceId);
    }

    public static CashMovementResult cashMovement(CashMovementView view, String traceId) {
        return new CashMovementResult(view.movementId(), view.shiftId(), view.movementType(),
            view.signedAmountMinor(), view.currency(), view.businessDate(), view.theoreticalCashMinor(),
            view.recordVersion(), traceId);
    }

    public static DrawerEventResult drawerEvent(DrawerEventView view, String traceId) {
        return new DrawerEventResult(view.drawerEventId(), view.shiftId(), view.eventType(),
            view.deviceExecutionStatus(), view.businessDate(), view.theoreticalCashMinor(),
            view.recordVersion(), traceId);
    }

    public static OrderDetails order(OrderView view) {
        return new OrderDetails(view.orderId(), view.localOrderNo(), view.storeId().toString(), view.terminalId(),
            view.shiftId(), view.cashierUserId().toString(), view.businessDate(), view.status(), view.paymentStatus(),
            view.currency(), view.grossAmountMinor(), view.receivableAmountMinor(), view.receivedAmountMinor(),
            "sha256:" + view.snapshotSha256(), view.snapshotJson(), view.recordVersion(), view.occurredAt());
    }

    public record ShiftResult(String shiftId, String status, LocalDate businessDate,
                              long theoreticalCashMinor, Long actualCashMinor, Long differenceMinor,
                              long recordVersion, String traceId) {
    }

    public record ApprovalResult(String approvalId, String shiftId, String approverUserId, String status,
                                 long theoreticalCashMinor, long actualCashMinor, long differenceMinor,
                                 long expectedShiftVersion, String traceId) {
    }

    public record CashMovementResult(String movementId, String shiftId, String movementType,
                                     long signedAmountMinor, String currency, LocalDate businessDate,
                                     long theoreticalCashMinor, long recordVersion, String traceId) {
    }

    public record DrawerEventResult(String drawerEventId, String shiftId, String eventType,
                                    String deviceExecutionStatus, LocalDate businessDate,
                                    long theoreticalCashMinor, long recordVersion, String traceId) {
    }

    public record OrderDetails(String orderId, String localOrderNo, String storeId, String terminalId,
                               String shiftId, String cashierUserId, LocalDate businessDate, String status,
                               String paymentStatus, String currency, long grossAmountMinor,
                               long receivableAmountMinor, long receivedAmountMinor, String snapshotHash,
                               String snapshotJson, long recordVersion, LocalDateTime occurredAt) {
    }
}
