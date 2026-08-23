package com.jingshanghui.pos.subscription.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort.ApplyAccessCommand;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort.TenantPlanSnapshot;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.CreateSubscription;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.NewTermCommand;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.SubscriptionCommand;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.SubscriptionRecord;
import com.jingshanghui.pos.subscription.application.port.SubscriptionPersistencePort;
import com.jingshanghui.pos.subscription.domain.SubscriptionIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 可信租户自查必须失败关闭，不能依赖请求 tenant_id。 */
class SubscriptionApplicationServiceTest {
    private static final String SUBSCRIPTION_ID = "01K00000000000000000000000";
    private final TrustedTenantContext tenantContext=mock(TrustedTenantContext.class);
    private final SubscriptionPersistencePort persistence=mock(SubscriptionPersistencePort.class);
    private final SaasSubscriptionControlPort saas=mock(SaasSubscriptionControlPort.class);
    private final SubscriptionIdGenerator ids=mock(SubscriptionIdGenerator.class);
    private final SubscriptionApplicationService service=new SubscriptionApplicationService(tenantContext,
        mock(ScopeAuthorizationService.class),saas,persistence,
        ids,Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC));

    @Test void readsOnlyTrustedTenantSubscription() {
        when(tenantContext.requireTenantId()).thenReturn("TENANT_A");
        SubscriptionRecord record=record("TENANT_A");when(persistence.findByTenant("TENANT_A")).thenReturn(record);
        when(persistence.find(record.subscriptionId())).thenReturn(record);when(persistence.listTerms(record.subscriptionId())).thenReturn(List.of());
        assertThat(service.current().subscription().tenantId()).isEqualTo("TENANT_A");
        verify(persistence).findByTenant("TENANT_A");
    }

    @Test void rejectsRepositoryTenantMismatch() {
        when(tenantContext.requireTenantId()).thenReturn("TENANT_A");when(persistence.findByTenant("TENANT_A")).thenReturn(record("TENANT_B"));
        assertThatThrownBy(service::current).hasMessageContaining("SUB-TENANT-001");
    }

    @Test void activationUsesNamedTransitionsAndSaasOwnerPort() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal("000000", 7L, 1L, "platform"));
        SubscriptionRecord draft=record("TENANT_A", "DRAFT", 0);
        SubscriptionRecord pending=record("TENANT_A", "PENDING_ACTIVATION", 1);
        SubscriptionRecord active=record("TENANT_A", "ACTIVE", 2);
        when(persistence.lock(SUBSCRIPTION_ID)).thenReturn(draft, pending, active);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find(SUBSCRIPTION_ID)).thenReturn(active);
        when(persistence.listTerms(SUBSCRIPTION_ID)).thenReturn(List.of());

        var detail=service.activate(new SubscriptionCommand(SUBSCRIPTION_ID, "审批已通过",
            "activate-key-001", "corr-activate-001"));

        assertThat(detail.subscription().state()).isEqualTo("ACTIVE");
        var access=ArgumentCaptor.forClass(ApplyAccessCommand.class);
        verify(saas).applySubscriptionAccess(access.capture());
        assertThat(access.getValue().accessMode()).isEqualTo("NORMAL");
        assertThat(access.getValue().tenantId()).isEqualTo("TENANT_A");
        verify(persistence, times(2)).appendState(any());
        verify(persistence).insertCommand(any());
    }

    @Test void expiryScanConvergesActiveSubscriptionIntoGraceMode() {
        SubscriptionRecord active=expiredRecord("TENANT_A", "ACTIVE", 2);
        SubscriptionRecord grace=expiredRecord("TENANT_A", "GRACE_PERIOD", 3);
        when(persistence.acquireCheckpoint(any())).thenReturn(1);
        when(persistence.completeCheckpoint(any())).thenReturn(1);
        when(persistence.findDue(any(), eq(500))).thenReturn(List.of(active));
        when(persistence.lock(SUBSCRIPTION_ID)).thenReturn(active, grace);
        when(persistence.changeState(any())).thenReturn(1);

        var result=service.runExpiryScanAsSystem("runner-synthetic-001");

        assertThat(result.inspected()).isOne();
        assertThat(result.transitioned()).isOne();
        var access=ArgumentCaptor.forClass(ApplyAccessCommand.class);
        verify(saas).applySubscriptionAccess(access.capture());
        assertThat(access.getValue().accessMode()).isEqualTo("GRACE");
        verify(persistence).completeCheckpoint(any());
    }

    @Test void createWritesThreeReminderIntentsAndVersionedOutboxEvents() {
        platformPrincipal();
        when(saas.requireTenantPlan("TENANT_A")).thenReturn(new TenantPlanSnapshot(
            "TENANT_A",1L,"01K00000000000000000000001","ACTIVE"));
        when(persistence.find(SUBSCRIPTION_ID)).thenReturn(record("TENANT_A","DRAFT",0));
        when(persistence.listTerms(SUBSCRIPTION_ID)).thenReturn(List.of());
        when(ids.next()).thenReturn(SUBSCRIPTION_ID);
        LocalDateTime starts=LocalDateTime.of(2026,8,24,0,0);

        var detail=service.create(new CreateSubscription("TENANT_A","CONTRACT-1","ORDER-1",
            starts,starts.plusMonths(1),starts.plusMonths(1).plusDays(7)," Asia/Shanghai ",
            "RECOVERY-V1","create-key-001","corr-create-001"));

        assertThat(detail.subscription().state()).isEqualTo("DRAFT");
        verify(persistence,times(3)).appendNotification(any());
        verify(persistence,times(4)).appendOutbox(any());
        var term=ArgumentCaptor.forClass(SubscriptionPersistencePort.TermWrite.class);
        verify(persistence).appendTerm(term.capture());
        assertThat(term.getValue().businessTimeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test void rejectsCreatingSubscriptionForInactiveSaasTenant() {
        platformPrincipal();
        when(saas.requireTenantPlan("TENANT_A")).thenReturn(new TenantPlanSnapshot(
            "TENANT_A",1L,"01K00000000000000000000001","SUSPENDED"));
        LocalDateTime starts=LocalDateTime.of(2026,8,24,0,0);

        assertThatThrownBy(() -> service.create(new CreateSubscription("TENANT_A","CONTRACT-1","ORDER-1",
            starts,starts.plusMonths(1),starts.plusMonths(1).plusDays(7),"Asia/Shanghai",
            "RECOVERY-V1","create-key-002","corr-create-002")))
            .hasMessageContaining("SUB-SAA-006");
        verify(persistence,never()).insertSubscription(any());
    }

    @Test void renewalAppendsTermAndKeepsNormalAccess() {
        platformPrincipal();
        SubscriptionRecord active=record("TENANT_A","ACTIVE",2,1);
        SubscriptionRecord renewed=record("TENANT_A","ACTIVE",2,2);
        SubscriptionRecord advanced=record("TENANT_A","ACTIVE",3,2);
        when(persistence.lock(SUBSCRIPTION_ID)).thenReturn(active,renewed,advanced);
        when(persistence.changeCurrentTerm(any())).thenReturn(1);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find(SUBSCRIPTION_ID)).thenReturn(advanced);
        when(persistence.listTerms(SUBSCRIPTION_ID)).thenReturn(List.of());
        LocalDateTime starts=active.endsAt();

        var detail=service.renew(new NewTermCommand(SUBSCRIPTION_ID,"CONTRACT-2","ORDER-2",
            starts,starts.plusMonths(1),starts.plusMonths(1).plusDays(7),"Asia/Shanghai",
            "renew-key-001","corr-renew-001"));

        assertThat(detail.subscription().currentTermVersion()).isEqualTo(2);
        verify(persistence).appendTerm(any());
        verify(persistence,times(3)).appendNotification(any());
        verify(saas).applySubscriptionAccess(argThat(value -> "NORMAL".equals(value.accessMode())));
    }

    @Test void restoreFromExpiredRequiresNewEffectiveTermAndReturnsNormalAccess() {
        platformPrincipal();
        SubscriptionRecord expired=expiredRecord("TENANT_A","EXPIRED",4);
        SubscriptionRecord newTerm=record("TENANT_A","EXPIRED",4,2);
        SubscriptionRecord restored=record("TENANT_A","RESTORED",5,2);
        SubscriptionRecord active=record("TENANT_A","ACTIVE",6,2);
        when(persistence.lock(SUBSCRIPTION_ID)).thenReturn(expired,newTerm,restored,active);
        when(persistence.changeCurrentTerm(any())).thenReturn(1);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find(SUBSCRIPTION_ID)).thenReturn(active);
        when(persistence.listTerms(SUBSCRIPTION_ID)).thenReturn(List.of());
        LocalDateTime starts=LocalDateTime.of(2026,8,22,0,0);

        var detail=service.restore(new NewTermCommand(SUBSCRIPTION_ID,"CONTRACT-3","ORDER-3",
            starts,starts.plusMonths(1),starts.plusMonths(1).plusDays(7),"Asia/Shanghai",
            "restore-key-001","corr-restore-001"));

        assertThat(detail.subscription().state()).isEqualTo("ACTIVE");
        verify(persistence,times(2)).appendState(any());
        verify(saas).applySubscriptionAccess(argThat(value -> "NORMAL".equals(value.accessMode())));
    }

    @Test void suspendSwitchesToRecoveryOnlyWithoutDeletingHistory() {
        platformPrincipal();
        SubscriptionRecord active=record("TENANT_A","ACTIVE",2);
        SubscriptionRecord suspended=record("TENANT_A","SUSPENDED",3);
        when(persistence.lock(SUBSCRIPTION_ID)).thenReturn(active,suspended);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find(SUBSCRIPTION_ID)).thenReturn(suspended);
        when(persistence.listTerms(SUBSCRIPTION_ID)).thenReturn(List.of());

        var detail=service.suspend(new SubscriptionCommand(SUBSCRIPTION_ID,"人工复核",
            "suspend-key-001","corr-suspend-001"));

        assertThat(detail.accessMode()).isEqualTo("RECOVERY_ONLY");
        assertThat(detail.retainedCapabilities()).contains("REFUND","LEGAL_EXPORT","DATA_MIGRATION");
        verify(saas).applySubscriptionAccess(argThat(value -> "RECOVERY_ONLY".equals(value.accessMode())));
    }

    @Test void rejectsIdempotencyKeyReusedWithDifferentContent() {
        platformPrincipal();
        when(persistence.findCommand(eq("PLATFORM"),eq("SUSPEND_SUBSCRIPTION"),eq("suspend-key-002")))
            .thenReturn(new SubscriptionPersistencePort.CommandRecord("cmd","PLATFORM","SUSPEND_SUBSCRIPTION",
                "suspend-key-002","b".repeat(64),SUBSCRIPTION_ID,"SUSPENDED"));

        assertThatThrownBy(() -> service.suspend(new SubscriptionCommand(SUBSCRIPTION_ID,"新的原因",
            "suspend-key-002","corr-suspend-002"))).hasMessageContaining("SUB-IDEMP-002");
        verify(persistence,never()).changeState(any());
    }

    @Test void failsClosedWhenExpiryLeaseIsOwnedByAnotherRunner() {
        when(persistence.acquireCheckpoint(any())).thenReturn(0);
        assertThatThrownBy(() -> service.runExpiryScanAsSystem("runner-synthetic-002"))
            .hasMessageContaining("SUB-JOB-001");
        verify(persistence,never()).findDue(any(),anyInt());
    }

    private void platformPrincipal() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal("000000",7L,1L,"platform"));
    }

    private SubscriptionRecord record(String tenant){return record(tenant,"ACTIVE",2);}

    private SubscriptionRecord record(String tenant,String state,int stateVersion){return record(tenant,state,stateVersion,1);}

    private SubscriptionRecord record(String tenant,String state,int stateVersion,int termVersion){LocalDateTime now=LocalDateTime.of(2026,8,23,0,0);return new SubscriptionRecord(
        SUBSCRIPTION_ID,tenant,1L,"01K00000000000000000000001","CONTRACT-1","ORDER-1",
        state,stateVersion,termVersion,now.minusDays(1),now.plusDays(30),now.plusDays(37),"Asia/Shanghai","RECOVERY-V1",
        "a".repeat(64),now.minusDays(1),now);}

    private SubscriptionRecord expiredRecord(String tenant,String state,int stateVersion){LocalDateTime now=LocalDateTime.of(2026,8,23,0,0);return new SubscriptionRecord(
        SUBSCRIPTION_ID,tenant,1L,"01K00000000000000000000001","CONTRACT-1","ORDER-1",
        state,stateVersion,1,now.minusDays(2),now.minusDays(1),now.plusDays(7),"Asia/Shanghai","RECOVERY-V1",
        "a".repeat(64),now.minusDays(2),now);}
}
