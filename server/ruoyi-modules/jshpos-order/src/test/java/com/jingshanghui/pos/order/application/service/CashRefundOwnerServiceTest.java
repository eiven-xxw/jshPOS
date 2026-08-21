package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort.CashRefundCommand;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort.ReturnOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.ReturnOwnerMapper;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T2-REF-002 现金退款 Owner 的累计上限、班次绑定与幂等落账测试。 */
class CashRefundOwnerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");
    private static final String REFUND = "01K5R000000000000000000001";
    private static final String EVENT = "01K5E000000000000000000001";
    private static final String ORDER = "01K5N000000000000000000001";
    private static final String PAYMENT = "01K5P000000000000000000001";
    private static final String SHIFT = "01K5H000000000000000000001";
    private static final String TERMINAL = "01K5T000000000000000000001";
    private static final String CORRELATION = "01K5Z000000000000000000001";
    private static final String HASH = "a".repeat(64);
    private static final LocalDate DAY = LocalDate.parse("2026-08-17");

    private final ReturnOwnerMapper mapper = mock(ReturnOwnerMapper.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final ReturnOrderSnapshotPort orders = mock(ReturnOrderSnapshotPort.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final OrderJournalService journal = mock(OrderJournalService.class);
    private final CashRefundOwnerService service = new CashRefundOwnerService(mapper, orderMapper, orders,
        context, authorization, journal, new UlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)));

    @BeforeEach
    void configureTrustedSyntheticContext() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic Alice"));
        when(orders.requireSnapshot(ORDER)).thenReturn(new ReturnOrderSnapshot(ORDER, "SYN-ORDER-1", 1101L, TERMINAL, DAY,
            "COMPLETED", "PAID", "CNY", 1000, 100, 0, 900,
            "01K5S000000000000000000001", "b".repeat(64), PAYMENT, List.of()));
        when(mapper.lockCashPayment("TENANT_A", PAYMENT))
            .thenReturn(new ReturnOwnerMapper.CashPayment(PAYMENT, ORDER, "SUCCEEDED", 900));
        when(mapper.sumSucceededCashRefund("TENANT_A", PAYMENT)).thenReturn(0L);
        when(orderMapper.lockShift("TENANT_A", SHIFT)).thenReturn(new ShiftView(SHIFT, 1101L, TERMINAL,
            101L, "Synthetic Alice", DAY, "Asia/Shanghai", 1, "OPEN", "CNY", 0, 900,
            null, null, null, 1));
        when(orderMapper.addShiftCash("TENANT_A", SHIFT, -400)).thenReturn(1);
    }

    @Test
    void atomicallyAppendsRefundNegativeLedgerShiftAuditAndOutbox() {
        var result = service.refund(command(400, HASH));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.cashRefundId()).isEqualTo(EVENT);
        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertCashRefund(eq(EVENT), eq("TENANT_A"), eq(REFUND), eq(ORDER), eq(PAYMENT),
            eq(SHIFT), eq(1101L), eq(TERMINAL), eq(DAY), eq(400L), eq(HASH), eq(CORRELATION),
            eq(101L), any());
        verify(mapper).insertCashRefundLedger(any(), eq("TENANT_A"), eq(SHIFT), eq(ORDER), eq(PAYMENT),
            eq(EVENT), eq(400L), eq(DAY), any());
        verify(orderMapper).addShiftCash("TENANT_A", SHIFT, -400);
        verify(journal).audit(eq("TENANT_A"), eq("CASH_REFUND_SUCCEEDED"), eq("CASH_REFUND"),
            eq(REFUND), eq(101L), isNull(), eq(EVENT), eq("REQUESTED"), eq("SUCCEEDED"),
            eq(400L), eq(HASH), eq("ORIGINAL_RETURN"), any());
        verify(journal).appendEvent(eq("TENANT_A"), eq("order.command"),
            eq("cash.refund.succeeded.v1"), eq("CASH_REFUND"), eq(REFUND), eq(1L),
            eq(CORRELATION), any(), any());
    }

    @Test
    void returnsStoredResultForSameRefundAndRejectsChangedContent() {
        when(mapper.findCashRefund("TENANT_A", REFUND))
            .thenReturn(new ReturnOwnerMapper.CashRefund(EVENT, REFUND, 400, HASH, "SUCCEEDED"));
        assertThat(service.refund(command(400, HASH)).duplicate()).isTrue();
        verify(mapper, never()).lockCashPayment(any(), any());

        assertThatThrownBy(() -> service.refund(command(400, "c".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不同内容");
        verify(mapper, never()).insertCashRefund(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            anyLong(), any(), any(), any(), any());
    }

    @Test
    void rejectsCumulativeOverRefundAndMismatchedShiftBeforeWrites() {
        when(mapper.sumSucceededCashRefund("TENANT_A", PAYMENT)).thenReturn(700L);
        assertThatThrownBy(() -> service.refund(command(400, HASH))).hasMessageContaining("超过原收款");
        verify(mapper, never()).insertCashRefundLedger(any(), any(), any(), any(), any(), any(), anyLong(),
            any(), any());

        when(mapper.sumSucceededCashRefund("TENANT_A", PAYMENT)).thenReturn(0L);
        when(orderMapper.lockShift("TENANT_A", SHIFT)).thenReturn(new ShiftView(SHIFT, 2101L, TERMINAL,
            101L, "Synthetic Alice", DAY, "Asia/Shanghai", 1, "OPEN", "CNY", 0, 900,
            null, null, null, 1));
        assertThatThrownBy(() -> service.refund(command(400, HASH))).hasMessageContaining("班次");
        verify(orderMapper, never()).addShiftCash("TENANT_A", SHIFT, -400);
    }

    private CashRefundCommand command(long amountMinor, String hash) {
        return new CashRefundCommand(EVENT, REFUND, ORDER, PAYMENT, SHIFT, 1101L, TERMINAL, DAY,
            amountMinor, hash, CORRELATION, NOW);
    }
}
