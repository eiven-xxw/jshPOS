package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderDispositionServiceTest {

    private static final String SOURCE = "01K2A000000000000000000071";
    private static final String DISPOSITION = "01K2A000000000000000000072";
    private static final String ORDER = "01K2A000000000000000000051";
    private static final String TERMINAL = "01K2A000000000000000000011";
    private static final String SHIFT = "01K2A000000000000000000021";
    private static final String SNAPSHOT = "a".repeat(64);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 21);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 3, 0);

    private final OrderMapper mapper = mock(OrderMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final OrderJournalService journal = mock(OrderJournalService.class);
    private final OrderFinalityGuardService finalityGuard = new OrderFinalityGuardService(mapper);
    private final OrderDispositionService service = new OrderDispositionService(
        mapper, context, authorization, journal, finalityGuard);

    @BeforeEach
    void arrangeTrustedContext() {
        when(context.requirePrincipal()).thenReturn(
            new TrustedPrincipal("TENANT_A", 101L, 1L, "虚构收银员甲"));
    }

    @Test
    void appendsCancellationTombstoneWhenNoCompletedOrderExists() {
        String requestHash = hash("CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "虚构顾客付款前取消", null, SNAPSHOT);

        service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL, SHIFT, 101L,
            BUSINESS_DATE, "CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "虚构顾客付款前取消", null, SNAPSHOT, requestHash, 2, NOW);

        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertOrderDisposition("TENANT_A", SOURCE, DISPOSITION, ORDER, 1101L,
            TERMINAL, SHIFT, 101L, BUSINESS_DATE, "CANCEL_BEFORE_COMPLETION", "DRAFT",
            "CANCELLED", "CUSTOMER_CANCEL", "虚构顾客付款前取消", null, SNAPSHOT,
            requestHash, 2, NOW);
        verify(journal).audit("TENANT_A", "ORDER_CANCELLED", "ORDER", ORDER, 101L,
            null, SOURCE, "DRAFT", "CANCELLED", null, requestHash, "CUSTOMER_CANCEL", NOW);
    }

    @Test
    void completedOrderUsesOnlyAppendOnlyRouteAndMustMatchAuthoritativeSnapshot() {
        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(completedOrder());
        String requestHash = hash("RETURN_REFUND_REQUIRED", "COMPLETED", "COMPLETED",
            "CUSTOMER_RETURN", "虚构顾客原单退货", null, SNAPSHOT);

        service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL, SHIFT, 101L,
            BUSINESS_DATE, "RETURN_REFUND_REQUIRED", "COMPLETED", "COMPLETED",
            "CUSTOMER_RETURN", "虚构顾客原单退货", null, SNAPSHOT, requestHash, 4, NOW);

        verify(mapper).insertOrderDisposition(eq("TENANT_A"), eq(SOURCE), eq(DISPOSITION),
            eq(ORDER), eq(1101L), eq(TERMINAL), eq(SHIFT), eq(101L), eq(BUSINESS_DATE),
            eq("RETURN_REFUND_REQUIRED"), eq("COMPLETED"), eq("COMPLETED"),
            eq("CUSTOMER_RETURN"), eq("虚构顾客原单退货"), eq(null), eq(SNAPSHOT),
            eq(requestHash), eq(4L), eq(NOW));
        verify(journal).audit("TENANT_A", "ORDER_REVERSAL_ROUTED", "ORDER", ORDER, 101L,
            null, SOURCE, "COMPLETED", "COMPLETED", null, requestHash, "CUSTOMER_RETURN", NOW);
    }

    @Test
    void rejectsSnapshotReplacementAndExistingCompletionCancellation() {
        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(completedOrder());
        String forgedSnapshot = "b".repeat(64);
        String routeHash = hash("RETURN_REFUND_REQUIRED", "COMPLETED", "COMPLETED",
            "CUSTOMER_RETURN", "虚构摘要替换", null, forgedSnapshot);

        assertThatThrownBy(() -> service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL,
            SHIFT, 101L, BUSINESS_DATE, "RETURN_REFUND_REQUIRED", "COMPLETED", "COMPLETED",
            "CUSTOMER_RETURN", "虚构摘要替换", null, forgedSnapshot, routeHash, 4, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_DISPOSITION_REQUIRED");

        String cancelHash = hash("CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "不得覆盖成交", null, SNAPSHOT);
        assertThatThrownBy(() -> service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL,
            SHIFT, 101L, BUSINESS_DATE, "CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "不得覆盖成交", null, SNAPSHOT, cancelHash, 2, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_CANCELLATION_BLOCKED");

        verify(mapper, never()).insertOrderDisposition(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Long.class), any());
    }

    @Test
    void rejectsSelfConsistentTamperingAndMissingAbnormalAuthorization() {
        String wrongHash = "c".repeat(64);
        assertThatThrownBy(() -> service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL,
            SHIFT, 101L, BUSINESS_DATE, "CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "虚构顾客付款前取消", null, SNAPSHOT, wrongHash, 2, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_DISPOSITION_HASH_MISMATCH");

        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(completedOrder());
        String abnormalHash = hash("EXPLICIT_COMPENSATION_REQUIRED", "COMPLETED", "COMPLETED",
            "SYNC_ANOMALY", "虚构同步异常", null, SNAPSHOT);
        assertThatThrownBy(() -> service.record(SOURCE, DISPOSITION, ORDER, 1101L, TERMINAL,
            SHIFT, 101L, BUSINESS_DATE, "EXPLICIT_COMPENSATION_REQUIRED", "COMPLETED", "COMPLETED",
            "SYNC_ANOMALY", "虚构同步异常", null, SNAPSHOT, abnormalHash, 4, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PERMISSION_DENIED");
    }

    @Test
    void malformedNullIdentityFailsAsDomainValidationNotRuntimeNullPointer() {
        assertThatThrownBy(() -> service.record(null, DISPOSITION, ORDER, 1101L, TERMINAL,
            SHIFT, 101L, BUSINESS_DATE, "CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "虚构输入", null, SNAPSHOT, "d".repeat(64), 2, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("sourceEventId");
    }

    private OrderView completedOrder() {
        return new OrderView(ORDER, "SYN-G7B-0001", 1101L, TERMINAL, SHIFT, 101L,
            BUSINESS_DATE, "COMPLETED", "PAID", "CNY", 1299, 1299, 1299,
            SNAPSHOT, "{}", 4, NOW);
    }

    private String hash(String type, String from, String effective, String reasonCode,
                        String reasonText, String authorizationRef, String snapshotHash) {
        return CanonicalHash.sha256(CanonicalHash.lengthPrefixed(List.of(type, ORDER, "1101",
            TERMINAL, "101", SHIFT, BUSINESS_DATE.toString(), from, effective, reasonCode,
            reasonText, authorizationRef == null ? "" : authorizationRef, snapshotHash)));
    }
}
