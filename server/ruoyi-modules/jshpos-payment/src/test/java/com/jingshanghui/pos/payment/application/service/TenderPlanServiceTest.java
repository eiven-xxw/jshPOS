package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.application.port.TenderCashCollectionPort;
import com.jingshanghui.pos.order.application.port.TenderOrderSettlementPort;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CollectTenderAllocation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CancelTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RecoverTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TenderAllocationInput;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderAllocationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderCollectResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderPlanResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderPlanView;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2-PAY-004 正式应用层事务、幂等、外部失败关闭与最终订单完成回归。 */
class TenderPlanServiceTest {

    private static final String TENANT = "TENANT_A";
    private static final String COMMAND = "01K2A000000000000000000081";
    private static final String PLAN = "01K2A000000000000000000082";
    private static final String ORDER = "01K2A000000000000000000083";
    private static final String TERMINAL = "01K2A000000000000000000084";
    private static final String SHIFT = "01K2A000000000000000000085";
    private static final String ELECTRONIC = "01K2A000000000000000000086";
    private static final String CASH = "01K2A000000000000000000087";
    private static final String CASH_FACT = "01K2A000000000000000000088";
    private static final String HASH = "a".repeat(64);
    private static final String CONTENT_HASH = "b".repeat(64);
    private static final Instant AT = Instant.parse("2026-08-22T01:00:00Z");

