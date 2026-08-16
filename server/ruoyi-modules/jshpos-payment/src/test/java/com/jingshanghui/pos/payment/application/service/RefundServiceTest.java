package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.LineSnapshot;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.ApproveRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundLineView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 原单退款创建、四眼审批、占额与 UNKNOWN 收敛应用回归。 */
class RefundServiceTest {

    private static final String TENANT = "TENANT_A";
    private static final String COMMAND = "01K2A000000000000000000011";
    private static final String PAYMENT = "01K2A000000000000000000012";
    private static final String ORDER = "01K2A000000000000000000013";
    private static final String TERMINAL = "01K2A000000000000000000014";
    private static final String ATTEMPT = "01K2A000000000000000000015";
    private static final String REFUND = "01K2A000000000000000000016";
    private static final String LINE = "01K2A000000000000000000017";
    private static final String OBSERVATION = "01K2A000000000000000000018";
    private static final Instant AT = Instant.parse("2026-08-16T09:00:00Z");

    private PaymentMapper mapper;
    private TrustedTenantContext tenantContext;
    private ScopeAuthorizationService authorization;
    private PaymentOrderSnapshotPort orders;
    private PaymentIdempotencyService idempotency;
    private PaymentJournalService journal;
    private RefundService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PaymentMapper.class);
        tenantContext = mock(TrustedTenantContext.class);
        authorization = mock(ScopeAuthorizationService.class);
        orders = mock(PaymentOrderSnapshotPort.class);
        idempotency = mock(PaymentIdempotencyService.class);
        journal = mock(PaymentJournalService.class);
        UlidGenerator ulids = mock(UlidGenerator.class);
        when(ulids.next()).thenReturn("01K2A000000000000000000099");
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 101L, 1001L, "cashier-a"));
        when(tenantContext.requireTenantId()).thenReturn(TENANT);
        service = new RefundService(mapper, tenantContext, authorization, orders, idempotency, journal, ulids,
            Clock.fixed(AT, ZoneOffset.UTC));
    }

    @Test
    void createsPendingApprovalRefundAgainstSuccessfulOriginalPayment() {
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        when(orders.requireSnapshot(ORDER)).thenReturn(order());
        when(mapper.findSucceededAttempt(TENANT, PAYMENT)).thenReturn(attempt());

        var result = service.create(create());

        assertThat(result.status()).isEqualTo("PENDING_APPROVAL");
        verify(mapper).insertRefund(TENANT, REFUND, PAYMENT, ORDER, 1101L, "PENDING_APPROVAL", 500, "CNY",
            "CUSTOMER_RETURN", 101L, "LAKALA", REFUND, LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
        verify(mapper).insertRefundLine(TENANT, "01K2A000000000000000000099", REFUND, LINE,
            new BigDecimal("1.000000"), 500);
    }

    @Test
    void rejectsNonOriginalLineAndPaymentWithoutConfirmedProviderTransaction() {
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        when(orders.requireSnapshot(ORDER)).thenReturn(order());
        CreateRefund alien = new CreateRefund(COMMAND, "refund:tenant-a:0001", REFUND, PAYMENT, ORDER, 500, "CNY",
            "CUSTOMER_RETURN", List.of(new com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundLine(
            "01K2B000000000000000000017", "1.000000", 500)), AT);
        assertThatThrownBy(() -> service.create(alien)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("REF-LINE-004");

        when(mapper.findSucceededAttempt(TENANT, PAYMENT)).thenReturn(new AttemptView(ATTEMPT, PAYMENT, "LAKALA",
            "req-a-0001", null, "SUCCEEDED", 1_299, "CNY", 2));
        assertThatThrownBy(() -> service.create(create())).isInstanceOf(ServiceException.class)
            .hasMessageContaining("REF-PAY-002");
    }

    @Test
    void requesterCannotApproveOwnRefund() {
        when(mapper.lockRefund(TENANT, REFUND)).thenReturn(refund("PENDING_APPROVAL", 101L, null, 1));
        assertThatThrownBy(() -> service.approve(new ApproveRefund(COMMAND, REFUND, "APPROVED", AT)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("REF-RBAC-001");
        verify(mapper, never()).updateRefundStatus(anyString(), anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void independentApproverRechecksMoneyAndQuantityUnderPaymentLock() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 202L, 1001L, "manager-a"));
        when(mapper.lockRefund(TENANT, REFUND)).thenReturn(refund("PENDING_APPROVAL", 101L, null, 1));
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        when(mapper.findRefundLines(TENANT, REFUND)).thenReturn(List.of(
            new RefundLineView(LINE, new BigDecimal("1.000000"), 500)));
        when(orders.requireSnapshot(ORDER)).thenReturn(order());
        when(mapper.sumReservedRefundAmount(TENANT, PAYMENT)).thenReturn(200L);
        when(mapper.findReservedQuantities(TENANT, PAYMENT)).thenReturn(List.of());
        when(mapper.updateRefundStatus(TENANT, REFUND, "PROCESSING", 202L, null, 1)).thenReturn(1);

        service.approve(new ApproveRefund(COMMAND, REFUND, "APPROVED", AT));

        verify(mapper).updateRefundStatus(TENANT, REFUND, "PROCESSING", 202L, null, 1);
        verify(journal).audit(anyString(), anyLong(), anyString(), anyString(), anyString(), anyLong(), anyLong(),
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void approvalRejectsCumulativeRefundAmountBeyondOriginalPayment() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 202L, 1001L, "manager-a"));
        when(mapper.lockRefund(TENANT, REFUND)).thenReturn(refund("PENDING_APPROVAL", 101L, null, 1));
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        when(mapper.findRefundLines(TENANT, REFUND)).thenReturn(List.of(
            new RefundLineView(LINE, new BigDecimal("1.000000"), 500)));
        when(orders.requireSnapshot(ORDER)).thenReturn(order());
        when(mapper.sumReservedRefundAmount(TENANT, PAYMENT)).thenReturn(1_000L);
        assertThatThrownBy(() -> service.approve(new ApproveRefund(COMMAND, REFUND, "APPROVED", AT)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("REF-LIMIT-001");
    }

    @Test
    void callbackSuccessConvergesUnknownAndUpdatesPaymentRefundAggregate() {
        RefundObservation observation = observation("CALLBACK", "SUCCEEDED", "refund-txn-a-1");
        when(mapper.lockRefund(TENANT, REFUND)).thenReturn(refund("UNKNOWN", 101L, 202L, 3));
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        when(mapper.updateRefundStatus(TENANT, REFUND, "SUCCEEDED", null, "refund-txn-a-1", 3)).thenReturn(1);
        when(mapper.sumSucceededRefundAmount(TENANT, PAYMENT)).thenReturn(500L);
        when(mapper.updatePaymentRefund(TENANT, PAYMENT, "PARTIALLY_REFUNDED", 500, 3)).thenReturn(1);

        var result = service.acceptObservation(observation);

        assertThat(result.beforeStatus()).isEqualTo("UNKNOWN");
        assertThat(result.afterStatus()).isEqualTo("SUCCEEDED");
        verify(mapper).updatePaymentRefund(TENANT, PAYMENT, "PARTIALLY_REFUNDED", 500, 3);
    }

    @Test
    void unapprovedOrFakeRefundObservationCannotMoveFunds() {
        RefundObservation valid = observation("QUERY", "UNKNOWN", null);
        when(mapper.lockRefund(TENANT, REFUND)).thenReturn(refund("PENDING_APPROVAL", 101L, null, 1));
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 0, 3));
        assertThat(service.acceptObservation(valid).outcome()).isEqualTo("CONFLICT");
        verify(mapper, never()).updateRefundStatus(anyString(), anyString(), anyString(), any(), any(), anyLong());

        RefundObservation fake = observation("FAKE_TEST", "UNKNOWN", null);
        assertThatThrownBy(() -> service.acceptObservation(fake)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("REF-OBS-003");
    }

    @Test
    void duplicateRefundObservationReturnsCurrentRefundStatusAndRejectsIdentityReplay() {
        RefundObservation valid = observation("QUERY", "UNKNOWN", null);
        when(mapper.findObservation(TENANT, OBSERVATION)).thenReturn(new ObservationView(OBSERVATION, "REFUND",
            REFUND, valid.payloadHash(), "APPLIED"));
        when(mapper.findRefund(TENANT, REFUND)).thenReturn(refund("UNKNOWN", 101L, 202L, 3));
        var duplicate = service.acceptObservation(valid);
        assertThat(duplicate.duplicate()).isTrue();
        assertThat(duplicate.afterStatus()).isEqualTo("UNKNOWN");

        when(mapper.findObservation(TENANT, OBSERVATION)).thenReturn(new ObservationView(OBSERVATION, "PAYMENT",
            PAYMENT, valid.payloadHash(), "APPLIED"));
        assertThat(service.acceptObservation(valid).outcome()).isEqualTo("CONFLICT");
        verify(mapper).insertDeadLetter(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    private CreateRefund create() {
        return new CreateRefund(COMMAND, "refund:tenant-a:0001", REFUND, PAYMENT, ORDER, 500, "CNY",
            "CUSTOMER_RETURN", List.of(new com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundLine(
            LINE, "1.000000", 500)), AT);
    }

    private PaymentView payment(String status, long succeededRefund, long version) {
        return new PaymentView(PAYMENT, ORDER, 1101L, TERMINAL, status, 1_299, "CNY", succeededRefund, version,
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    private AttemptView attempt() {
        return new AttemptView(ATTEMPT, PAYMENT, "LAKALA", "req-a-0001", "txn-a-0001", "SUCCEEDED", 1_299,
            "CNY", 2);
    }

    private RefundView refund(String status, Long requester, Long approver, long version) {
        return new RefundView(REFUND, PAYMENT, ORDER, 1101L, status, 500, "CNY", requester, approver, "LAKALA",
            REFUND, null, version);
    }

    private OrderPaymentSnapshot order() {
        return new OrderPaymentSnapshot(ORDER, 1101L, TERMINAL, "COMPLETED", "PAID", "CNY", 1_299,
            List.of(new LineSnapshot(LINE, new BigDecimal("2.000000"), 1_000)));
    }

    private RefundObservation observation(String source, String status, String providerRefundNo) {
        String hash = PaymentHash.sha256(PaymentHash.canonical(List.of(OBSERVATION, REFUND, source, status, 500,
            "CNY", "LAKALA", REFUND, String.valueOf(providerRefundNo), AT.toString())));
        return new RefundObservation(OBSERVATION, REFUND, source, status, 500, "CNY", "LAKALA", REFUND,
            providerRefundNo, AT, hash);
    }
}
