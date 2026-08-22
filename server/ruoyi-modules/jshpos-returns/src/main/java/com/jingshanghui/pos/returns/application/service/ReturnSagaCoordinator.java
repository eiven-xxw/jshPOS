package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovementLine;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort.CashRefundCommand;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort.RefundCommand;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort.RefundLine;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort.RefundState;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationCommand;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationLine;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PaymentObservation;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ReturnHash;
import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 每次只投递一个跨 Owner 检查点的协调器。
 * Owner 成功而进程在 ACK 前终止时，下一次仍复用原 eventId；各 Owner 幂等返回原结果后继续收敛。
 */
@Service
@RequiredArgsConstructor
public class ReturnSagaCoordinator {

    private final ReturnOrchestrationService returns;
    private final ReturnPromotionAllocationPort promotions;
    private final CashRefundOwnerPort cashRefunds;
    private final ReturnPaymentRefundPort paymentRefunds;
    private final AuthoritativeInventoryMovementPort inventory;
    private final UlidGenerator ulids;
    private final Clock clock;

    /** 推进至下一个持久化检查点；不循环吞并多个 Owner 事务。 */
    public ReturnView processNext(String returnId) {
        ReturnView current = returns.find(returnId);
        Status status = Status.valueOf(current.status());
        Instant now = clock.instant();
        return switch (status) {
            case PROMOTION_PENDING -> allocatePromotion(current, now);
            case CASH_REFUND_PENDING -> refundCash(current, now);
            case PAYMENT_PENDING -> requestPaymentRefund(current, now);
            case PAYMENT_UNKNOWN -> queryPaymentRefund(current, now);
            case INVENTORY_PENDING -> receiveInventory(current, now);
            case PENDING_APPROVAL, COMPLETED, FAILED -> current;
        };
    }

    private ReturnView allocatePromotion(ReturnView current, Instant now) {
        var result = promotions.allocate(new AllocationCommand(current.promotionEventId(),
            current.promotionSnapshotId(), current.returnId(), current.lines().stream()
            .map(line -> new AllocationLine(line.orderLineId(), line.requestedQuantity())).toList(),
            current.correlationId()));
        return returns.acceptPromotion(current.promotionEventId(), result, now);
    }

    private ReturnView refundCash(ReturnView current, Instant now) {
        String requestHash = ReturnHash.sha256(ReturnHash.canonical(List.of(current.paymentEventId(),
            current.returnId(), current.orderId(), current.originalCashPaymentId(), current.refundShiftId(),
            current.storeId(), current.terminalId(), current.businessDate(), current.refundableAmountMinor(),
            current.correlationId())));
        var result = cashRefunds.refund(new CashRefundCommand(current.paymentEventId(), current.returnId(),
            current.orderId(), current.originalCashPaymentId(), current.refundShiftId(), current.storeId(),
            current.terminalId(), current.businessDate(), current.refundableAmountMinor(), requestHash,
            current.correlationId(), now));
        String payloadHash = ReturnHash.sha256(ReturnHash.canonical(List.of(result.cashRefundId(),
            result.refundId(), result.amountMinor(), result.status())));
        return returns.acceptCashRefund(current.paymentEventId(), current.returnId(), result.amountMinor(),
            result.status(), payloadHash, now);
    }

    private ReturnView requestPaymentRefund(ReturnView current, Instant now) {
        RefundState result = paymentRefunds.request(new RefundCommand(current.paymentEventId(),
            "return-refund-" + current.returnId(), current.returnId(), current.paymentId(), current.orderId(),
            current.refundableAmountMinor(), current.lines().stream().map(line -> new RefundLine(
            line.orderLineId(), line.requestedQuantity(), line.refundableAmountMinor())).toList(),
            current.reasonCode(), now));
        ReturnView acknowledged = returns.acknowledgePaymentRequest(current.paymentEventId(), result, now);
        return observeIfConverging(acknowledged, result, now);
    }

    private ReturnView queryPaymentRefund(ReturnView current, Instant now) {
        return observeIfConverging(current, paymentRefunds.find(current.returnId()), now);
    }

    private ReturnView observeIfConverging(ReturnView current, RefundState state, Instant now) {
        if ("PENDING_APPROVAL".equals(state.status()) || "PROCESSING".equals(state.status())) return current;
        String observationId = ulids.next();
        String hash = ReturnHash.sha256(ReturnHash.canonical(List.of(current.returnId(), state.paymentId(),
            state.status(), state.amountMinor(), state.currency())));
        return returns.observePayment(new PaymentObservation(observationId, current.returnId(), state.status(),
            state.amountMinor(), hash, now));
    }

    private ReturnView receiveInventory(ReturnView current, Instant now) {
        List<OwnedMovementLine> lines = new ArrayList<>();
        current.lines().forEach(line -> lines.add(new OwnedMovementLine(line.orderLineId(), line.skuId(),
            line.unitId(), line.requestedQuantity(), MovementType.SALE_RETURN_IN)));
        var result = inventory.applyOwnedMovement(new OwnedMovement(current.inventoryEventId(), "REFUND",
            current.returnId(), current.warehouseId(), current.storeId(), current.businessDate(),
            current.correlationId(), lines, current.orderId()));
        String payloadHash = ReturnHash.sha256(ReturnHash.canonical(List.of(result.eventId(), result.sourceType(),
            result.sourceId(), result.affectedLines(), result.negativeAlert())));
        return returns.acceptInventory(current.inventoryEventId(), result, payloadHash, now);
    }
}
