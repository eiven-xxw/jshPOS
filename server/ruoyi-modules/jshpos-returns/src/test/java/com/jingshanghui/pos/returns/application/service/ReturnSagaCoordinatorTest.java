package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort.CashRefundResult;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort.RefundState;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocatedLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationResult;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PaymentObservation;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnLineView;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2-REF-002 Owner 失败、ACK 丢失、UNKNOWN 与重放固定向量。 */
class ReturnSagaCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");
    private static final String RETURN = "01K5R000000000000000000001";
    private static final String ORDER = "01K5N000000000000000000001";
    private static final String EVENT = "01K5E000000000000000000001";
    private static final String INVENTORY_EVENT = "01K5V000000000000000000001";

    private final ReturnOrchestrationService returns = mock(ReturnOrchestrationService.class);
    private final ReturnPromotionAllocationPort promotions = mock(ReturnPromotionAllocationPort.class);
    private final CashRefundOwnerPort cash = mock(CashRefundOwnerPort.class);
    private final ReturnPaymentRefundPort payments = mock(ReturnPaymentRefundPort.class);
    private final AuthoritativeInventoryMovementPort inventory = mock(AuthoritativeInventoryMovementPort.class);
    private final ReturnSagaCoordinator coordinator = new ReturnSagaCoordinator(returns, promotions, cash,
        payments, inventory, new UlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void allocatesOriginalPromotionSnapshotUsingStableEvent() {
        ReturnView current = view(Status.PROMOTION_PENDING, "CASH", EVENT, null, null);
        AllocationResult allocated = new AllocationResult(RETURN, "01K5S000000000000000000001",
            1000, 100, 900, List.of(new AllocatedLine("01K5A000000000000000000001",
            BigDecimal.ONE, 1000, 100, 900, BigDecimal.ONE, 900)));
        when(returns.find(RETURN)).thenReturn(current);
        when(promotions.allocate(any())).thenReturn(allocated);
        when(returns.acceptPromotion(eq(EVENT), eq(allocated), eq(NOW))).thenReturn(current);

        coordinator.processNext(RETURN);

        var command = ArgumentCaptor.forClass(ReturnPromotionAllocationPort.AllocationCommand.class);
        verify(promotions).allocate(command.capture());
        assertThat(command.getValue().eventId()).isEqualTo(EVENT);
        assertThat(command.getValue().refundId()).isEqualTo(RETURN);
    }

    @Test
    void retriesOwnerFailureWithSameCashBusinessCommand() {
        ReturnView current = view(Status.CASH_REFUND_PENDING, "CASH", null, EVENT, null);
        when(returns.find(RETURN)).thenReturn(current);
        when(cash.refund(any())).thenThrow(new IllegalStateException("synthetic owner crash"))
            .thenReturn(new CashRefundResult(EVENT, RETURN, 900, "SUCCEEDED", true));
        when(returns.acceptCashRefund(eq(EVENT), eq(RETURN), eq(900L), eq("SUCCEEDED"), any(), eq(NOW)))
            .thenReturn(view(Status.INVENTORY_PENDING, "CASH", null, EVENT, INVENTORY_EVENT));

        assertThatThrownBy(() -> coordinator.processNext(RETURN)).hasMessageContaining("synthetic owner crash");
        coordinator.processNext(RETURN);

        var commands = ArgumentCaptor.forClass(CashRefundOwnerPort.CashRefundCommand.class);
        verify(cash, times(2)).refund(commands.capture());
        assertThat(commands.getAllValues()).extracting(CashRefundOwnerPort.CashRefundCommand::eventId)
            .containsOnly(EVENT);
        assertThat(commands.getAllValues()).extracting(CashRefundOwnerPort.CashRefundCommand::refundId)
            .containsOnly(RETURN);
    }

    @Test
    void unknownPaymentQueriesExistingRefundAndNeverRegeneratesCommand() {
        ReturnView current = view(Status.PAYMENT_UNKNOWN, "PROVIDER_NEUTRAL", null, EVENT, null);
        RefundState unknown = new RefundState(RETURN, "01K5P000000000000000000001", "UNKNOWN", 900,
            "CNY", true);
        when(returns.find(RETURN)).thenReturn(current);
        when(payments.find(RETURN)).thenReturn(unknown);
        when(returns.observePayment(any())).thenReturn(current);

        coordinator.processNext(RETURN);

        verify(payments).find(RETURN);
        verify(payments, never()).request(any());
        var observation = ArgumentCaptor.forClass(PaymentObservation.class);
        verify(returns).observePayment(observation.capture());
        assertThat(observation.getValue().returnId()).isEqualTo(RETURN);
        assertThat(observation.getValue().paymentStatus()).isEqualTo("UNKNOWN");
    }

    @Test
    void emitsOnlyRefundOwnedInventoryMovement() {
        ReturnView current = view(Status.INVENTORY_PENDING, "CASH", null, EVENT, INVENTORY_EVENT);
        ApplyResult applied = new ApplyResult(INVENTORY_EVENT, "REFUND", RETURN, 1, false, false);
        when(returns.find(RETURN)).thenReturn(current);
        when(inventory.applyOwnedMovement(any())).thenReturn(applied);
        when(returns.acceptInventory(eq(INVENTORY_EVENT), eq(applied), any(), eq(NOW))).thenReturn(current);

        coordinator.processNext(RETURN);

        var movement = ArgumentCaptor.forClass(AuthoritativeInventoryMovementPort.OwnedMovement.class);
        verify(inventory).applyOwnedMovement(movement.capture());
        assertThat(movement.getValue().sourceType()).isEqualTo("REFUND");
        assertThat(movement.getValue().eventId()).isEqualTo(INVENTORY_EVENT);
        assertThat(movement.getValue().lines().get(0).movementType().name()).isEqualTo("SALE_RETURN_IN");
    }

    @Test
    void terminalAndApprovalStatesDoNotCallAnyOwner() {
        for (Status state : List.of(Status.PENDING_APPROVAL, Status.COMPLETED, Status.FAILED)) {
            when(returns.find(RETURN)).thenReturn(view(state, "CASH", null, null, null));
            assertThat(coordinator.processNext(RETURN).status()).isEqualTo(state.name());
        }
        verify(promotions, never()).allocate(any());
        verify(cash, never()).refund(any());
        verify(payments, never()).request(any());
        verify(inventory, never()).applyOwnedMovement(any());
    }

    private ReturnView view(Status status, String kind, String promotionEvent, String paymentEvent,
                            String inventoryEvent) {
        var line = new ReturnLineView("01K5X000000000000000000001", "01K5A000000000000000000001",
            701L, 301L, BigDecimal.ONE, 1000L, 100L, 900L, BigDecimal.ONE, 900L);
        return new ReturnView(RETURN, ORDER, 1101L, "01K5T000000000000000000001",
            "01K5H000000000000000000001", "01K5W000000000000000000001",
            LocalDate.parse("2026-08-17"), kind,
            "PROVIDER_NEUTRAL".equals(kind) ? "01K5P000000000000000000001" : null,
            "CASH".equals(kind) ? "01K5C000000000000000000001" : null,
            "01K5S000000000000000000001", "a".repeat(64), status.name(), 1000L, 100L, 900L,
            promotionEvent, paymentEvent, inventoryEvent, 101L, 102L, "CUSTOMER_RETURN",
            "01K5Z000000000000000000001", 3, List.of(line), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), false);
    }
}
