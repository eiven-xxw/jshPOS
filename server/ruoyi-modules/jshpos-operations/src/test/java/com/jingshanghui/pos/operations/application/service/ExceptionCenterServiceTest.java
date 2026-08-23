package com.jingshanghui.pos.operations.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairResult;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerObservation;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;
import com.jingshanghui.pos.operations.application.port.ExceptionOwnerGateway;
import com.jingshanghui.pos.operations.application.port.ExceptionOwnerGateway.OwnedObservation;
import com.jingshanghui.pos.operations.application.port.ExceptionPersistencePort;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 异常中心租约、幂等、Owner失败关闭和职责分离故障回归。 */
@ExtendWith(MockitoExtension.class)
class ExceptionCenterServiceTest {
    private static final String CASE_ID = "01K3N000000000000000000001";
    private static final String HASH = "a".repeat(64);
    @Mock TrustedTenantContext context;
    @Mock ScopeAuthorizationService authorization;
    @Mock ExceptionPersistencePort persistence;
    @Mock ExceptionOwnerGateway owners;
    @Mock UlidGenerator ids;
    ExceptionCenterService service;

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "operator"));
        lenient().when(ids.next()).thenReturn("01K3N000000000000000000099");
        service = new ExceptionCenterService(context, authorization, persistence, owners, ids,
            Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void claimUsesLeaseOptimisticLockAndReplaysOriginalCommand() {
        CaseRecord open = record("OPEN", null, null, 0);
        CaseRecord claimed = record("CLAIMED", 101L, LocalDateTime.of(2026, 8, 23, 0, 30), 1);
        when(persistence.lock("TENANT_A", CASE_ID)).thenReturn(open);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find("TENANT_A", CASE_ID)).thenReturn(claimed);

        CaseDetail first = service.claim(new ClaimCommand(CASE_ID, 30, "exc-claim-001", "trace-001"));
        assertThat(first.exceptionCase().state()).isEqualTo("CLAIMED");
        verify(persistence).insertLeaseEvent(any());
        ArgumentCaptor<ExceptionPersistencePort.CommandWrite> command = ArgumentCaptor.forClass(ExceptionPersistencePort.CommandWrite.class);
        verify(persistence).insertCommand(command.capture());

        when(persistence.findCommand("TENANT_A", "CLAIM", "exc-claim-001")).thenReturn(
            new CommandRecord("01K3N000000000000000000098", CASE_ID, "CLAIM", "exc-claim-001",
                command.getValue().requestSha256(), "CLAIMED", LocalDateTime.MIN));
        CaseDetail replay = service.claim(new ClaimCommand(CASE_ID, 30, "exc-claim-001", "trace-after-ack-loss"));
        assertThat(replay.exceptionCase()).isEqualTo(claimed);
        verify(persistence, times(1)).changeState(any());
    }

    @Test
    void sameIdempotencyKeyWithDifferentLeaseFailsClosed() {
        when(persistence.findCommand("TENANT_A", "CLAIM", "exc-claim-002")).thenReturn(
            new CommandRecord("01K3N000000000000000000097", CASE_ID, "CLAIM", "exc-claim-002",
                HASH, "CLAIMED", LocalDateTime.MIN));
        assertThatThrownBy(() -> service.claim(new ClaimCommand(CASE_ID, 60, "exc-claim-002", "trace-002")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("同幂等键异内容");
        verify(persistence, never()).lock(any(), any());
    }

    @Test
    void unavailableOwnerRepairRemainsWaitingAndNeverCreatesGreenResult() {
        CaseRecord inProgress = record("IN_PROGRESS", 101L, LocalDateTime.of(2026, 8, 23, 0, 30), 2);
        CaseRecord waiting = record("WAITING_OWNER", 101L, LocalDateTime.of(2026, 8, 23, 0, 30), 3);
        when(persistence.lock("TENANT_A", CASE_ID)).thenReturn(inProgress);
        when(persistence.latestPlan("TENANT_A", CASE_ID)).thenReturn(new PlanRecord(
            "01K3N000000000000000000096", CASE_ID, "OBSERVE_ORIGINAL_PAYMENT", HASH, 101L, "ACTIVE", LocalDateTime.MIN));
        when(owners.repair(eq("PAYMENT_REFUND"), any())).thenReturn(
            new OwnerRepairResult("UNAVAILABLE", "PAYMENT_PROVIDER_BLOCKED", null, "外部Provider未解阻"));
        when(persistence.updateRepairResult(any())).thenReturn(1);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find("TENANT_A", CASE_ID)).thenReturn(waiting);

        CaseDetail result = service.repair(new RepairCommand(CASE_ID, "OBSERVE_ORIGINAL_PAYMENT", "exc-repair-001", "trace-003"));
        assertThat(result.exceptionCase().state()).isEqualTo("WAITING_OWNER");
        verify(persistence).updateRepairResult(argThat(value -> "UNAVAILABLE".equals(value.toState())));
        verify(persistence, never()).insertReview(any());
    }

    @Test
    void resolverCannotPerformIndependentReview() {
        CaseRecord resolved = record("RESOLVED", 101L, LocalDateTime.of(2026, 8, 23, 0, 30), 4);
        when(persistence.lock("TENANT_A", CASE_ID)).thenReturn(resolved);
        assertThatThrownBy(() -> service.review(new CaseCommand(CASE_ID, "已核验Owner结果和来源摘要", "exc-review-001", "trace-004")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("职责分离");
        verify(persistence, never()).insertReview(any());
    }

    @Test
    void lostRepairResultUpdateFailsClosedBeforeCaseTransition() {
        CaseRecord inProgress = record("IN_PROGRESS", 101L, LocalDateTime.of(2026, 8, 23, 0, 30), 2);
        when(persistence.lock("TENANT_A", CASE_ID)).thenReturn(inProgress);
        when(persistence.latestPlan("TENANT_A", CASE_ID)).thenReturn(new PlanRecord(
            "01K3N000000000000000000095", CASE_ID, "REBUILD_REPORTING", HASH, 101L, "ACTIVE", LocalDateTime.MIN));
        when(owners.repair(eq("PAYMENT_REFUND"), any())).thenReturn(new OwnerRepairResult("SUCCEEDED", "result-1", HASH, "已完成"));
        when(persistence.updateRepairResult(any())).thenReturn(0);
        assertThatThrownBy(() -> service.repair(new RepairCommand(CASE_ID, "REBUILD_REPORTING", "exc-repair-002", "trace-005")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("修复结果并发冲突");
        verify(persistence, never()).changeState(any());
    }

    @Test
    void outOfOrderObservationIsAppendedWithoutOverwritingLatestSourceHead() {
        CaseRecord latest = new CaseRecord(CASE_ID, "TENANT_A", 10L, "SYNC", "SYNC_DEAD_LETTER",
            "sync-fact-001", "SYNC:sync-fact-001", "P1", "OPEN", "source-event-010", 10,
            HASH, null, null, null, null, 3, LocalDateTime.MIN, LocalDateTime.MIN,
            LocalDateTime.MIN, LocalDateTime.MIN);
        OwnerObservation late = new OwnerObservation("SYNC_DEAD_LETTER", "sync-fact-001", "source-event-009",
            9, "b".repeat(64), "sync-fact-001", "P1", "trace-late-009",
            LocalDateTime.of(2026, 8, 22, 23, 59), "乱序同步死信观察", "RETRY_ORIGINAL_SYNC_EVENT");
        when(owners.scan(10L, LocalDate.of(2026, 8, 23), 100)).thenReturn(List.of(new OwnedObservation("SYNC", late)));
        when(persistence.findByDedup("TENANT_A", 10L, "SYNC:sync-fact-001")).thenReturn(latest);
        when(persistence.find("TENANT_A", CASE_ID)).thenReturn(latest);
        when(persistence.list("TENANT_A", 10L, null, null, 100)).thenReturn(List.of(latest));

        service.scan(new ScanCommand(10L, LocalDate.of(2026, 8, 23), "exc-scan-001", "trace-scan-001"));

        verify(persistence, never()).updateObservationHead(any());
        verify(persistence).insertObservation(argThat(value -> "OUT_OF_ORDER".equals(value.conflictFlag())));
        verify(persistence).appendAudit(argThat(value -> "OWNER_OBSERVED".equals(value.actionCode())));
    }

    private CaseRecord record(String state, Long assignee, LocalDateTime lease, int version) {
        return new CaseRecord(CASE_ID, "TENANT_A", 10L, "PAYMENT_REFUND", "PAYMENT_UNKNOWN",
            "payment-attempt-001", "PAYMENT_REFUND:payment-attempt-001", "P1", state, "source-event-001",
            1, HASH, assignee, lease, state.equals("RESOLVED") ? 101L : null, null, version,
            LocalDateTime.MIN, LocalDateTime.MIN, LocalDateTime.MIN, LocalDateTime.MIN);
    }
}
