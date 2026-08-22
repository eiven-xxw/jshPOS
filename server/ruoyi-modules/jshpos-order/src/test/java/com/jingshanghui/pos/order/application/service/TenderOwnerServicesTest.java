package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.port.TenderCashCollectionPort.CashTenderCommand;
import com.jingshanghui.pos.order.application.port.TenderOrderSettlementPort.OrderSettlementCommand;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.TenderCashMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PAY-004 通过 Order/Shift Owner 端口产生现金与订单完成事实的回归。 */
class TenderOwnerServicesTest {

    private static final String TENANT = "TENANT_A";
    private static final String PLAN = "01K2A000000000000000000091";
    private static final String ALLOCATION = "01K2A000000000000000000092";
    private static final String ORDER = "01K2A000000000000000000093";
    private static final String SHIFT = "01K2A000000000000000000094";
    private static final String TERMINAL = "01K2A000000000000000000095";
    private static final String COMMAND = "01K2A000000000000000000096";
    private static final String HASH = "a".repeat(64);
    private static final String PLAN_HASH = "b".repeat(64);
    private static final Instant AT = Instant.parse("2026-08-22T03:00:00Z");

    private final OrderMapper orders = mock(OrderMapper.class);
    private final TenderCashMapper cash = mock(TenderCashMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final OrderJournalService journal = mock(OrderJournalService.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 101L, 1001L, "cashier"));
        when(ulids.next()).thenReturn("01K2A000000000000000000099");
    }

    @Test
    void cashOwnerAppendsTenderLedgerShiftAuditAndOutbox() {
        when(orders.findOrder(TENANT, ORDER)).thenReturn(order("PENDING_PAYMENT", "UNPAID", 2));
        when(orders.lockShift(TENANT, SHIFT)).thenReturn(shift());
        when(orders.addShiftCash(TENANT, SHIFT, 299)).thenReturn(1);
        TenderCashCollectionService service = new TenderCashCollectionService(
            cash, orders, context, authorization, journal, ulids);

        var result = service.collect(cashCommand());

        assertThat(result.changeMinor()).isEqualTo(201);
        verify(cash).insertCashTender(eq(TENANT), anyString(), eq(PLAN), eq(ALLOCATION), eq(ORDER), eq(SHIFT),
            eq(1101L), eq(TERMINAL), eq(101L), eq(LocalDate.parse("2026-08-22")), eq(299L), eq(500L),
            eq(201L), eq(HASH), eq(COMMAND), any());
        verify(orders).addShiftCash(TENANT, SHIFT, 299);
        verify(journal).appendEvent(eq(TENANT), eq("order.command"), eq("cash.tender.received.v1"),
            eq("CASH_TENDER"), anyString(), eq(1L), eq(COMMAND), anyString(), any());
    }

    @Test
    void cashOwnerReturnsSameFactForExactDuplicateAndRejectsChangedContent() {
        TenderCashMapper.CashTenderRow existing = new TenderCashMapper.CashTenderRow(
            "01K2A000000000000000000097", ALLOCATION, ORDER, SHIFT, 299, 500, 201, HASH);
        when(cash.findByAllocation(TENANT, ALLOCATION)).thenReturn(existing);
        TenderCashCollectionService service = new TenderCashCollectionService(
            cash, orders, context, authorization, journal, ulids);
        assertThat(service.collect(cashCommand()).duplicate()).isTrue();
        verify(orders, never()).findOrder(anyString(), anyString());

        CashTenderCommand changed = new CashTenderCommand(PLAN, ALLOCATION, ORDER, 1101L, TERMINAL, SHIFT,
            LocalDate.parse("2026-08-22"), 299, 501, HASH, COMMAND, AT);
        assertThatThrownBy(() -> service.collect(changed)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("IDEMPOTENCY_CONTENT_MISMATCH");
    }

    @Test
    void fullyPaidPlanCompletesPendingOrderWithTwoExplicitTransitions() {
        when(orders.lockOrder(TENANT, ORDER)).thenReturn(order("PENDING_PAYMENT", "UNPAID", 2));
        when(orders.insertTenderSettlement(eq(TENANT), anyString(), eq(PLAN), eq(ORDER), eq(1_299L),
            eq(HASH), eq(PLAN_HASH), eq(COMMAND), eq(4L), any())).thenReturn(1);
        TenderOrderSettlementService service = new TenderOrderSettlementService(
            orders, context, authorization, journal, ulids);

        var result = service.complete(settlementCommand());

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.recordVersion()).isEqualTo(4);
        verify(orders, org.mockito.Mockito.times(2)).insertStateHistory(eq(TENANT), anyString(), eq(ORDER),
            eq(COMMAND), anyString(), anyString(), anyLong(), eq(101L), eq("TENDER_PLAN_PAID"), any());
        verify(journal).appendEvent(eq(TENANT), eq("order.command"), eq("order.tender-paid.v1"), eq("ORDER"),
            eq(ORDER), eq(4L), eq(COMMAND), anyString(), any());
    }

    @Test
    void completedOrderIsIdempotentButSnapshotTamperingFailsClosed() {
        TenderOrderSettlementService service = new TenderOrderSettlementService(
            orders, context, authorization, journal, ulids);
        when(orders.lockOrder(TENANT, ORDER)).thenReturn(order("COMPLETED", "PAID", 4));
        when(orders.findTenderSettlement(TENANT, ORDER)).thenReturn(new OrderMapper.TenderSettlementRow(
            "01K2A000000000000000000098", PLAN, ORDER, "COMPLETED", "PAID", 1_299,
            "CNY", HASH, PLAN_HASH, COMMAND, 4));
        assertThat(service.complete(settlementCommand()).duplicate()).isTrue();
        verify(orders, never()).insertTenderSettlement(anyString(), anyString(), anyString(), anyString(),
            anyLong(), anyString(), anyString(), anyString(), anyLong(), any());

        when(orders.lockOrder(TENANT, ORDER)).thenReturn(new OrderView(ORDER, "SYN-001", 1101L, TERMINAL,
            SHIFT, 101L, LocalDate.parse("2026-08-22"), "CONFIRMED", "UNPAID", "CNY", 1_299,
            1_299, 0, "f".repeat(64), "{}", 3, LocalDateTime.ofInstant(AT, ZoneOffset.UTC)));
        assertThatThrownBy(() -> service.complete(settlementCommand())).isInstanceOf(ServiceException.class)
            .hasMessageContaining("TENDER_ORDER_BINDING_CONFLICT");
    }

    private CashTenderCommand cashCommand() {
        return new CashTenderCommand(PLAN, ALLOCATION, ORDER, 1101L, TERMINAL, SHIFT,
            LocalDate.parse("2026-08-22"), 299, 500, HASH, COMMAND, AT);
    }

    private OrderSettlementCommand settlementCommand() {
        return new OrderSettlementCommand(PLAN, ORDER, 1101L, TERMINAL, HASH, PLAN_HASH,
            1_299, "CNY", COMMAND, AT);
    }

    private OrderView order(String status, String paymentStatus, long version) {
        return new OrderView(ORDER, "SYN-001", 1101L, TERMINAL, SHIFT, 101L,
            LocalDate.parse("2026-08-22"), status, paymentStatus, "CNY", 1_299,
            1_299, "PAID".equals(paymentStatus) ? 1_299 : 0, HASH, "{}", version,
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    private ShiftView shift() {
        return new ShiftView(SHIFT, 1101L, TERMINAL, 101L, "cashier", LocalDate.parse("2026-08-22"),
            "Asia/Shanghai", 1, "OPEN", "CNY", 0, 0, null, null, null, 1);
    }
}
