package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort.ReturnOrderLine;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort.ReturnOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocatedLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationResult;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.ApproveReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PaymentObservation;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.RequestLine;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.RequestReturn;
import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.IdempotencyRow;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.InboxRow;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.LineRow;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.ReturnRow;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.ReservedQuantityRow;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Return Owner 的累计上限、职责分离、Inbox/Outbox 和 UNKNOWN 检查点测试。 */
class ReturnOrchestrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T08:00:00Z");
    private static final LocalDateTime NOW_DB = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final LocalDate DAY = LocalDate.parse("2026-08-17");
    private static final String RETURN = "01K5R000000000000000000001";
    private static final String ORDER = "01K5N000000000000000000001";
    private static final String ORDER_LINE = "01K5A000000000000000000001";
    private static final String RETURN_LINE = "01K5X000000000000000000001";
    private static final String COMMAND = "01K5C000000000000000000001";
    private static final String EVENT = "01K5E000000000000000000001";
    private static final String PAYMENT_EVENT = "01K5F000000000000000000001";
    private static final String HISTORY = "01K5Y000000000000000000001";
    private static final String TERMINAL = "01K5T000000000000000000001";
    private static final String SHIFT = "01K5H000000000000000000001";
    private static final String WAREHOUSE = "01K5W000000000000000000001";
    private static final String SNAPSHOT = "01K5S000000000000000000001";
    private static final String CASH_PAYMENT = "01K5P000000000000000000001";
    private static final String CORRELATION = "01K5Z000000000000000000001";

    private final ReturnMapper mapper = mock(ReturnMapper.class);
    private final ReturnOrderSnapshotPort orders = mock(ReturnOrderSnapshotPort.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit = mock(DomainAuditService.class);
    private final UlidGenerator ulids = mock(UlidGenerator.class);
    private final ReturnOrchestrationService service = new ReturnOrchestrationService(mapper, orders, context,
        authorization, audit, ulids);

    @BeforeEach
    void configureTrustedSyntheticContext() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic Alice"));
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(orders.requireSnapshot(ORDER)).thenReturn(orderSnapshot(CASH_PAYMENT));
        when(mapper.lockOrderGuard("TENANT_A", ORDER)).thenReturn(ORDER);
        when(mapper.sumReservedQuantities("TENANT_A", ORDER)).thenReturn(List.of());
        when(mapper.listLines("TENANT_A", RETURN)).thenReturn(List.of(line(null, null, null)));
    }

    @Test
    void createsPendingApprovalUnderOrderLockAndStableIdempotency() {
        when(ulids.next()).thenReturn(RETURN_LINE, HISTORY);
        when(mapper.findReturn("TENANT_A", RETURN)).thenReturn(row(Status.PENDING_APPROVAL, null, null));

        var result = service.request(request("CASH", null, "1"));

        assertThat(result.status()).isEqualTo("PENDING_APPROVAL");
        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertOrderGuard("TENANT_A", ORDER);
        verify(mapper).lockOrderGuard("TENANT_A", ORDER);
        verify(mapper).insertReturn(any());
        verify(mapper).insertLine(any());
        verify(mapper).insertIdempotency(any());
        verify(audit).append(eq("RETURN_REQUESTED"), eq("RETURN"), eq(RETURN), eq(null),
            eq("PENDING_APPROVAL"), any());
    }

    @Test
    void rejectsChangedIdempotencyAndCumulativeQuantityOverflow() {
        when(mapper.findIdempotency("TENANT_A", "REQUEST_ORIGINAL_RETURN", "return-key-0001"))
            .thenReturn(new IdempotencyRow("f".repeat(64), RETURN));
        assertThatThrownBy(() -> service.request(request("CASH", null, "1")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不同退货内容");
        verify(mapper, never()).insertReturn(any());

        when(mapper.findIdempotency("TENANT_A", "REQUEST_ORIGINAL_RETURN", "return-key-0001"))
            .thenReturn(null);
        when(mapper.sumReservedQuantities("TENANT_A", ORDER))
            .thenReturn(List.of(new ReservedQuantityRow(ORDER_LINE, new BigDecimal("1.5"))));
        when(ulids.next()).thenReturn(RETURN_LINE);
        assertThatThrownBy(() -> service.request(request("CASH", null, "1")))
            .hasMessageContaining("累计退货数量");
    }

    @Test
    void settlementKindMustInheritOriginalOrderCollectionFact() {
        assertThatThrownBy(() -> service.request(request("PROVIDER_NEUTRAL", CASH_PAYMENT, "1")))
            .hasMessageContaining("继承原订单收款事实");
        when(orders.requireSnapshot(ORDER)).thenReturn(orderSnapshot(null));
        assertThatThrownBy(() -> service.request(request("CASH", null, "1")))
            .hasMessageContaining("继承原订单收款事实");
    }

    @Test
    void enforcesIndependentApproverBeforePublishingPromotionOutbox() {
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(row(Status.PENDING_APPROVAL, null, null));
        assertThatThrownBy(() -> service.approve(new ApproveReturn(COMMAND, RETURN, "SUPERVISOR_APPROVED",
            CORRELATION, NOW))).hasMessageContaining("申请人与审批人必须分离");
        verify(mapper, never()).approve(any(), any(), anyLong(), any(), any(), any());
        verify(mapper, never()).insertOutbox(any());
    }

    @Test
    void freezesPromotionAllocationThenEmitsExactlyOneStableCashOwnerCommand() {
        ReturnRow current = row(Status.PROMOTION_PENDING, EVENT, null);
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(current);
        when(mapper.applyPromotionHeader("TENANT_A", RETURN, 2, "CASH_REFUND_PENDING",
            1000, 100, 900, PAYMENT_EVENT, null, NOW_DB)).thenReturn(1);
        when(mapper.updateAllocation(any())).thenReturn(1);
        when(ulids.next()).thenReturn(PAYMENT_EVENT, HISTORY);
        when(mapper.findReturn("TENANT_A", RETURN)).thenReturn(row(Status.CASH_REFUND_PENDING, EVENT, PAYMENT_EVENT));
        AllocationResult result = new AllocationResult(RETURN, SNAPSHOT, 1000, 100, 900,
            List.of(new AllocatedLine(ORDER_LINE, BigDecimal.ONE, 1000, 100, 900,
                BigDecimal.ONE, 900)));

        assertThat(service.acceptPromotion(EVENT, result, NOW).refundableAmountMinor()).isEqualTo(900);
        verify(mapper).insertInbox(any());
        verify(mapper).updateAllocation(any());
        verify(mapper).insertOutbox(any());
        verify(mapper).markOutboxDelivered("TENANT_A", EVENT, NOW_DB);
    }

    @Test
    void paymentUnknownPersistsCheckpointWithoutCreatingAnotherRefundCommand() {
        ReturnRow current = row(Status.PAYMENT_PENDING, null, EVENT);
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(current);
        when(mapper.advancePayment("TENANT_A", RETURN, 2, "PAYMENT_PENDING", "PAYMENT_UNKNOWN", null, NOW_DB))
            .thenReturn(1);
        when(ulids.next()).thenReturn(HISTORY);
        when(mapper.findReturn("TENANT_A", RETURN)).thenReturn(row(Status.PAYMENT_UNKNOWN, null, EVENT));

        var result = service.observePayment(new PaymentObservation(PAYMENT_EVENT, RETURN, "UNKNOWN", 900,
            "c".repeat(64), NOW));

        assertThat(result.status()).isEqualTo("PAYMENT_UNKNOWN");
        verify(mapper).insertInbox(any());
        verify(mapper, never()).insertOutbox(any());
    }

    @Test
    void rejectsSameInboxEventWithDifferentPayloadHash() {
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(row(Status.PAYMENT_PENDING, null, EVENT));
        when(mapper.findInbox("TENANT_A", PAYMENT_EVENT))
            .thenReturn(new InboxRow(PAYMENT_EVENT, "PAYMENT_OBSERVATION", RETURN, "a".repeat(64)));
        assertThatThrownBy(() -> service.observePayment(new PaymentObservation(PAYMENT_EVENT, RETURN,
            "UNKNOWN", 900, "b".repeat(64), NOW))).hasMessageContaining("不同身份或内容");
        verify(mapper, never()).advancePayment(any(), any(), anyLong(), any(), any(), any(), any());
    }

    private RequestReturn request(String kind, String paymentId, String quantity) {
        return new RequestReturn(COMMAND, "return-key-0001", RETURN, ORDER, 1101L, TERMINAL, SHIFT,
            WAREHOUSE, DAY, kind, paymentId, "CUSTOMER_RETURN", List.of(new RequestLine(ORDER_LINE, quantity)),
            CORRELATION, NOW);
    }

    private ReturnOrderSnapshot orderSnapshot(String cashPaymentId) {
        return new ReturnOrderSnapshot(ORDER, 1101L, TERMINAL, DAY, "COMPLETED", "PAID", "CNY",
            2000, 200, 0, 1800, SNAPSHOT, "a".repeat(64), cashPaymentId,
            List.of(new ReturnOrderLine(ORDER_LINE, 701L, 301L, new BigDecimal("2"), 2000, 200, 0, 1800)));
    }

    private ReturnRow row(Status status, String promotionEventId, String paymentEventId) {
        Long gross = status == Status.PENDING_APPROVAL || status == Status.PROMOTION_PENDING ? null : 1000L;
        Long discount = gross == null ? null : 100L;
        Long refundable = gross == null ? null : 900L;
        return new ReturnRow(RETURN, "d".repeat(64), ORDER, 1101L, TERMINAL, SHIFT, WAREHOUSE, DAY,
            status == Status.PAYMENT_PENDING || status == Status.PAYMENT_UNKNOWN ? "PROVIDER_NEUTRAL" : "CASH",
            status == Status.PAYMENT_PENDING || status == Status.PAYMENT_UNKNOWN ? CASH_PAYMENT : null,
            status == Status.PAYMENT_PENDING || status == Status.PAYMENT_UNKNOWN ? null : CASH_PAYMENT,
            SNAPSHOT, "a".repeat(64), status.name(), gross, discount, refundable, promotionEventId,
            paymentEventId, null, 101L, status == Status.PENDING_APPROVAL ? null : 102L,
            "CUSTOMER_RETURN", CORRELATION, status == Status.PENDING_APPROVAL ? 1 : 2, NOW_DB);
    }

    private LineRow line(Long gross, Long discount, Long refundable) {
        return new LineRow(RETURN_LINE, ORDER_LINE, 701L, 301L, BigDecimal.ONE, gross, discount,
            refundable, gross == null ? null : BigDecimal.ONE, refundable);
    }
}
