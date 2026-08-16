package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RunReconciliation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.StatementEntry;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TransitionCase;
import com.jingshanghui.pos.payment.application.model.PaymentViews.InternalFactView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationCaseView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付、退款与受控账单的双源对账和四眼处置回归。 */
class ReconciliationServiceTest {

    private static final String TENANT = "TENANT_A";
    private static final String COMMAND = "01K2A000000000000000000021";
    private static final String RUN = "01K2A000000000000000000022";
    private static final String ENTRY1 = "01K2A000000000000000000023";
    private static final String ENTRY2 = "01K2A000000000000000000024";
    private static final String CASE = "01K2A000000000000000000025";
    private static final Instant AT = Instant.parse("2026-08-16T10:00:00Z");

    private PaymentMapper mapper;
    private TrustedTenantContext tenantContext;
    private PaymentIdempotencyService idempotency;
    private PaymentJournalService journal;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(PaymentMapper.class);
        tenantContext = mock(TrustedTenantContext.class);
        idempotency = mock(PaymentIdempotencyService.class);
        journal = mock(PaymentJournalService.class);
        UlidGenerator ulids = mock(UlidGenerator.class);
        when(ulids.next()).thenReturn("01K2A000000000000000000090", "01K2A000000000000000000091",
            "01K2A000000000000000000092");
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 301L, 1001L, "finance-a"));
        service = new ReconciliationService(mapper, tenantContext, idempotency, journal, ulids);
    }

    @Test
    void opensStableCasesForDuplicateProviderAmountMismatchAndInternalOnly() {
        StatementEntry first = entry(ENTRY1, "txn-a-1", 1_300);
        StatementEntry duplicate = entry(ENTRY2, "txn-a-1", 1_300);
        RunReconciliation command = new RunReconciliation(COMMAND, "reconcile:tenant-a:01", RUN, "LAKALA",
            LocalDate.of(2026, 8, 16), List.of(first, duplicate), AT);
        when(mapper.findInternalFactsByReference(TENANT, "LAKALA", "txn-a-1")).thenReturn(List.of(
            new InternalFactView("txn-a-1", "01K2A000000000000000000030", "PAYMENT", "SUCCEEDED", 1_299, "CNY",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC))));
        when(mapper.findInternalFacts(anyString(), anyString(), any(), any())).thenReturn(List.of(
            new InternalFactView("txn-a-1", "01K2A000000000000000000030", "PAYMENT", "SUCCEEDED", 1_299, "CNY",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC)),
            new InternalFactView("txn-a-2", "01K2A000000000000000000031", "PAYMENT", "SUCCEEDED", 500, "CNY",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC))));
        when(mapper.completeReconciliationRun(TENANT, RUN, 3)).thenReturn(1);

        var result = service.run(command);

        assertThat(result.statementEntries()).isEqualTo(2);
        assertThat(result.casesOpened()).isEqualTo(3);
        verify(mapper).insertReconciliationCase(TENANT, "01K2A000000000000000000090", RUN,
            "DUPLICATE_PROVIDER_REF", null, "txn-a-1", LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
        verify(mapper).completeReconciliationRun(TENANT, RUN, 3);
        verify(mapper, never()).updatePaymentStatus(anyString(), anyString(), anyString(), anyLong());
        verify(mapper, never()).updateRefundStatus(anyString(), anyString(), anyString(), any(), any(), anyLong());
    }

    @Test
    void returnsStoredRunForSameIdempotentRequest() {
        StatementEntry entry = entry(ENTRY1, "txn-a-1", 1_299);
        when(idempotency.find(anyString(), anyString(), anyString(), anyString(), any()))
            .thenReturn(new com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationResult(
                RUN, 1, 0, false));
        var result = service.run(new RunReconciliation(COMMAND, "reconcile:tenant-a:01", RUN, "LAKALA",
            LocalDate.of(2026, 8, 16), List.of(entry), AT));
        assertThat(result.duplicate()).isTrue();
        verify(mapper, never()).insertReconciliationRun(anyString(), anyString(), anyString(), any(), anyInt(),
            anyLong(), any());
    }

    @Test
    void sameProviderReferenceWithOppositeBusinessTypeOpensRefundMismatchOnlyOnce() {
        StatementEntry statementEntry = entry(ENTRY1, "txn-a-1", 1_299);
        when(mapper.findInternalFactsByReference(TENANT, "LAKALA", "txn-a-1")).thenReturn(List.of(
            new InternalFactView("txn-a-1", "01K2A000000000000000000032", "REFUND", "SUCCEEDED", 1_299, "CNY",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC))));
        when(mapper.findInternalFacts(anyString(), anyString(), any(), any())).thenReturn(List.of(
            new InternalFactView("txn-a-1", "01K2A000000000000000000032", "REFUND", "SUCCEEDED", 1_299, "CNY",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC))));
        when(mapper.completeReconciliationRun(TENANT, RUN, 1)).thenReturn(1);
        var result = service.run(new RunReconciliation(COMMAND, "reconcile:tenant-a:02", RUN, "LAKALA",
            LocalDate.of(2026, 8, 16), List.of(statementEntry), AT));
        assertThat(result.casesOpened()).isEqualTo(1);
        verify(mapper).insertReconciliationCase(TENANT, "01K2A000000000000000000090", RUN,
            "REFUND_MISMATCH", "txn-a-1", "txn-a-1", LocalDateTime.ofInstant(AT, ZoneOffset.UTC));
    }

    @Test
    void rejectsTamperedStatementDigestBeforePersistence() {
        StatementEntry tampered = new StatementEntry(ENTRY1, "txn-a-1", "PAYMENT", "SUCCEEDED", 1_299,
            "CNY", AT, "a".repeat(64));
        RunReconciliation command = new RunReconciliation(COMMAND, "reconcile:tenant-a:01", RUN, "LAKALA",
            LocalDate.of(2026, 8, 16), List.of(tampered), AT);
        assertThatThrownBy(() -> service.run(command)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("REC-ENTRY-002");
        verify(mapper, never()).insertStatementEntry(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    void resolverAndApproverMustBeDifferentBeforeClosingCase() {
        when(mapper.lockReconciliationCase(TENANT, CASE)).thenReturn(new ReconciliationCaseView(CASE, RUN,
            "AMOUNT_MISMATCH", "txn-a-1", "txn-a-1", "RESOLVED", 301L, null, 3));
        TransitionCase approve = new TransitionCase(COMMAND, CASE, "APPROVED", "MATCHED_MANUALLY",
            "已核验渠道原始凭证", AT);
        assertThatThrownBy(() -> service.transition(approve)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("REC-RBAC-001");

        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 302L, 1001L, "auditor-a"));
        when(mapper.updateReconciliationCase(TENANT, CASE, "APPROVED", null, 302L, "MATCHED_MANUALLY",
            "已核验渠道原始凭证", 3)).thenReturn(1);
        service.transition(approve);
        verify(mapper).updateReconciliationCase(TENANT, CASE, "APPROVED", null, 302L, "MATCHED_MANUALLY",
            "已核验渠道原始凭证", 3);
    }

    @Test
    void invalidLifecycleIsRejectedWithoutMutation() {
        when(mapper.lockReconciliationCase(TENANT, CASE)).thenReturn(new ReconciliationCaseView(CASE, RUN,
            "AMOUNT_MISMATCH", "txn-a-1", "txn-a-1", "OPEN", null, null, 1));
        assertThatThrownBy(() -> service.transition(new TransitionCase(COMMAND, CASE, "CLOSED", "FORCE_CLOSE",
            "禁止跨越审批状态", AT))).isInstanceOf(ServiceException.class).hasMessageContaining("REC-STATE-001");
        verify(mapper, never()).updateReconciliationCase(anyString(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), anyLong());
    }

    private StatementEntry entry(String id, String reference, long amount) {
        String hash = PaymentHash.sha256(PaymentHash.canonical(List.of(id, reference, "PAYMENT", "SUCCEEDED",
            amount, "CNY", AT)));
        return new StatementEntry(id, reference, "PAYMENT", "SUCCEEDED", amount, "CNY", AT, hash);
    }
}
