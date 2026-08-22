package com.jingshanghui.pos.onboarding.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.*;
import com.jingshanghui.pos.onboarding.application.port.OnboardingOwnerGateway;
import com.jingshanghui.pos.onboarding.application.port.OnboardingPersistencePort;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {
    private static final String PLAN_ID = "01K3M000000000000000000001";
    private static final String HASH = "a".repeat(64);
    @Mock TrustedTenantContext context;
    @Mock ScopeAuthorizationService authorization;
    @Mock OnboardingPersistencePort persistence;
    @Mock OnboardingOwnerGateway owners;
    @Mock UlidGenerator ids;
    @Mock PlatformTransactionManager transactionManager;
    OnboardingService service;

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "admin"));
        lenient().when(ids.next()).thenReturn(PLAN_ID);
        service = new OnboardingService(context, authorization, persistence, owners, ids,
            Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC), transactionManager);
    }

    @Test
    void createsFrozenPlanAndReplaysSameIdempotencyKey() {
        OwnerSnapshot snapshot = snapshot(2);
        PlanRecord created = plan("DRAFT", 0, 2, 101L);
        when(owners.capture(10L, 20L, 30L, 40L)).thenReturn(snapshot);
        when(persistence.findPlan("TENANT_A", PLAN_ID)).thenReturn(created);

        PlanDetail result = service.create(new CreatePlan(10L, 20L, 30L, 40L,
            "onb-create-001", "trace-001"));

        assertThat(result.plan().state()).isEqualTo("DRAFT");
        verify(persistence).insertPlan(any());
        verify(persistence).appendState(any());
        verify(persistence).appendAudit(any());
        verify(persistence).appendOutbox(any());

        ArgumentCaptor<OnboardingPersistencePort.PlanWrite> write = ArgumentCaptor.forClass(
            OnboardingPersistencePort.PlanWrite.class);
        verify(persistence).insertPlan(write.capture());
        PlanRecord replayRecord = plan("DRAFT", 0, 2, 101L, write.getValue().requestSha256());
        reset(owners);
        when(persistence.findPlanByIdempotency("TENANT_A", "onb-create-001")).thenReturn(replayRecord);
        assertThat(service.create(new CreatePlan(10L, 20L, 30L, 40L,
            "onb-create-001", "trace-002")).plan()).isEqualTo(replayRecord);
        verifyNoInteractions(owners);
    }

    @Test
    void preflightDetectsVersionDriftAndFailsClosed() {
        PlanRecord draft = plan("DRAFT", 0, 2, 101L);
        PlanRecord preflighting = plan("PREFLIGHTING", 1, 2, 101L);
        PlanRecord failed = plan("PREFLIGHT_FAILED", 2, 2, 101L);
        when(persistence.lockPlan("TENANT_A", PLAN_ID)).thenReturn(draft);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.findPlan("TENANT_A", PLAN_ID)).thenReturn(preflighting, failed);
        when(owners.capture(10L, 20L, 30L, 40L)).thenReturn(snapshot(99));

        PlanDetail result = service.preflight(new PlanCommand(PLAN_ID, "preflight-001", "trace-001"));

        assertThat(result.plan().state()).isEqualTo("PREFLIGHT_FAILED");
        verify(persistence, never()).insertSnapshot(any());
        verify(persistence).insertCommand(any());
    }

    @Test
    void preflightFreezesExplicitNullOptionalConfig() {
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("device.expectation", null);
        items.put("ui.layout", "compact");
        String snapshotHash = com.jingshanghui.pos.foundation.domain.CanonicalJson.from(items).sha256();
        PlanRecord draft = plan("DRAFT", 0, 2, 101L, HASH, snapshotHash);
        PlanRecord preflighting = plan("PREFLIGHTING", 1, 2, 101L, HASH, snapshotHash);
        PlanRecord ready = plan("READY", 2, 2, 101L, HASH, snapshotHash);
        when(persistence.lockPlan("TENANT_A", PLAN_ID)).thenReturn(draft);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.findPlan("TENANT_A", PLAN_ID)).thenReturn(preflighting, ready);
        when(owners.capture(10L, 20L, 30L, 40L)).thenReturn(new OwnerSnapshot(
            10L, 1, 20L, 2, 30L, 40L, 3, HASH, "CONVENIENCE", items));

        PlanDetail result = service.preflight(new PlanCommand(PLAN_ID, "preflight-null-001", "trace-001"));

        assertThat(result.plan().state()).isEqualTo("READY");
        verify(persistence, times(2)).insertSnapshot(any());
    }

    @Test
    void rejectsSelfApprovalBeforeAnyOwnerEffect() {
        when(persistence.lockPlan("TENANT_A", PLAN_ID)).thenReturn(plan("READY", 1, 2, 101L));
        assertThatThrownBy(() -> service.approve(new ReasonCommand(PLAN_ID, "审批通过",
            "approval-001", "trace-001"))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("创建人与审批人必须分离");
        verify(persistence, never()).insertApproval(any());
    }

    @Test
    void rejectsIncompleteOwnerCheckSetAndBlockedExternalOpen() {
        when(persistence.lockPlan("TENANT_A", PLAN_ID)).thenReturn(plan("APPLIED", 3, 2, 102L));
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.findPlan("TENANT_A", PLAN_ID)).thenReturn(plan("CHECKING", 4, 2, 102L));
        when(persistence.nextCheckRun("TENANT_A", PLAN_ID)).thenReturn(1);
        when(owners.checks(any(), eq(1))).thenReturn(List.of(new CheckFact("STORE_ORG", "FOUNDATION", true,
            false, "1", HASH, CheckStatus.PASS, "通过")));
        assertThatThrownBy(() -> service.checks(new PlanCommand(PLAN_ID, "checks-001", "trace-001")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("检查集不完整");

        reset(persistence);
        reset(owners);
        when(persistence.lockPlan("TENANT_A", PLAN_ID)).thenReturn(plan("READY_TO_OPEN", 5, 2, 102L));
        when(persistence.listLatestChecks("TENANT_A", PLAN_ID)).thenReturn(List.of(new CheckRecord(PLAN_ID,
            "TENANT_A", PLAN_ID, 1, "PAYMENT_EXTERNAL", "PAYMENT", true, true, "BLOCKED", HASH,
            CheckStatus.BLOCKED, "外部阻断", LocalDateTime.MIN)));
        assertThatThrownBy(() -> service.open(new ReasonCommand(PLAN_ID, "正式开店",
            "open-key-001", "trace-002"))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("禁止形成 OPENED");
        verifyNoInteractions(owners);
    }

    private static OwnerSnapshot snapshot(int targetVersion) {
        return new OwnerSnapshot(10L, 1, 20L, targetVersion, 30L, 40L, 3, HASH,
            "CONVENIENCE", Map.of("ui.layout", "compact"));
    }

    private static PlanRecord plan(String state, int recordVersion, int targetVersion, long creator) {
        return plan(state, recordVersion, targetVersion, creator, HASH);
    }

    private static PlanRecord plan(String state, int recordVersion, int targetVersion, long creator,
                                   String requestHash) {
        return plan(state, recordVersion, targetVersion, creator, requestHash,
            com.jingshanghui.pos.foundation.domain.CanonicalJson.from(Map.of("ui.layout", "compact")).sha256());
    }

    private static PlanRecord plan(String state, int recordVersion, int targetVersion, long creator,
                                   String requestHash, String snapshotHash) {
        return new PlanRecord(PLAN_ID, "TENANT_A", 10L, 20L, 30L, 40L, 1, targetVersion, 3,
            HASH, "CONVENIENCE", snapshotHash, state, "onb-create-001", requestHash, creator, 0,
            recordVersion, LocalDateTime.MIN, LocalDateTime.MIN);
    }
}
