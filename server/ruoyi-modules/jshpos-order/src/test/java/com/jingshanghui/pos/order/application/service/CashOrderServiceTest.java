package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.application.service.PriceBookService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderCommands.CashOrder;
import com.jingshanghui.pos.order.application.model.OrderCommands.Line;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CashOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");
    private static final String ORDER = "01K2A000000000000000000031";
    private static final String SHIFT = "01K2A000000000000000000021";
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final PriceBookService prices = mock(PriceBookService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UlidGenerator ulids = new UlidGenerator(clock);
    private final IdempotencyService idempotency = new IdempotencyService(mapper, ulids, new ObjectMapper());
    private final OrderJournalService journal = new OrderJournalService(mapper, ulids);
    private final OrderFinalityGuardService finalityGuard = new OrderFinalityGuardService(mapper);
    private final CashOrderService service = new CashOrderService(
        mapper, context, authorization, prices, idempotency, journal, finalityGuard, ulids, clock);

    @BeforeEach
    void configureTrustedSyntheticTenant() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic Alice"));
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(mapper.findIdempotency("TENANT_A", "SUBMIT_CASH_ORDER", "cash-order-key-0001")).thenReturn(null);
        when(prices.resolve(701L, 301L, 1101L, NOW))
            .thenReturn(new ResolvedPrice(1299, "CNY", 1L, 2L, "TENANT_BASE", NOW));
        when(mapper.lockShift("TENANT_A", SHIFT)).thenReturn(new ShiftView(
            SHIFT, 1101L, "01K2A000000000000000000011", 101L, "Synthetic Alice",
            LocalDate.parse("2026-08-16"), "Asia/Shanghai", 1, "OPEN", "CNY",
            0, 0, null, null, null, 1));
        when(mapper.addShiftCash("TENANT_A", SHIFT, 1299)).thenReturn(1);
    }

    @Test
    void writesTheEntireCashFactSetThroughOneApplicationTransactionBoundary() {
        var result = service.submit(command(1299, "TENANT_BASE"));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.paymentStatus()).isEqualTo("PAID");
        assertThat(result.changeAmountMinor()).isEqualTo(701);
        verify(authorization).requireStoreAccess(1101L);
        verify(prices).resolve(701L, 301L, 1101L, NOW);
        verify(mapper).insertCompletedOrder(eq("TENANT_A"), eq(ORDER), eq("A-T1-000001"), eq(1101L),
            eq("01K2A000000000000000000011"), eq(SHIFT), eq(101L), eq(LocalDate.parse("2026-08-16")),
            eq("Asia/Shanghai"), eq(1299L), eq(1299L), eq(1L), eq(1L), eq("CONVENIENCE.1"),
            any(), any(), eq("cash-order-key-0001"), any(), any());
        verify(mapper).insertCashPayment(eq("TENANT_A"), any(), eq(ORDER), eq(SHIFT),
            eq(1299L), eq(2000L), eq(701L), eq(1299L), any());
        verify(mapper).insertCashLedger(eq("TENANT_A"), any(), eq(SHIFT), eq(ORDER), any(),
            eq(1299L), eq(LocalDate.parse("2026-08-16")), any());
        verify(mapper).addShiftCash("TENANT_A", SHIFT, 1299);
        verify(mapper, org.mockito.Mockito.times(3)).insertOutbox(eq("TENANT_A"), any(),
            eq("order.command"), any(), any(), any(), anyLong(), eq("01K2A000000000000000000051"),
            any(), any(), any());
        verify(mapper).insertIdempotency(any(), any(), eq("SUBMIT_CASH_ORDER"),
            eq("01K2A000000000000000000051"), eq("cash-order-key-0001"), any(), eq(ORDER),
            eq("CREATED"), any(), any());
    }

    @Test
    void rejectsPriceTamperingBeforeWritingOrderFacts() {
        assertThatThrownBy(() -> service.submit(command(1298, "TENANT_BASE")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        verify(mapper, never()).insertCompletedOrder(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsClientCashierThatDoesNotMatchTrustedPrincipal() {
        CashOrder original = command(1299, "TENANT_BASE");
        CashOrder forged = new CashOrder(original.commandId(), original.idempotencyKey(), original.orderId(),
            original.localOrderNo(), original.storeId(), original.terminalId(), original.shiftId(), "999",
            original.businessDate(), original.storeTimezone(), original.catalogVersion(), original.priceVersion(),
            original.industryTemplateVersion(), original.grossAmountMinor(), original.receivableAmountMinor(),
            original.tenderedAmountMinor(), original.lines(), original.occurredAt());
        assertThatThrownBy(() -> service.submit(forged)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("PERMISSION_DENIED");
    }

    @Test
    void cancellationTombstonePreventsLateCompletion() {
        when(mapper.countCancellationDisposition("TENANT_A", ORDER)).thenReturn(1);

        assertThatThrownBy(() -> service.submit(command(1299, "TENANT_BASE")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_CANCELLATION_BLOCKED");

        verify(mapper, never()).insertCompletedOrder(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    private CashOrder command(long unitPrice, String source) {
        long gross = unitPrice;
        return new CashOrder("01K2A000000000000000000051", "cash-order-key-0001", ORDER,
            "A-T1-000001", 1101L, "01K2A000000000000000000011", SHIFT, "101",
            LocalDate.parse("2026-08-16"), "Asia/Shanghai", 1, 1, "CONVENIENCE.1",
            gross, gross, 2000, List.of(new Line("01K2A000000000000000000041", 1, 701L,
            "A-SKU-001", "001234", "Synthetic Water", 301L, "PCS", "1.000000",
            unitPrice, gross, gross, source)), NOW);
    }
}
