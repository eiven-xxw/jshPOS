package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.catalog.application.port.MemberPricePackageSourcePort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import com.jingshanghui.pos.member.application.port.MemberBenefitPackageSourcePort;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.MemberBenefitPackageView;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.ObjectPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningResult;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证权益包只消费正式无 PII 只读端口，并与元数据、审计和 Outbox 同事务编排。 */
class MemberBenefitPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T02:00:00Z");
    private static final String CORRELATION = "01K30000000000000000000001";

    @Test
    void publishUsesOwnerPortsSignsTenantObjectAndAppendsEvidence() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        TenantResourceNamespace namespace = mock(TenantResourceNamespace.class);
        MemberBenefitPackageSourcePort benefits = mock(MemberBenefitPackageSourcePort.class);
        MemberPricePackageSourcePort prices = mock(MemberPricePackageSourcePort.class);
        PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
        SigningPort signer = mock(SigningPort.class);
        ObjectPort objects = mock(ObjectPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<SigningPort> signers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ObjectPort> objectProviders = mock(ObjectProvider.class);
        PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
        LocalDateTime from = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        LocalDateTime to = from.plusDays(7);

        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 7L, 8L, "synthetic"));
        when(tenants.requireTenantId()).thenReturn("TENANT_A");
        when(signers.getIfAvailable()).thenReturn(signer);
        when(objectProviders.getIfAvailable()).thenReturn(objects);
        when(namespace.objectKey(anyString())).thenReturn("tenant/TENANT_A/member-benefit-v1.jshpkg");
        when(signer.sign(eq("TENANT_A"), any())).thenReturn(new SigningResult("synthetic-key-v1", new byte[64]));
        when(ids.next()).thenReturn("01K30000000000000000000010", "01K30000000000000000000011",
            "01K30000000000000000000012");
        when(benefits.listForPackage("TENANT_A", 1101L, from, to)).thenReturn(List.of(
            new MemberBenefitPackageSourcePort.BenefitPackageRow("01K30000000000000000000021", "GOLD",
                true, false, "BEST_PRICE", false, 0, from, to, "a".repeat(64))));
        when(prices.listForPackage("TENANT_A", 1101L, from, to)).thenReturn(List.of(
            new MemberPricePackageSourcePort.MemberPricePackageRow("01K30000000000000000000022", 1,
                "GOLD", 101L, 1001L, 1101L, 580, from, to, "b".repeat(64))));
        when(persistence.findMemberBenefitPackage("TENANT_A", 1101L, 1)).thenReturn(
            new MemberBenefitPackageView("01K30000000000000000000010", 1101L, 1, 0,
                "c".repeat(64), "synthetic-key-v1", "tenant/TENANT_A/member-benefit-v1.jshpkg",
                1, 1, from, to));

        var service = new MemberBenefitPackageService(tenants, authorization, namespace, benefits, prices,
            persistence, signers, objectProviders, ids, Clock.fixed(NOW, ZoneOffset.UTC));
        MemberBenefitPackageView result = service.publish(1101L, 1, 0, NOW.plus(Duration.ofDays(7)), CORRELATION);

        assertThat(result.packageVersion()).isEqualTo(1);
        verify(authorization, times(2)).requireStoreAccess(1101L);
        verify(objects).put(eq("tenant/TENANT_A/member-benefit-v1.jshpkg"),
            argThat(payload -> new String(payload, java.nio.charset.StandardCharsets.UTF_8)
                .contains("JSHMBP|1.0|member-benefit-engine-1.0.0|TENANT_A|1101|1|0")),
            argThat(signature -> signature.length == 64));
        verify(persistence).insertMemberBenefitPackage(any());
        verify(persistence).insertAudit(argThat(audit -> audit.actionCode().equals("MEMBER_BENEFIT_PACKAGE_PUBLISHED")));
        verify(persistence).insertOutbox(argThat(event -> event.eventType().equals("promotion.member-benefit-package.published.v1")));
    }

    @Test
    void missingSignerAndVersionGapFailClosedBeforeAnyFactWrite() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 7L, 8L, "synthetic"));
        @SuppressWarnings("unchecked") ObjectProvider<SigningPort> signers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ObjectPort> objects = mock(ObjectProvider.class);
        PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
        var service = new MemberBenefitPackageService(tenants, mock(ScopeAuthorizationService.class),
            mock(TenantResourceNamespace.class), mock(MemberBenefitPackageSourcePort.class),
            mock(MemberPricePackageSourcePort.class), persistence, signers, objects,
            mock(PromotionIdGenerator.class), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.publish(1101L, 2, 0, NOW.plusSeconds(60), CORRELATION))
            .hasMessageContaining("PRM-MBP-PKG-005");
        verify(persistence, never()).insertMemberBenefitPackage(any());
    }
}
