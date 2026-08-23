package com.jingshanghui.pos.saas.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 可信租户授权、受限恢复能力和原子配额边界。 */
class SaasEntitlementServiceTest {
    private final TrustedTenantContext context=mock(TrustedTenantContext.class);
    private final SaasPersistencePort persistence=mock(SaasPersistencePort.class);
    private final SaasEntitlementService service=new SaasEntitlementService(context,persistence,Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

    @Test void activeTenantGetsFeatureAndAtomicQuota(){when(context.requireTenantId()).thenReturn("TENANT_A");when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(new TenantEntitlementRecord("TENANT_A",1L,"01K00000000000000000000000","ACTIVE",1,null));when(persistence.listItems(any())).thenReturn(List.of(new EntitlementItemRecord("i","01K00000000000000000000000","STORE_COUNT",true,5L,"a".repeat(64))));when(persistence.quotaUsed("TENANT_A","STORE_COUNT")).thenReturn(1L,2L);when(persistence.consumeQuota(any())).thenReturn(1);
        assertThat(service.decide("store_count").allowed()).isTrue();assertThat(service.consume("STORE_COUNT",1).quotaUsed()).isEqualTo(2L);}
    @Test void suspendedTenantKeepsRefundButRejectsNormalFeature(){when(context.requireTenantId()).thenReturn("TENANT_A");when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(new TenantEntitlementRecord("TENANT_A",1L,"v","SUSPENDED",1,null));when(persistence.listItems("v")).thenReturn(List.of(new EntitlementItemRecord("i","v","SALE",true,null,"a".repeat(64))));assertThat(service.decide("SALE").allowed()).isFalse();assertThat(service.decide("REFUND").allowed()).isTrue();}
    @Test void missingBindingAndQuotaConflictFailClosed(){when(context.requireTenantId()).thenReturn("TENANT_A");assertThat(service.decide("SALE").reasonCode()).isEqualTo("TENANT_NOT_ONBOARDED");when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(new TenantEntitlementRecord("TENANT_A",1L,"v","ACTIVE",1,null));when(persistence.listItems("v")).thenReturn(List.of(new EntitlementItemRecord("i","v","STORE_COUNT",true,1L,"a".repeat(64))));when(persistence.consumeQuota(any())).thenReturn(0);assertThatThrownBy(()->service.consume("STORE_COUNT",1)).hasMessageContaining("SAA-QUOTA-003");}
    @Test void subscriptionRecoveryModeOverridesNormalPackageFeature(){when(context.requireTenantId()).thenReturn("TENANT_A");when(persistence.findTenantEntitlement("TENANT_A")).thenReturn(new TenantEntitlementRecord("TENANT_A",1L,"v","ACTIVE",1,null));when(persistence.listItems("v")).thenReturn(List.of(new EntitlementItemRecord("i","v","SALE",true,null,"a".repeat(64))));when(persistence.findSubscriptionAccess("TENANT_A")).thenReturn(new SubscriptionAccessRecord("TENANT_A","sub","RECOVERY_ONLY",3,"a".repeat(64),1,null));assertThat(service.decide("SALE").allowed()).isFalse();assertThat(service.decide("SALE").reasonCode()).isEqualTo("SUBSCRIPTION_ACCESS_DENIED");}
}
