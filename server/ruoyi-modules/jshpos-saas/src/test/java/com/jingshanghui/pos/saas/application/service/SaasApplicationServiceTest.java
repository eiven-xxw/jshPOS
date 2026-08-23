package com.jingshanghui.pos.saas.application.service;

import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.port.TenantProvisioningPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort.*;
import com.jingshanghui.pos.saas.domain.SaasIdGenerator;
import com.jingshanghui.pos.saas.infrastructure.persistence.entity.SaasPlanEntity;
import com.jingshanghui.pos.saas.infrastructure.persistence.mapper.SaasPlanMapper;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用测试夹具串联正式应用服务，验证开户、审批分离、Foundation 端口和激活检查点。 */
class SaasApplicationServiceTest {
    @Test void syntheticMerchantOnboardingConvergesWithoutClientTenantAuthority() {
        TrustedTenantContext context=mock(TrustedTenantContext.class);ScopeAuthorizationService auth=mock(ScopeAuthorizationService.class);
        TenantProvisioningPort tenantPort=mock(TenantProvisioningPort.class);SaasPersistencePort store=mock(SaasPersistencePort.class);SaasPlanMapper plans=mock(SaasPlanMapper.class);
        AtomicLong actor=new AtomicLong(10);when(context.requirePrincipal()).thenAnswer(i->new TrustedPrincipal("000000",actor.get(),1L,"platform"));
        SaasPlanEntity plan=new SaasPlanEntity();plan.setPlanId(1L);plan.setStatus("ACTIVE");plan.setPlatformPackageId(8L);plan.setAccountLimit(50L);when(plans.selectById(1L)).thenReturn(plan);
        var version=new EntitlementVersionRecord("01K80000000000000000000001",1L,1,"EFFECTIVE",LocalDateTime.of(2026,8,22,0,0),null,"a".repeat(64),10L,20L,4,LocalDateTime.now(),LocalDateTime.now());
        when(store.findEffectiveVersion(eq(1L),any())).thenReturn(version);when(store.listItems(version.versionId())).thenReturn(List.of(new EntitlementItemRecord("i",version.versionId(),"STORE_COUNT",true,10L,"b".repeat(64))));
        AtomicReference<ApplicationRecord> app=new AtomicReference<>();AtomicReference<TenantEntitlementRecord> tenant=new AtomicReference<>();List<String> checkpoints=new ArrayList<>();
        doAnswer(i->{ApplicationWrite w=i.getArgument(0);app.set(new ApplicationRecord(w.applicationId(),w.applicationCode(),null,null,w.companyName(),w.industry(),w.planId(),w.state(),w.submitterUserId(),null,0,w.contentSha256(),w.at(),w.at()));return null;}).when(store).insertApplication(any());
        when(store.findApplication(any())).thenAnswer(i->app.get());when(store.lockApplication(any())).thenAnswer(i->app.get());when(store.listCheckpoints(any())).thenAnswer(i->List.copyOf(checkpoints));
        when(store.changeApplication(any())).thenAnswer(i->{ApplicationChange c=i.getArgument(0);ApplicationRecord old=app.get();app.set(new ApplicationRecord(old.applicationId(),old.applicationCode(),c.tenantId()==null?old.tenantId():c.tenantId(),c.technicalTenantId()==null?old.technicalTenantId():c.technicalTenantId(),old.companyName(),old.industry(),old.planId(),c.toState(),old.submitterUserId(),c.approverUserId()==null?old.approverUserId():c.approverUserId(),old.recordVersion()+1,old.contentSha256(),old.createdAt(),c.at()));return 1;});
        doAnswer(i->{CheckpointWrite w=i.getArgument(0);checkpoints.add(w.stepCode());return null;}).when(store).insertCheckpoint(any());
        doAnswer(i->{TenantBindingWrite w=i.getArgument(0);tenant.set(new TenantEntitlementRecord(w.tenantId(),w.planId(),w.versionId(),w.lifecycleState(),0,w.at()));return null;}).when(store).bindTenant(any());
        when(store.findTenantEntitlement(any())).thenAnswer(i->tenant.get());when(store.changeLifecycle(any())).thenAnswer(i->{LifecycleChange c=i.getArgument(0);TenantEntitlementRecord old=tenant.get();tenant.set(new TenantEntitlementRecord(old.tenantId(),old.planId(),old.versionId(),c.toState(),old.lifecycleVersion()+1,c.at()));return 1;});
        when(tenantPort.provision(any())).thenReturn(new TenantProvisioningPort.ProvisionedTenant(91L,"200001"));
        Clock clock=Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC);SaasApplicationService service=new SaasApplicationService(context,auth,tenantPort,store,plans,new SaasIdGenerator(clock),clock);

        ApplicationDetail created=service.createApplication(new CreateApplication("APP_001","虚构商户","CONVENIENCE",1L,"create-001","trace-001"));
        String id=created.application().applicationId();assertThat(created.application().state()).isEqualTo("DRAFT");
        assertThat(service.preflight(new ApplicationCommand(id,null,"preflight-001","trace-002")).application().state()).isEqualTo("READY");
        actor.set(20);assertThat(service.approve(new ApplicationCommand(id,"独立复核通过","approve-001","trace-003")).application().state()).isEqualTo("APPROVED");
        char[] password="Synthetic-Pass-01".toCharArray();ApplicationDetail provisioned=service.provision(new ProvisionCommand(id,"虚构联系人","00000000000","synthetic",password,"provision-001","trace-004"));
        assertThat(provisioned.application().tenantId()).isEqualTo("200001");assertThat(password).containsOnly('\0');assertThat(checkpoints).contains("TECHNICAL_TENANT","ENTITLEMENT_BINDING");
        service.initialize(new ApplicationCommand(id,null,"initialize-001","trace-005"));
        ApplicationDetail activated=service.activate(new ApplicationCommand(id,null,"activate-001","trace-006"));
        assertThat(activated.application().state()).isEqualTo("ACTIVE");assertThat(activated.lifecycle().lifecycleState()).isEqualTo("ACTIVE");
        verify(tenantPort).changeStatus("200001", TenantProvisioningPort.TechnicalTenantStatus.ACTIVE);
    }
}
