package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.member.application.model.BenefitCommands.*;
import com.jingshanghui.pos.member.application.model.BenefitViews.*;
import com.jingshanghui.pos.member.application.model.MemberViews.MemberView;
import com.jingshanghui.pos.member.application.model.PointsViews.LevelView;
import com.jingshanghui.pos.member.application.port.*;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证 Member Owner 的可信范围、职责分离、幂等和无 PII 快照。 */
class MemberBenefitServiceTest {
    private static final String TENANT="100001";
    private static final String POLICY="01K5D000000000000000000001";
    private static final String VERSION="01K5D000000000000000000002";
    private static final String COMMAND="01K5D000000000000000000003";
    private static final String CORRELATION="01K5D000000000000000000004";
    private static final String MEMBER="01K5D000000000000000000005";
    private static final String LEVEL_HISTORY="01K5D000000000000000000006";
    private static final String SNAPSHOT="01K5D000000000000000000007";
    private static final LocalDateTime NOW=LocalDateTime.of(2026,8,23,4,0);
    private final TrustedTenantContext tenants=mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization=mock(ScopeAuthorizationService.class);
    private final MemberPersistencePort members=mock(MemberPersistencePort.class);
    private final PointsPersistencePort points=mock(PointsPersistencePort.class);
    private final BenefitPersistencePort persistence=mock(BenefitPersistencePort.class);
    private final MemberIdGenerator ids=mock(MemberIdGenerator.class);
    private MemberBenefitService service;

    @BeforeEach void setup() {
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,8L,"synthetic-admin"));
        when(tenants.requireTenantId()).thenReturn(TENANT);
        when(ids.next()).thenReturn("01K5D000000000000000000101","01K5D000000000000000000102",
            "01K5D000000000000000000103","01K5D000000000000000000104","01K5D000000000000000000105",
            "01K5D000000000000000000106","01K5D000000000000000000107","01K5D000000000000000000108",
            "01K5D000000000000000000109","01K5D000000000000000000110");
        service=new MemberBenefitService(tenants,authorization,members,points,persistence,ids,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
    }

    @Test void createsDefaultBestPriceDraftWithTrustedStoreScope() {
        PolicyVersionView stored=version("DRAFT",7L,null,false,false,0,0);
        when(persistence.findVersion(TENANT,POLICY,VERSION)).thenReturn(stored);
        PolicyVersionView result=service.createDraft(new CreateDraft(COMMAND,POLICY,VERSION,"V1_MEMBER",
            "V1会员权益",List.of(new LevelRule("GOLD",true,false)),List.of(1001L),CORRELATION));
        assertThat(result.defaultCombinationPolicy()).isEqualTo("BEST_PRICE");
        verify(authorization).requireTenantAdministrator();
        verify(authorization).requireStoreAccess(1001L);
        verify(persistence).insertMappings(argThat(values -> values.size()==1
            && values.get(0).memberPriceEligible() && !values.get(0).stackingAllowed()));
        verify(persistence).insertOutbox(argThat(value -> !value.payloadJson().contains(MEMBER)));
    }

    @Test void approvalEnforcesCreatorApproverSeparationAndDigest() {
        PolicyVersionView validated=version("VALIDATED",7L,null,false,false,0,1);
        when(persistence.lockVersion(TENANT,POLICY,VERSION)).thenReturn(validated);
        VersionAction action=new VersionAction(COMMAND,POLICY,VERSION,"a".repeat(64),null,null,
            "APPROVED","synthetic",CORRELATION);
        assertThatThrownBy(() -> service.approve(action)).hasMessageContaining("MEM-BENEFIT-013");
        verify(persistence,never()).transitionVersion(any());

        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,9L,8L,"synthetic-approver"));
        when(persistence.transitionVersion(any())).thenReturn(1);
        when(persistence.findVersion(TENANT,POLICY,VERSION)).thenReturn(version("APPROVED",7L,9L,false,false,0,2));
        assertThat(service.approve(action).state()).isEqualTo("APPROVED");
        verify(persistence).transitionVersion(argThat(value -> value.approvedBy().equals(9L)));
    }

    @Test void issuesMinimalSnapshotAndResolvesOnlyInsideStoreAndTtl() {
        when(members.findMember(TENANT,MEMBER)).thenReturn(new MemberView(MEMBER,"ACTIVE","会员-0005",0,NOW));
        when(points.findCurrentLevel(TENANT,MEMBER)).thenReturn(new LevelView(LEVEL_HISTORY,MEMBER,"GOLD","L1",
            "APPROVED",1001L,LocalDate.of(2026,8,23),7L,9L,"01K5D000000000000000000008",NOW.minusDays(1)));
        when(persistence.findActiveVersion(TENANT,1001L,"GOLD",NOW)).thenReturn(
            version("ACTIVE",7L,9L,true,true,3,3));
        EntitlementSnapshotView stored=new EntitlementSnapshotView(SNAPSHOT,"b".repeat(64),LEVEL_HISTORY,"GOLD",
            VERSION,1001L,true,true,NOW,NOW.plusHours(24),3,"c".repeat(64),"d".repeat(64));
        when(persistence.findSnapshot(TENANT,SNAPSHOT)).thenReturn(stored);
        EntitlementSnapshotView result=service.issue(new IssueEntitlement(COMMAND,SNAPSHOT,MEMBER,1001L,
            NOW.toInstant(ZoneOffset.UTC),CORRELATION));
        assertThat(result.memberRefHash()).hasSize(64);
        verify(persistence).insertSnapshot(argThat(value -> !value.memberRefHash().equals(MEMBER)
            && value.expiresAt().equals(NOW.plusHours(24))));
        assertThat(service.resolve(SNAPSHOT,1001L,NOW.plusHours(1).toInstant(ZoneOffset.UTC)).levelCode())
            .isEqualTo("GOLD");
        assertThatThrownBy(() -> service.resolve(SNAPSHOT,1002L,NOW.plusHours(1).toInstant(ZoneOffset.UTC)))
            .hasMessageContaining("MEM-BENEFIT-011");
        assertThatThrownBy(() -> service.resolve(SNAPSHOT,1001L,NOW.plusHours(24).toInstant(ZoneOffset.UTC)))
            .hasMessageContaining("MEM-BENEFIT-011");
    }

    @Test void sameIdempotencyKeyWithDifferentContentFailsClosed() {
        when(persistence.findCommand(TENANT,"CREATE_BENEFIT_DRAFT",COMMAND)).thenReturn(
            new BenefitPersistencePort.StoredCommand("f".repeat(64),VERSION,"e".repeat(64),"{}"));
        assertThatThrownBy(() -> service.createDraft(new CreateDraft(COMMAND,POLICY,VERSION,"V1_MEMBER",
            "V1会员权益",List.of(new LevelRule("GOLD",true,false)),List.of(1001L),CORRELATION)))
            .hasMessageContaining("MEM-BENEFIT-015");
        verify(persistence,never()).insertPolicy(any());
    }

    private PolicyVersionView version(String state,Long created,Long approved,boolean stacking,
                                      boolean eligible,long epoch,int version) {
        return new PolicyVersionView(POLICY,VERSION,"V1_MEMBER","V1会员权益",1,state,"BEST_PRICE",
            stacking,eligible,NOW.minusHours(1),NOW.plusDays(3),epoch,"a".repeat(64),created,approved,version);
    }
}