    private final PaymentMapper mapper = mock(PaymentMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final PaymentOrderSnapshotPort orders = mock(PaymentOrderSnapshotPort.class);
    private final TenderCashCollectionPort cashPort = mock(TenderCashCollectionPort.class);
    private final TenderOrderSettlementPort settlementPort = mock(TenderOrderSettlementPort.class);
    private final PaymentIdempotencyService idempotency = mock(PaymentIdempotencyService.class);
    private final PaymentJournalService journal = mock(PaymentJournalService.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);
    private final TenderPlanService service = new TenderPlanService(mapper, context, authorization, orders,
        cashPort, settlementPort, idempotency, journal, ulids);

    @BeforeEach
    void prepareTrustedContext() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 101L, 1001L, "cashier"));
        when(context.requireTenantId()).thenReturn(TENANT);
        when(ulids.next()).thenReturn("01K2A000000000000000000099");
    }

    @Test
    void freezesAuthoritativeOrderAndAllAllocations() {
        when(orders.requireSnapshot(ORDER)).thenReturn(orderSnapshot());

        TenderPlanResult result = service.create(create());

        assertThat(result.plan().status()).isEqualTo("FROZEN");
        assertThat(result.allocations()).hasSize(2);
        verify(mapper).insertTenderPlan(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyString(), any(), anyLong(), anyString(), anyInt(), anyString(), anyString(), any());
        verify(mapper, org.mockito.Mockito.times(2)).insertTenderAllocation(anyString(), anyString(), anyString(),
            anyInt(), anyString(), anyLong(), anyString(), anyString(), any());
        verify(idempotency).save(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), any(), any());
    }

    @Test
    void returnsFrozenIdempotentResultWithoutReadingOrderAgain() {
        TenderPlanResult stored = new TenderPlanResult(plan("FROZEN", 0, 0, 1),
            List.of(allocation(ELECTRONIC, 1, "ELECTRONIC", "PLANNED", 1_000, 1),
                allocation(CASH, 2, "CASH", "PLANNED", 299, 1)), false);
        when(idempotency.find(anyString(), anyString(), anyString(), anyString(), any())).thenReturn(stored);

        assertThat(service.create(create()).duplicate()).isTrue();
        verify(orders, never()).requireSnapshot(anyString());
        verify(mapper, never()).insertTenderPlan(anyString(), anyString(), anyString(), anyString(), anyLong(),
            anyString(), anyString(), any(), anyLong(), anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void rejectsTamperedOrderSnapshotBeforeAnyPaymentWrite() {
        when(orders.requireSnapshot(ORDER)).thenReturn(new OrderPaymentSnapshot(ORDER, 1101L, TERMINAL, SHIFT,
            101L, LocalDate.parse("2026-08-22"), "PENDING_PAYMENT", "UNPAID", "CNY", 1_299,
            "f".repeat(64), List.of(new PaymentOrderSnapshotPort.LineSnapshot(
                "01K2A000000000000000000089", BigDecimal.ONE, 1_299))));
        assertThatThrownBy(() -> service.create(create())).isInstanceOf(ServiceException.class)
            .hasMessageContaining("TENDER-ORDER-001");
        verify(mapper, never()).insertTenderPlan(anyString(), anyString(), anyString(), anyString(), anyLong(),
            anyString(), anyString(), any(), anyLong(), anyString(), anyInt(), anyString(), anyString(), any());
    }

    @Test
    void electronicAllocationFailsClosedAndPersistsAuditableIdempotentOutcome() {
        when(mapper.lockTenderPlan(TENANT, PLAN)).thenReturn(plan("FROZEN", 0, 0, 1));
        TenderAllocationView electronic = allocation(ELECTRONIC, 1, "ELECTRONIC", "PLANNED", 1_000, 1);
        when(mapper.lockTenderAllocation(TENANT, ELECTRONIC)).thenReturn(electronic);
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of(electronic,
            allocation(CASH, 2, "CASH", "PLANNED", 299, 1)));

        TenderCollectResult result = service.collect(collect(ELECTRONIC, null));

        assertThat(result.outcome()).isEqualTo("PAYMENT_EXTERNAL_BLOCKED");
        assertThat(result.allocationStatus()).isEqualTo("PLANNED");
        verify(cashPort, never()).collect(any());
        verify(mapper, never()).updateTenderAllocation(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
        verify(idempotency).save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void rejectsCashBeforePreviousElectronicSuccess() {
        when(mapper.lockTenderPlan(TENANT, PLAN)).thenReturn(plan("FROZEN", 0, 0, 1));
        TenderAllocationView cash = allocation(CASH, 2, "CASH", "PLANNED", 299, 1);
        when(mapper.lockTenderAllocation(TENANT, CASH)).thenReturn(cash);
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of(
            allocation(ELECTRONIC, 1, "ELECTRONIC", "UNKNOWN", 1_000, 2), cash));

        assertThatThrownBy(() -> service.collect(collect(CASH, 500L))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("TENDER-SEQUENCE-002");
        verify(cashPort, never()).collect(any());
    }

    @Test
    void finalCashSuccessCompletesPlanAndOrderThroughOwnerPorts() {
        TenderPlanView plan = plan("COLLECTING", 1_000, 1_000, 2);
        TenderAllocationView electronic = allocation(ELECTRONIC, 1, "ELECTRONIC", "SUCCEEDED", 1_000, 2);
        TenderAllocationView cash = allocation(CASH, 2, "CASH", "PLANNED", 299, 1);
        when(mapper.lockTenderPlan(TENANT, PLAN)).thenReturn(plan);
        when(mapper.lockTenderAllocation(TENANT, CASH)).thenReturn(cash);
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of(electronic, cash));
        when(cashPort.collect(any())).thenReturn(new TenderCashCollectionPort.CashTenderReceipt(
            CASH_FACT, CASH, 299, 500, 201, false));
        when(mapper.updateTenderAllocation(eq(TENANT), eq(CASH), eq("SUCCEEDED"), eq(CASH_FACT), eq(COMMAND),
            anyString(), eq(1L)))
            .thenReturn(1);
        when(mapper.updateTenderPlanProjection(TENANT, PLAN, "PAID", 1_299, 1_299, 2)).thenReturn(1);

        TenderCollectResult result = service.collect(collect(CASH, 500L));

        assertThat(result.planStatus()).isEqualTo("PAID");
        assertThat(result.changeMinor()).isEqualTo(201);
        verify(settlementPort).complete(any());
        verify(mapper, org.mockito.Mockito.times(2)).insertTenderHistory(anyString(), anyString(), anyString(), any(), anyString(),
            anyString(), any(), anyString(), anyLong(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void queryUsesTrustedTenantAndStoreScope() {
        when(mapper.findTenderPlan(TENANT, PLAN)).thenReturn(plan("UNKNOWN", 0, 1_000, 2));
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of());
        assertThat(service.find(PLAN).plan().status()).isEqualTo("UNKNOWN");
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void cancelsOnlyUntouchedAllocationsAndKeepsAppendOnlyHistory() {
        TenderPlanView plan = plan("FROZEN", 0, 0, 1);
        TenderAllocationView electronic = allocation(ELECTRONIC, 1, "ELECTRONIC", "PLANNED", 1_000, 1);
        TenderAllocationView cash = allocation(CASH, 2, "CASH", "PLANNED", 299, 1);
        when(mapper.lockTenderPlan(TENANT, PLAN)).thenReturn(plan);
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of(electronic, cash));
        when(mapper.cancelTenderAllocation(eq(TENANT), anyString(), eq(1L))).thenReturn(1);
        when(mapper.updateTenderPlanProjection(TENANT, PLAN, "CANCELLED", 0, 0, 1)).thenReturn(1);

        TenderPlanResult result = service.cancel(new CancelTenderPlan(COMMAND,
            "tender:cancel:tenant-a:0001", PLAN, "CUSTOMER_ABORT", AT));

        assertThat(result.plan().status()).isEqualTo("CANCELLED");
        assertThat(result.allocations()).allMatch(item -> "CANCELLED".equals(item.status()));
        verify(mapper, org.mockito.Mockito.times(2)).cancelTenderAllocation(eq(TENANT), anyString(), eq(1L));
        verify(mapper, org.mockito.Mockito.times(3)).insertTenderHistory(anyString(), anyString(), anyString(),
            any(), anyString(), anyString(), any(), anyString(), anyLong(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    void recoveryInspectsUnknownWithoutChangingOrReplacingIt() {
        TenderPlanView plan = plan("UNKNOWN", 0, 1_000, 2);
        when(mapper.lockTenderPlan(TENANT, PLAN)).thenReturn(plan);
        when(mapper.findTenderAllocations(TENANT, PLAN)).thenReturn(List.of(
            allocation(ELECTRONIC, 1, "ELECTRONIC", "UNKNOWN", 1_000, 2),
            allocation(CASH, 2, "CASH", "PLANNED", 299, 1)));

        TenderPlanResult result = service.recover(new RecoverTenderPlan(COMMAND,
            "tender:recover:tenant-a:0001", PLAN, "ACK_TIMEOUT", AT));

        assertThat(result.plan().status()).isEqualTo("UNKNOWN");
        verify(mapper, never()).updateTenderPlanProjection(anyString(), anyString(), anyString(),
            anyLong(), anyLong(), anyLong());
        verify(mapper, never()).updateTenderAllocation(anyString(), anyString(), anyString(), any(), any(), any(), anyLong());
        verify(mapper, never()).cancelTenderAllocation(anyString(), anyString(), anyLong());
        verify(idempotency).save(eq(TENANT), eq("RECOVER_TENDER_PLAN"), eq(COMMAND), anyString(),
            anyString(), eq(PLAN), any(), any());
    }

    private CreateTenderPlan create() {
        return new CreateTenderPlan(COMMAND, "tender:create:tenant-a:0001", PLAN, ORDER, HASH, 1101L,
            TERMINAL, 1_299, "CNY", List.of(
                new TenderAllocationInput(ELECTRONIC, 1, "ELECTRONIC", 1_000),
                new TenderAllocationInput(CASH, 2, "CASH", 299)), AT);
    }

    private CollectTenderAllocation collect(String allocationId, Long tendered) {
        return new CollectTenderAllocation(COMMAND, "tender:collect:tenant-a:0001", PLAN,
            allocationId, tendered, AT);
    }

    private OrderPaymentSnapshot orderSnapshot() {
        return new OrderPaymentSnapshot(ORDER, 1101L, TERMINAL, SHIFT, 101L, LocalDate.parse("2026-08-22"),
            "PENDING_PAYMENT", "UNPAID", "CNY", 1_299, HASH,
            List.of(new PaymentOrderSnapshotPort.LineSnapshot(
                "01K2A000000000000000000089", BigDecimal.ONE, 1_299)));
    }

    private TenderPlanView plan(String status, long succeeded, long occupied, long version) {
        return new TenderPlanView(PLAN, ORDER, HASH, 1101L, TERMINAL, SHIFT, LocalDate.parse("2026-08-22"),
            status, 1_299, succeeded, occupied, "CNY", 2, CONTENT_HASH, COMMAND, version,
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    private TenderAllocationView allocation(String id, int sequence, String type, String status,
                                             long amount, long version) {
        return new TenderAllocationView(id, PLAN, sequence, type, status, amount, "CNY", HASH,
            null, null, null, null, version);
    }
}
