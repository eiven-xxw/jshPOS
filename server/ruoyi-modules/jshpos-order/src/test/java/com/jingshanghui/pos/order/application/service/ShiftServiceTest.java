package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderCommands.ApproveDifference;
import com.jingshanghui.pos.order.application.model.OrderCommands.CloseShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.OpenShift;
import com.jingshanghui.pos.order.application.model.OrderViews.ApprovalView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShiftServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T01:00:00Z");
    private static final String TERMINAL = "01K2A000000000000000000011";
    private static final String SHIFT = "01K2A000000000000000000021";
    private static final String APPROVAL = "01K2A000000000000000000091";
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final OrderJournalService journal = mock(OrderJournalService.class);
    private final ShiftDifferencePolicy differencePolicy = mock(ShiftDifferencePolicy.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ShiftService service = new ShiftService(mapper, context, authorization, idempotency, journal,
        differencePolicy, new UlidGenerator(clock), clock);

    @Test
    void opensShiftOnlyForTheTrustedCashierAndStore() {
        when(context.requirePrincipal()).thenReturn(principal(101L));
        ShiftView opened = shift("OPEN", 1, 5000, null, null);
        when(mapper.findShift(eq("TENANT_A"), any())).thenReturn(opened);

        ShiftView result = service.open(new OpenShift("01K2A000000000000000000051", "open-shift-key-0001",
            1101L, TERMINAL, "101", LocalDate.parse("2026-08-16"), "Asia/Shanghai", 5000, 1, NOW));

        assertThat(result.status()).isEqualTo("OPEN");
        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertShift(eq("TENANT_A"), any(), eq(1101L), eq(TERMINAL), eq(101L),
            eq("Synthetic User"), eq(LocalDate.parse("2026-08-16")), eq("Asia/Shanghai"), eq(1L),
            eq(5000L), any());
        verify(journal).appendEvent(eq("TENANT_A"), eq("shift.event"), eq("shift.opened.v1"),
            eq("SHIFT"), any(), eq(1L), eq("01K2A000000000000000000051"), any(), any());
    }

    @Test
    void bindsSupervisorApprovalToTheExactCountAndShiftVersion() {
        when(context.requirePrincipal()).thenReturn(principal(102L));
        when(mapper.findShift("TENANT_A", SHIFT)).thenReturn(shift("OPEN", 1, 5000, null, null));
        when(mapper.lockShift("TENANT_A", SHIFT)).thenReturn(shift("OPEN", 1, 5000, null, null));
        when(mapper.sumCashLedger("TENANT_A", SHIFT)).thenReturn(1299L);
        when(differencePolicy.approvalThresholdMinor(1101L)).thenReturn(0L);
        ApprovalView approval = new ApprovalView(APPROVAL, SHIFT, 102L, "APPROVED", 6299, 6300, 1, 1);
        when(mapper.findApproval(eq("TENANT_A"), eq(SHIFT), any())).thenReturn(approval);

        ApprovalView result = service.approveDifference(new ApproveDifference(
            "01K2A000000000000000000052", "approve-shift-key-01", SHIFT, 6300, 1,
            "COUNT_CONFIRMED", "Synthetic supervisor confirmed", NOW));

        assertThat(result.differenceMinor()).isEqualTo(1);
        verify(mapper).insertApproval(eq("TENANT_A"), any(), eq(SHIFT), eq(102L),
            eq("COUNT_CONFIRMED"), eq("Synthetic supervisor confirmed"), eq(6299L), eq(6300L),
            eq(1L), eq(1L), any());
        verify(journal).appendEvent(eq("TENANT_A"), eq("shift.event"),
            eq("shift.difference-approved.v1"), eq("SHIFT"), eq(SHIFT), eq(1L),
            eq("01K2A000000000000000000052"), any(), any());
    }

    @Test
    void closesOnlyWithAnApprovalMatchingTheExactCashCount() {
        when(context.requirePrincipal()).thenReturn(principal(101L));
        when(mapper.lockShift("TENANT_A", SHIFT)).thenReturn(shift("OPEN", 1, 5000, null, null));
        when(mapper.sumCashLedger("TENANT_A", SHIFT)).thenReturn(1299L);
        when(differencePolicy.approvalThresholdMinor(1101L)).thenReturn(0L);
        when(mapper.findApproval("TENANT_A", SHIFT, APPROVAL))
            .thenReturn(new ApprovalView(APPROVAL, SHIFT, 102L, "APPROVED", 6299, 6300, 1, 1));
        when(mapper.closeShift(eq("TENANT_A"), eq(SHIFT), eq(6299L), eq(6300L), eq(1L),
            eq(APPROVAL), any(), eq(1L))).thenReturn(1);
        when(mapper.findShift("TENANT_A", SHIFT)).thenReturn(shift("CLOSED", 2, 6299, 6300L, 1L));

        ShiftView result = service.close(new CloseShift("01K2A000000000000000000053",
            "close-shift-key-0001", SHIFT, 6300, 1, APPROVAL, NOW));

        assertThat(result.status()).isEqualTo("CLOSED");
        verify(mapper).closeShift(eq("TENANT_A"), eq(SHIFT), eq(6299L), eq(6300L), eq(1L),
            eq(APPROVAL), any(), eq(1L));
    }

    @Test
    void rejectsAnApprovalForADifferentCount() {
        when(context.requirePrincipal()).thenReturn(principal(101L));
        when(mapper.lockShift("TENANT_A", SHIFT)).thenReturn(shift("OPEN", 1, 5000, null, null));
        when(mapper.sumCashLedger("TENANT_A", SHIFT)).thenReturn(1299L);
        when(differencePolicy.approvalThresholdMinor(1101L)).thenReturn(0L);
        when(mapper.findApproval("TENANT_A", SHIFT, APPROVAL))
            .thenReturn(new ApprovalView(APPROVAL, SHIFT, 102L, "APPROVED", 6299, 6301, 2, 1));

        assertThatThrownBy(() -> service.close(new CloseShift("01K2A000000000000000000053",
            "close-shift-key-0001", SHIFT, 6300, 1, APPROVAL, NOW)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SHIFT_DIFFERENCE_APPROVAL_REQUIRED");
        verify(mapper, never()).closeShift(any(), any(), anyLong(), anyLong(), anyLong(), any(), any(), anyLong());
    }

    private TrustedPrincipal principal(long userId) {
        return new TrustedPrincipal("TENANT_A", userId, 1L, "Synthetic User");
    }

    private ShiftView shift(String status, long version, long theoretical, Long actual, Long difference) {
        return new ShiftView(SHIFT, 1101L, TERMINAL, 101L, "Synthetic Cashier",
            LocalDate.parse("2026-08-16"), "Asia/Shanghai", 1, status, "CNY", 5000,
            theoretical, actual, difference, status.equals("CLOSED") ? APPROVAL : null, version);
    }
}
