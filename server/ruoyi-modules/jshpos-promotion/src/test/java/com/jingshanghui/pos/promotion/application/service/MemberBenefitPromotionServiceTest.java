package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.catalog.application.port.MemberPriceResolutionPort;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort;
import com.jingshanghui.pos.member.application.port.MemberEntitlementQueryPort.EntitlementQuote;
import com.jingshanghui.pos.promotion.application.model.MemberBenefitPromotionModels.*;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.*;
import com.jingshanghui.pos.promotion.application.port.MemberBenefitCapabilityPort.Capability;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.CommandWrite;
import com.jingshanghui.pos.promotion.domain.*;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.Path;
import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证可信能力开关、无 PII 权益、会员价候选和稳定幂等事务编排。 */
class MemberBenefitPromotionServiceTest {
    private static final String TENANT="TENANT_A", REQUEST="01K5E000000000000000000001",
        TERMINAL="01K5E000000000000000000002", SNAP="01K5E000000000000000000003",
        LINE="01K5E000000000000000000004", VERSION="01K5E000000000000000000005",
        BENEFIT="01K5E000000000000000000006", CORRELATION="01K5E000000000000000000007";
    private static final Instant NOW=Instant.parse("2026-08-23T05:00:00Z");
    private final TrustedTenantContext tenants=mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization=mock(ScopeAuthorizationService.class);
    private final PromotionPersistencePort persistence=mock(PromotionPersistencePort.class);
    private final MemberEntitlementQueryPort entitlements=mock(MemberEntitlementQueryPort.class);
    private final MemberPriceResolutionPort prices=mock(MemberPriceResolutionPort.class);
    private final MemberBenefitCapabilityPort capabilities=mock(MemberBenefitCapabilityPort.class);
    private final ObjectMapper json=new ObjectMapper();
    private MemberBenefitPromotionService service;

    @BeforeEach void setup(){
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,8L,"synthetic"));
        when(persistence.findPackage(TENANT,1101L,3)).thenReturn(new PackageView(SNAP,1101L,3,2,"a".repeat(64),
            "key-v1","tenant/TENANT_A/package",LocalDateTime.ofInstant(NOW.minusSeconds(10),ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.plusSeconds(600),ZoneOffset.UTC)));
        service=new MemberBenefitPromotionService(tenants,authorization,persistence,
            new PromotionRuleDefinitionCodec(json),new PromotionEngine(),entitlements,prices,capabilities,
            new MemberBenefitCombinationEngine(),new PromotionIdGenerator(Clock.fixed(NOW,ZoneOffset.UTC)),json,
            Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test void enabledCapabilityUsesMemberPriceAndWritesImmutableBinding(){
        when(capabilities.resolve(1101L)).thenReturn(new Capability(true,false,9,"b".repeat(64)));
        when(entitlements.resolve(SNAP,1101L,NOW)).thenReturn(new EntitlementQuote(SNAP,"c".repeat(64),"GOLD",
            BENEFIT,1101L,true,false,NOW.minusSeconds(1),NOW.plusSeconds(60),3,"d".repeat(64),"e".repeat(64)));
        when(prices.resolve(SNAP,11L,12L,1101L,NOW)).thenReturn(new MemberPriceCandidate(VERSION,
            "01K5E000000000000000000008",SNAP,"GOLD",11L,12L,1101L,80,"CNY","f".repeat(64),NOW,null));
        MemberQuoteView result=service.quote(command(SNAP));
        assertThat(result.selectedPath()).isEqualTo(Path.MEMBER_PATH);
        assertThat(result.result().payableAmountMinor()).isEqualTo(80);
        verify(persistence).insertMemberBenefitBinding(argThat(v->v.selectedPath().equals("MEMBER_PATH")
            && v.rightsDigest().equals("d".repeat(64))));
        verify(persistence).insertCommand(argThat(v->v.commandType().equals("MEMBER_PROMOTION_QUOTE")));
        verify(persistence).insertAudit(any());verify(persistence).insertOutbox(any());
    }

    @Test void missingCapabilityDefaultsToNormalAndDoesNotResolveMemberOwners(){
        when(capabilities.resolve(1101L)).thenReturn(new Capability(false,false,0,"0".repeat(64)));
        MemberQuoteView result=service.quote(command(SNAP));
        assertThat(result.selectedPath()).isEqualTo(Path.NORMAL_PATH);
        assertThat(result.result().payableAmountMinor()).isEqualTo(100);
        verifyNoInteractions(entitlements,prices);
    }

    @Test void sameKeyDifferentContentFailsBeforeWritingFacts(){
        when(persistence.findCommand(eq(TENANT),eq("MEMBER_PROMOTION_QUOTE"),eq(REQUEST))).thenReturn(
            new PromotionPersistencePort.StoredCommand("a".repeat(64),SNAP,"b".repeat(64),"{}"));
        assertThatThrownBy(()->service.quote(command(SNAP))).hasMessageContaining("PRM-IDEMP-003");
        verify(persistence,never()).insertQuote(any());
    }

    private MemberQuote command(String snapshot){return new MemberQuote(REQUEST,1101L,TERMINAL,"POS",
        OffsetDateTime.ofInstant(NOW,ZoneOffset.UTC),"CNY",3,snapshot,List.of(new MemberQuoteLine(
        new BasketLine(LINE,1,11L,null,null,BigDecimal.ONE,100),12L)),CORRELATION);}
}
