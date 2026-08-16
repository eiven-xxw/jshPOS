package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateAttempt;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateIntent;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.PaymentObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

/** Provider 无关支付核心应用事务、幂等和观察收敛回归。 */
class PaymentCoreServiceTest {

    private static final String TENANT = "TENANT_A";
    private static final String COMMAND = "01K2A000000000000000000001";
    private static final String PAYMENT = "01K2A000000000000000000002";
    private static final String ORDER = "01K2A000000000000000000003";
    private static final String TERMINAL = "01K2A000000000000000000004";
    private static final String ATTEMPT = "01K2A000000000000000000005";
    private static final String OBSERVATION = "01K2A000000000000000000006";
    private static final Instant AT = Instant.parse("2026-08-16T08:00:00Z");

    private PaymentMapper mapper;
    private TrustedTenantContext tenantContext;
    private ScopeAuthorizationService authorization;
    private PaymentOrderSnapshotPort orders;
    private PaymentIdempotencyService idempotency;
    private PaymentJournalService journal;
    private PaymentCoreService service;

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
        service = new PaymentCoreService(mapper, tenantContext, authorization, orders, idempotency, journal, ulids,
            Clock.fixed(AT, ZoneOffset.UTC));
    }

    @Test
    void createsIntentOnlyFromAuthoritativePayableOrderSnapshot() {
        when(orders.requireSnapshot(ORDER)).thenReturn(order());

        PaymentResult result = service.createIntent(intent());

        assertThat(result.status()).isEqualTo("CREATED");
        verify(mapper).insertPayment(TENANT, PAYMENT, ORDER, 1101L, TERMINAL, 1_299, "CNY",
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
        verify(authorization).requireStoreAccess(1101L);
        verify(journal).event(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), any(), any());
        verify(idempotency).save(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void returnsStoredIdempotentIntentWithoutSecondWrite() {
        when(idempotency.find(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new PaymentResult(PAYMENT, "CREATED", 1_299, "CNY", 1, false));

        assertThat(service.createIntent(intent()).duplicate()).isTrue();
        verify(mapper, never()).insertPayment(anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyLong(), anyString(), any());
        verify(orders, never()).requireSnapshot(anyString());
    }

    @Test
    void rejectsClientAmountThatDisagreesWithOrderOwner() {
        when(orders.requireSnapshot(ORDER)).thenReturn(new OrderPaymentSnapshot(ORDER, 1101L, TERMINAL,
            "PENDING_PAYMENT", "UNPAID", "CNY", 1_300, List.of()));
        assertThatThrownBy(() -> service.createIntent(intent())).isInstanceOf(ServiceException.class)
            .hasMessageContaining("PAY-ORDER-003");
        verify(mapper, never()).insertPayment(anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyLong(), anyString(), any());
    }

    @Test
    void createsStableAttemptWithoutProviderNetworkSideEffect() {
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("CREATED", 1));
        when(mapper.countAttempts(TENANT, PAYMENT)).thenReturn(0);
        when(mapper.updatePaymentStatus(TENANT, PAYMENT, "PROCESSING", 1)).thenReturn(1);

        AttemptResult result = service.createAttempt(new CreateAttempt(COMMAND, "attempt:tenant-a:0001", ATTEMPT,
            PAYMENT, "LAKALA", "req-a-0001", AT));

        assertThat(result.status()).isEqualTo("CREATED");
        verify(mapper).insertAttempt(TENANT, ATTEMPT, PAYMENT, "LAKALA", "req-a-0001", 1_299, "CNY",
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    @Test
    void unknownPaymentCannotCreateReplacementAttempt() {
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("UNKNOWN", 2));
        when(mapper.countAttempts(TENANT, PAYMENT)).thenReturn(1);
        assertThatThrownBy(() -> service.createAttempt(new CreateAttempt(COMMAND, "attempt:tenant-a:0001", ATTEMPT,
            PAYMENT, "LAKALA", "req-a-0001", AT))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("PAY-ATTEMPT-001");
        verify(mapper, never()).insertAttempt(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyLong(), anyString(), any());
    }

    @Test
    void querySuccessConvergesUnknownAndPersistsObservationBeforeProjectionUpdates() {
        PaymentObservation observation = observation("QUERY", "SUCCEEDED", "txn-a-0001");
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("UNKNOWN", 2));
        when(mapper.lockAttempt(TENANT, ATTEMPT)).thenReturn(attempt("UNKNOWN", null, 2));
        when(mapper.updateAttemptStatus(TENANT, ATTEMPT, "SUCCEEDED", "txn-a-0001", 2)).thenReturn(1);
        when(mapper.updatePaymentStatus(TENANT, PAYMENT, "SUCCEEDED", 2)).thenReturn(1);

        var result = service.acceptPayment(observation);

        assertThat(result.beforeStatus()).isEqualTo("UNKNOWN");
        assertThat(result.afterStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.outcome()).isEqualTo("APPLIED");
        verify(mapper).insertObservation(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(), anyString(),
            anyString(), any());
    }

    @Test
    void repeatedObservationIsDuplicateAndHashConflictIsDeadLettered() {
        PaymentObservation valid = observation("CALLBACK", "SUCCEEDED", "txn-a-0001");
        when(mapper.findObservation(TENANT, OBSERVATION)).thenReturn(
            new ObservationView(OBSERVATION, "PAYMENT", PAYMENT, valid.payloadHash(), "APPLIED"));
        when(mapper.findPayment(TENANT, PAYMENT)).thenReturn(payment("SUCCEEDED", 3));
        assertThat(service.acceptPayment(valid).duplicate()).isTrue();

        PaymentObservation changed = new PaymentObservation(valid.observationId(), valid.paymentId(), valid.attemptId(),
            valid.source(), valid.observedStatus(), valid.amountMinor(), valid.currency(), valid.providerCode(),
            valid.providerRequestNo(), valid.providerTransactionNo(), valid.observedAt(), "b".repeat(64));
        assertThat(service.acceptPayment(changed).outcome()).isEqualTo("CONFLICT");
        verify(mapper).insertDeadLetter(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    @Test
    void fakeObservationCannotEnterFormalRuntime() {
        PaymentObservation fake = observation("FAKE_TEST", "UNKNOWN", null);
        assertThatThrownBy(() -> service.acceptPayment(fake)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("PAY-OBS-003");
        verify(mapper, never()).findObservation(anyString(), anyString());
    }

    @Test
    void crossAggregateAttemptIsDeadLetteredWithoutStateMutation() {
        PaymentObservation observation = observation("QUERY", "UNKNOWN", null);
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("PROCESSING", 1));
        when(mapper.lockAttempt(TENANT, ATTEMPT)).thenReturn(new AttemptView(ATTEMPT,
            "01K2B000000000000000000002", "LAKALA", "req-a-0001", null, "PROCESSING", 1_299, "CNY", 1));
        assertThat(service.acceptPayment(observation).outcome()).isEqualTo("CONFLICT");
        verify(mapper, never()).updatePaymentStatus(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void mismatchedAmountIsIsolatedWithoutStateMutation() {
        PaymentObservation valid = observation("QUERY", "UNKNOWN", null);
        PaymentObservation tampered = new PaymentObservation(valid.observationId(), valid.paymentId(), valid.attemptId(),
            valid.source(), valid.observedStatus(), 1_300, valid.currency(), valid.providerCode(),
            valid.providerRequestNo(), valid.providerTransactionNo(), valid.observedAt(), valid.payloadHash());
        when(mapper.lockPayment(TENANT, PAYMENT)).thenReturn(payment("PROCESSING", 1));
        when(mapper.lockAttempt(TENANT, ATTEMPT)).thenReturn(attempt("PROCESSING", null, 1));
        assertThat(service.acceptPayment(tampered).outcome()).isEqualTo("CONFLICT");
        verify(mapper, never()).updatePaymentStatus(anyString(), anyString(), anyString(), anyLong());
        verify(mapper, never()).updateAttemptStatus(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void observationIdentityCannotBeReplayedAcrossAggregateTypes() {
        PaymentObservation valid = observation("QUERY", "UNKNOWN", null);
        when(mapper.findObservation(TENANT, OBSERVATION)).thenReturn(new ObservationView(OBSERVATION, "REFUND",
            "01K2A000000000000000000088", valid.payloadHash(), "APPLIED"));
        assertThat(service.acceptPayment(valid).outcome()).isEqualTo("CONFLICT");
        verify(mapper, never()).findPayment(anyString(), anyString());
        verify(mapper).insertDeadLetter(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), any());
    }

    private CreateIntent intent() {
        return new CreateIntent(COMMAND, "intent:tenant-a:0001", PAYMENT, ORDER, 1101L, TERMINAL, 1_299, "CNY", AT);
    }

    private OrderPaymentSnapshot order() {
        return new OrderPaymentSnapshot(ORDER, 1101L, TERMINAL, "PENDING_PAYMENT", "UNPAID", "CNY", 1_299,
            List.of());
    }

    private PaymentView payment(String status, long version) {
        return new PaymentView(PAYMENT, ORDER, 1101L, TERMINAL, status, 1_299, "CNY", 0, version,
            LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    private AttemptView attempt(String status, String transaction, long version) {
        return new AttemptView(ATTEMPT, PAYMENT, "LAKALA", "req-a-0001", transaction, status, 1_299, "CNY", version);
    }

    private PaymentObservation observation(String source, String status, String transaction) {
        String hash = PaymentHash.sha256(PaymentHash.canonical(List.of(OBSERVATION, PAYMENT, ATTEMPT, source, status,
            1_299, "CNY", "LAKALA", "req-a-0001", String.valueOf(transaction), AT.toString())));
        return new PaymentObservation(OBSERVATION, PAYMENT, ATTEMPT, source, status, 1_299, "CNY", "LAKALA",
            "req-a-0001", transaction, AT, hash);
    }
}
