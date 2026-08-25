package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.ObjectPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.SigningResult;
import com.jingshanghui.pos.promotion.application.port.PromotionPackagePorts.StoredObject;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.PackageItemWrite;
import com.jingshanghui.pos.promotion.domain.PromotionPackageCodec;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证规则包冻结、签名失败关闭、租户对象命名空间与审计事件同事务编排。 */
class PromotionPackageServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-17T02:00:00Z");
    private static final String CORRELATION = "01K5R000000000000000000004";

    @Test
    void publishFreezesFutureWindowMembershipAndStableRuleOrder() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        TenantResourceNamespace namespace = mock(TenantResourceNamespace.class);
        PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
        SigningPort signer = mock(SigningPort.class);
        ObjectPort objects = mock(ObjectPort.class);
        @SuppressWarnings("unchecked") ObjectProvider<SigningPort> signers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ObjectPort> objectProviders = mock(ObjectProvider.class);
        PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 7L, 8L, "synthetic"));
        when(tenants.requireTenantId()).thenReturn("TENANT_A");
        when(signers.getIfAvailable()).thenReturn(signer);
        when(objectProviders.getIfAvailable()).thenReturn(objects);
        when(namespace.objectKey(anyString())).thenReturn("tenant/TENANT_A/object/promotion.jshpkg");
        when(signer.sign(eq("TENANT_A"), any())).thenReturn(new SigningResult("synthetic-key-v1", new byte[64]));
        when(ids.next()).thenReturn("01K5R000000000000000000100", "01K5R000000000000000000101",
            "01K5R000000000000000000102", "01K5R000000000000000000103",
            "01K5R000000000000000000104");
        when(persistence.listPackageRuleDefinitions(eq("TENANT_A"), eq(1101L), any(), any()))
            .thenReturn(List.of(row("01K5R000000000000000000012"), row("01K5R000000000000000000011")));
        when(persistence.findManualPolicy("TENANT_A", 1101L)).thenReturn(policy());
        AtomicReference<byte[]> storedPayload = new AtomicReference<>();
        AtomicReference<byte[]> storedSignature = new AtomicReference<>();
        doAnswer(invocation -> {
            storedPayload.set(invocation.getArgument(1));
            storedSignature.set(invocation.getArgument(2));
            return null;
        }).when(objects).put(eq("tenant/TENANT_A/object/promotion.jshpkg"), any(), any());
        when(persistence.findPackage("TENANT_A", 1101L, 1)).thenAnswer(ignored -> new PackageView(
            "01K5R000000000000000000100", 1101L, 1, 0,
            PromotionPackageCodec.sha256(storedPayload.get()), "synthetic-key-v1",
            "tenant/TENANT_A/object/promotion.jshpkg", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC)));
        when(objects.get("tenant/TENANT_A/object/promotion.jshpkg"))
            .thenAnswer(ignored -> new StoredObject(storedPayload.get(), storedSignature.get()));
        var service = new PromotionPackageService(tenants, authorization, namespace, persistence,
            new PromotionRuleDefinitionCodec(new ObjectMapper()), new ManualPolicyCodec(new ObjectMapper()),
            signers, objectProviders, ids,
            Clock.fixed(NOW, ZoneOffset.UTC));

        PackageView published = service.publish(1101L, 1, 0, NOW.plusSeconds(3600), CORRELATION);
        assertThat(published.payloadSha256()).isEqualTo(PromotionPackageCodec.sha256(storedPayload.get()));

        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(persistence).listPackageRuleDefinitions(eq("TENANT_A"), eq(1101L),
            eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)), end.capture());
        assertThat(end.getValue()).isEqualTo(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC));
        ArgumentCaptor<PackageItemWrite> items = ArgumentCaptor.forClass(PackageItemWrite.class);
        verify(persistence, times(2)).insertPackageItem(items.capture());
        assertThat(items.getAllValues()).extracting(PackageItemWrite::ruleVersionId)
            .containsExactly("01K5R000000000000000000011", "01K5R000000000000000000012");
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(objects).put(startsWith("tenant/TENANT_A/object/"), payload.capture(), argThat(value -> value.length == 64));
        assertThat(service.download(1101L, 1).payload()).containsExactly(payload.getValue());
        CanonicalJson.Result canonicalPolicy = canonicalPolicy();
        assertThat(new String(payload.getValue(), java.nio.charset.StandardCharsets.UTF_8))
            .contains("@MANUAL_POLICY|31|" + canonicalPolicy.sha256() + "|" + canonicalPolicy.json());
        verify(persistence).insertAudit(any());
        verify(persistence).insertOutbox(any());
        verify(authorization, times(3)).requireStoreAccess(1101L);
    }

    @Test
    void publishFailsClosedWithoutInfrastructureOrWithMalformedSignature() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 7L, 8L, "synthetic"));
        @SuppressWarnings("unchecked") ObjectProvider<SigningPort> signers = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<ObjectPort> objects = mock(ObjectProvider.class);
        var persistence = mock(PromotionPersistencePort.class);
        var service = new PromotionPackageService(tenants, mock(ScopeAuthorizationService.class),
            mock(TenantResourceNamespace.class), persistence,
            new PromotionRuleDefinitionCodec(new ObjectMapper()), new ManualPolicyCodec(new ObjectMapper()),
            signers, objects,
            mock(PromotionIdGenerator.class), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> service.publish(1101L, 1, 0, NOW.plusSeconds(10), CORRELATION))
            .hasMessageContaining("PRM-PKG-010");
        verify(persistence, never()).insertPackage(any());
    }

    private static PromotionPersistencePort.PublishedRuleRow row(String versionId) {
        return new PromotionPersistencePort.PublishedRuleRow(versionId, "AMOUNT_OFF", 1, "STACKABLE", null,
            LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
            LocalDateTime.ofInstant(NOW.plusSeconds(7200), ZoneOffset.UTC), "STORE:1101|CHANNEL:POS", 10L,
            null, null, null, null, null, "[]");
    }

    private static PromotionPersistencePort.ManualPolicyRow policy() {
        CanonicalJson.Result canonical = canonicalPolicy();
        // 模拟 MySQL JSON 列读取时的键顺序和空格变化，封包前必须再次规范化。
        String mysqlJson = "{\"withApprovalMinor\": 1000, \"withoutApprovalMinor\": 100, "
            + "\"roundingMultiplesMinor\": [1, 10], \"policyType\": \"PROMOTION_MANUAL_AUTHORITY\", "
            + "\"minimumLinePayableMinor\": 20, \"maximumRoundingMinor\": 9}";
        return new PromotionPersistencePort.ManualPolicyRow(31L, canonical.sha256(), mysqlJson);
    }

    private static CanonicalJson.Result canonicalPolicy() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policyType", "PROMOTION_MANUAL_AUTHORITY");
        content.put("withoutApprovalMinor", 100L);
        content.put("withApprovalMinor", 1000L);
        content.put("minimumLinePayableMinor", 20L);
        content.put("maximumRoundingMinor", 9L);
        content.put("roundingMultiplesMinor", List.of(1L, 10L));
        return CanonicalJson.from(content);
    }
}
