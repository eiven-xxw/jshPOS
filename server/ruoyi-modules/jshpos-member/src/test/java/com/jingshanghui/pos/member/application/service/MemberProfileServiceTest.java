package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.member.application.model.MemberCommands.*;
import com.jingshanghui.pos.member.application.model.MemberViews.*;
import com.jingshanghui.pos.member.application.port.MemberIdentityProtector;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证会员 Owner 的去敏幂等、状态迁移、合并路由和只追加事实。 */
class MemberProfileServiceTest {
    private static final String TENANT="TENANT_A";
    private static final String MEMBER="01K5C000000000000000000001";
    private static final String TARGET="01K5C000000000000000000002";
    private static final String IDENTITY="01K5C000000000000000000003";
    private static final String COMMAND="01K5C000000000000000000004";
    private static final String CORRELATION="01K5C000000000000000000005";
    private static final String REQUEST="01K5C000000000000000000006";
    private static final String LINK="01K5C000000000000000000007";
    private static final LocalDateTime NOW=LocalDateTime.of(2026,8,17,10,0);
    private final TrustedTenantContext tenants=mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization=mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit=mock(DomainAuditService.class);
    private final MemberPersistencePort persistence=mock(MemberPersistencePort.class);
    private final MemberIdentityProtector identities=mock(MemberIdentityProtector.class);
    private final MemberIdGenerator ids=mock(MemberIdGenerator.class);
    private MemberProfileService service;

