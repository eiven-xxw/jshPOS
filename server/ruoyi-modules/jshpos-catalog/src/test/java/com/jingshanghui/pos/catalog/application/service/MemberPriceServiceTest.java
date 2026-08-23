package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.*;
import com.jingshanghui.pos.catalog.application.port.MemberPricePersistencePort;
import com.jingshanghui.pos.catalog.infrastructure.id.MemberPriceIdGenerator;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 Pricing Owner 的版本幂等、职责分离、冲突拒绝与权益前置校验。 */
class MemberPriceServiceTest {
    private static final String TENANT="100001",VERSION="01K5E000000000000000000001";
    private static final String COMMAND="01K5E000000000000000000002",CORRELATION="01K5E000000000000000000003";
    private static final String ITEM="01K5E000000000000000000004",SNAPSHOT="01K5E000000000000000000005";
    private static final LocalDateTime NOW=LocalDateTime.of(2026,8,23,5,0);
    private final TrustedTenantContext tenants=mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization=mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit=mock(DomainAuditService.class);
    private final CatalogMapper catalog=mock(CatalogMapper.class);
    private final MemberEntitlementQueryPort entitlements=mock(MemberEntitlementQueryPort.class);
    private final MemberPricePersistencePort persistence=mock(MemberPricePersistencePort.class);
    private final MemberPriceIdGenerator ids=mock(MemberPriceIdGenerator.class);
    private MemberPriceService service;
    @BeforeEach void setup(){
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,8L,"synthetic-admin"));
        when(tenants.requireTenantId()).thenReturn(TENANT);
        when(ids.next()).thenReturn("01K5E000000000000000000101","01K5E000000000000000000102",
            "01K5E000000000000000000103","01K5E000000000000000000104");
        when(catalog.findProduct(TENANT,11L)).thenReturn(new ProductView(11L,21L,"SPU","SKU","合成商品",1L,2L,"STANDARD","ACTIVE",1));
        when(catalog.findUnit(TENANT,12L)).thenReturn(new DefinitionView(12L,"EA","件","ACTIVE"));
        service=new MemberPriceService(tenants,authorization,audit,catalog,entitlements,persistence,ids,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
    }
    @Test void createsImmutableMinorUnitDraft(){
        when(persistence.findVersion(TENANT,VERSION)).thenReturn(view("DRAFT",7L,null,0));
        VersionView result=service.create(new CreateVersion(COMMAND,VERSION,"MEMBER_V1",1,1001L,
            List.of(new ItemDraft(ITEM,"GOLD",11L,12L,880L)),CORRELATION));
        assertThat(result.state()).isEqualTo("DRAFT");verify(authorization).requireStoreAccess(1001L);
        verify(persistence).insertItems(argThat(v->v.size()==1&&v.get(0).amountMinor()==880L));
        verify(persistence).insertOutbox(argThat(v->!v.payloadJson().contains(SNAPSHOT)));
    }
    @Test void approvalRequiresIndependentActor(){
        when(persistence.lockVersion(TENANT,VERSION)).thenReturn(view("VALIDATED",7L,null,1));
        VersionAction action=action();
        assertThatThrownBy(()->service.approve(action)).hasMessageContaining("PRC-MEMBER-009");
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,9L,8L,"synthetic-approver"));
        when(persistence.transition(any())).thenReturn(1);when(persistence.findVersion(TENANT,VERSION)).thenReturn(view("APPROVED",7L,9L,2));
        assertThat(service.approve(action).state()).isEqualTo("APPROVED");
    }
    @Test void publishingConflictFailsClosed(){
        when(persistence.lockVersion(TENANT,VERSION)).thenReturn(view("APPROVED",7L,9L,2));
        when(persistence.countPublishingConflicts(eq(TENANT),eq(VERSION),eq(1001L),any(),any())).thenReturn(1);
        assertThatThrownBy(()->service.publish(action())).hasMessageContaining("PRC-MEMBER-006");
        verify(persistence,never()).transition(any());
    }
    @Test void resolvesOnlyAfterMemberEntitlementValidation(){
        Instant at=NOW.toInstant(ZoneOffset.UTC);
        when(entitlements.resolve(SNAPSHOT,1001L,at)).thenReturn(new MemberEntitlementQueryPort.EntitlementQuote(
            SNAPSHOT,"b".repeat(64),"GOLD","01K5E000000000000000000006",1001L,true,false,
            at.minusSeconds(1),at.plusSeconds(3600),0,"c".repeat(64),"d".repeat(64)));
        MemberPriceCandidate candidate=new MemberPriceCandidate(VERSION,ITEM,SNAPSHOT,"GOLD",11L,12L,1001L,
            880L,"CNY","e".repeat(64),at.minusSeconds(1),at.plusSeconds(3600));
        when(persistence.findCandidate(any())).thenReturn(candidate);
        assertThat(service.resolve(SNAPSHOT,11L,12L,1001L,at).amountMinor()).isEqualTo(880L);
        verify(persistence).findCandidate(argThat(v->v.tenantId().equals(TENANT)&&v.levelCode().equals("GOLD")));
    }
    private VersionAction action(){return new VersionAction(COMMAND,VERSION,"a".repeat(64),
        NOW.plusHours(1).toInstant(ZoneOffset.UTC),NOW.plusDays(1).toInstant(ZoneOffset.UTC),CORRELATION);}
    private VersionView view(String state,Long created,Long approved,int version){return new VersionView(VERSION,
        "MEMBER_V1",1,1001L,state,NOW.plusHours(1),NOW.plusDays(1),"a".repeat(64),created,approved,version);}
}
