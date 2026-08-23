package com.jingshanghui.pos.saas.application.service;

import com.jingshanghui.pos.saas.application.model.SaasModels.CommandRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.SubscriptionAccessRecord;
import com.jingshanghui.pos.saas.application.model.SaasModels.TenantEntitlementRecord;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort.ApplyAccessCommand;
import com.jingshanghui.pos.saas.domain.SaasIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Subscription 只能通过 SaaS Owner 端口切换访问投影。 */
class SaasSubscriptionControlServiceTest {
    private final SaasPersistencePort persistence=mock(SaasPersistencePort.class);
    private final SaasIdGenerator ids=mock(SaasIdGenerator.class);
    private final SaasSubscriptionControlService service=new SaasSubscriptionControlService(persistence,ids);
    private final LocalDateTime now=LocalDateTime.of(2026,8,23,0,0);

    @Test void createsProjectionEventAndIdempotentResultTogether() {
        activeTenant();
        when(ids.next()).thenReturn("01K00000000000000000000001","01K00000000000000000000002");

        service.applySubscriptionAccess(command("01K00000000000000000000010","NORMAL",1,"a".repeat(64)));

        verify(persistence).insertSubscriptionAccess(any());
        verify(persistence).appendSubscriptionAccessEvent(any());
        verify(persistence).insertCommand(any());
    }

    @Test void rejectsSameIdempotencyKeyWithDifferentContent() {
        activeTenant();
        when(persistence.findCommand("TENANT_A","SUBSCRIPTION_ACCESS","sub-access-key-001"))
            .thenReturn(new CommandRecord("cmd","TENANT_A","SUBSCRIPTION_ACCESS","sub-access-key-001",
                "b".repeat(64),"sub","NORMAL"));

        assertThatThrownBy(() -> service.applySubscriptionAccess(
            command("01K00000000000000000000010","NORMAL",1,"a".repeat(64))))
            .hasMessageContaining("SUB-SAA-003");
        verify(persistence,never()).insertSubscriptionAccess(any());
    }

    @Test void rejectsReplacingTenantWithAnotherSubscription() {
        activeTenant();
        when(persistence.findSubscriptionAccess("TENANT_A")).thenReturn(new SubscriptionAccessRecord(
            "TENANT_A","01K00000000000000000000099","NORMAL",3,"b".repeat(64),2,now));

        assertThatThrownBy(() -> service.applySubscriptionAccess(
            command("01K00000000000000000000010","GRACE",4,"a".repeat(64))))
            .hasMessageContaining("SUB-SAA-004");
    }

    @Test void rejectsSameSourceVersionWithDifferentObservation() {
        activeTenant();
        when(persistence.findSubscriptionAccess("TENANT_A")).thenReturn(new SubscriptionAccessRecord(
            "TENANT_A","01K00000000000000000000010","NORMAL",4,"b".repeat(64),2,now));

        assertThatThrownBy(() -> service.applySubscriptionAccess(
            command("01K00000000000000000000010","GRACE",4,"a".repeat(64))))
            .hasMessageContaining("SUB-SAA-008");
        verify(persistence,never()).changeSubscriptionAccess(any());
    }

    @Test void rejectsTenantRecordThatDoesNotMatchTrustedTarget() {
        when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(
            new TenantEntitlementRecord("TENANT_B",1L,"01K00000000000000000000020","ACTIVE",2,now));

        assertThatThrownBy(() -> service.requireTenantPlan("TENANT_A"))
            .hasMessageContaining("SUB-SAA-007");
    }

    private void activeTenant() {
        when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(
            new TenantEntitlementRecord("TENANT_A",1L,"01K00000000000000000000020","ACTIVE",2,now));
    }

    private ApplyAccessCommand command(String subscriptionId,String mode,int version,String hash) {
        return new ApplyAccessCommand("TENANT_A",subscriptionId,mode,version,hash,
            "sub-access-key-001","corr-sub-access-001",now);
    }
}