    @BeforeEach void setUp() {
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,8L,"synthetic-user"));
        when(tenants.requireTenantId()).thenReturn(TENANT);
        when(ids.next()).thenReturn("01K5C000000000000000000101","01K5C000000000000000000102",
            "01K5C000000000000000000103","01K5C000000000000000000104",
            "01K5C000000000000000000105","01K5C000000000000000000106",
            "01K5C000000000000000000107","01K5C000000000000000000108");
        when(identities.protect(anyString(),anyString())).thenReturn(
            new MemberIdentityProtector.ProtectedIdentity("a".repeat(64),"synthetic-cipher","+86****0000",3));
        when(identities.lookupHmac(anyString(),anyString())).thenReturn("a".repeat(64));
        service=new MemberProfileService(tenants,authorization,audit,persistence,identities,ids,
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC),ZoneOffset.UTC));
    }

    @Test void createsMemberAndIdentityWithoutCleartextInFacts() {
        when(persistence.findMember(TENANT,MEMBER)).thenReturn(member(MEMBER,"ACTIVE",0));
        MemberView result=service.create(new CreateMember(COMMAND,MEMBER,IDENTITY,"MOBILE",
            "+8613800000000",CORRELATION));
        assertThat(result.memberId()).isEqualTo(MEMBER);
        verify(persistence).insertIdentity(argThat(value -> value.lookupHmac().equals("a".repeat(64))
            && value.cipherText().equals("synthetic-cipher") && !value.maskedValue().contains("13800000000")));
        verify(persistence).insertOutbox(argThat(value -> !value.payloadJson().contains("13800000000")
            && !value.payloadJson().contains("synthetic-cipher")));
        verify(audit).append(eq("MEMBER_CREATED"),eq("MEMBER"),eq(MEMBER),isNull(),any(),any());
    }

    @Test void commandReplayReturnsOriginalAndDifferentContentIsRejected() {
        when(persistence.findCommand(eq(TENANT),eq("CREATE_MEMBER"),eq(COMMAND)))
            .thenReturn(new MemberPersistencePort.StoredCommand("f".repeat(64),MEMBER,"e".repeat(64),"{}"));
        assertThatThrownBy(() -> service.create(new CreateMember(COMMAND,MEMBER,IDENTITY,"MOBILE",
            "+8613800000000",CORRELATION))).hasMessageContaining("MEM-IDEMP-001");
        verify(persistence,never()).insertMember(any());
    }

    @Test void resolvesMergedAliasToActiveTargetAndNeverReturnsCleartext() {
        IdentityView identity=new IdentityView(IDENTITY,MEMBER,"MOBILE","+86****0000","ACTIVE",3,NOW);
        when(persistence.findIdentityByLookup(TENANT,"MOBILE","a".repeat(64))).thenReturn(identity);
        when(persistence.findMember(TENANT,MEMBER)).thenReturn(member(MEMBER,"MERGED",1));
        when(persistence.findLatestMemberLinkForSource(TENANT,MEMBER)).thenReturn(
            new MemberLinkView(LINK,MEMBER,TARGET,"MERGE",NOW));
        when(persistence.findMember(TENANT,TARGET)).thenReturn(member(TARGET,"ACTIVE",0));
        ResolvedMemberView result=service.resolve("MOBILE","+8613800000000");
        assertThat(result.member().memberId()).isEqualTo(TARGET);
        assertThat(result.matchedIdentity().maskedValue()).isEqualTo("+86****0000");
    }

    @Test void appendsConsentAndPrivacyHistoryThenEnforcesStateGraph() {
        when(persistence.findMember(TENANT,MEMBER)).thenReturn(member(MEMBER,"ACTIVE",0));
        String consent="01K5C000000000000000000008";
        ConsentView consentView=new ConsentView(consent,MEMBER,"MARKETING","v1","GRANTED","b".repeat(64),NOW);
        when(persistence.findConsent(TENANT,consent)).thenReturn(consentView);
        assertThat(service.recordConsent(new ConsentCommand(COMMAND,MEMBER,consent,"MARKETING","v1",
            "GRANTED","b".repeat(64),CORRELATION))).isEqualTo(consentView);
        verify(persistence).insertConsent(any());

        PrivacyRequestView requested=new PrivacyRequestView(REQUEST,MEMBER,"EXPORT","REQUESTED",0,NOW,null);
        when(persistence.findPrivacyRequest(TENANT,REQUEST)).thenReturn(requested);
        assertThat(service.requestPrivacy(new PrivacyCommand("01K5C000000000000000000009",MEMBER,REQUEST,
            "EXPORT","synthetic request",CORRELATION)).state()).isEqualTo("REQUESTED");
        verify(persistence,atLeastOnce()).insertPrivacyHistory(any());

        when(persistence.lockPrivacyRequest(TENANT,REQUEST)).thenReturn(requested);
        assertThatThrownBy(() -> service.transitionPrivacy(new PrivacyTransition(
            "01K5C000000000000000000010",REQUEST,"FULFILLED",0,"skip verification",CORRELATION)))
            .hasMessageContaining("MEM-PRIVACY-002");
        verify(persistence,never()).changePrivacyState(anyString(),anyString(),anyString(),eq("FULFILLED"),anyInt(),any());
    }

    @Test void mergeAndSplitAreReversibleAppendOnlyFacts() {
        when(persistence.lockMember(TENANT,MEMBER)).thenReturn(member(MEMBER,"ACTIVE",0),member(MEMBER,"MERGED",1));
        when(persistence.lockMember(TENANT,TARGET)).thenReturn(member(TARGET,"ACTIVE",0));
        when(persistence.findMember(TENANT,TARGET)).thenReturn(member(TARGET,"ACTIVE",0));
        when(persistence.changeMemberState(anyString(),anyString(),anyString(),anyString(),anyInt())).thenReturn(1);
        when(persistence.findLatestMemberLink(TENANT,LINK)).thenReturn(
            new MemberLinkView(LINK,MEMBER,TARGET,"MERGE",NOW),
            new MemberLinkView(LINK,MEMBER,TARGET,"MERGE",NOW),
            new MemberLinkView(LINK,MEMBER,TARGET,"SPLIT",NOW));

        MemberLinkView merged=service.merge(new MergeCommand(COMMAND,MEMBER,TARGET,LINK,
            "synthetic merge",CORRELATION));
        assertThat(merged.action()).isEqualTo("MERGE");
        MemberLinkView split=service.split(new SplitCommand("01K5C000000000000000000011",MEMBER,TARGET,LINK,
            "synthetic split",CORRELATION));
        assertThat(split.action()).isEqualTo("SPLIT");
        verify(persistence,times(2)).insertMemberLink(any());
        verify(authorization,times(2)).requireTenantAdministrator();
    }

    private static MemberView member(String id,String state,int version) {
        return new MemberView(id,state,"会员-000001",version,NOW);
    }
}
