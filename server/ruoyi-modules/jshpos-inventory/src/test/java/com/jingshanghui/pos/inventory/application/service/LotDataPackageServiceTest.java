package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.packagev1.PackageSigningPort;
import com.jingshanghui.pos.catalog.application.port.LotPolicyReadPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotPackageRelease;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.LotPackageWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证批次包使用独立单调版本、稳定发布键和正式签名端口。 */
class LotDataPackageServiceTest {
    private static final String RELEASE = "01K2A000000000000000000081";
    private static final String WAREHOUSE = "01K2A000000000000000000082";
    private static final String LOT = "01K2A000000000000000000083";
    private static final String POLICY = "01K2A000000000000000000084";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T02:00:00Z"), ZoneOffset.UTC);

    @Test
    @SuppressWarnings("unchecked")
    void firstReleaseUsesVersionOneAndPersistsSignedImmutableArtifact() throws Exception {
        LotInventoryMapper mapper = mock(LotInventoryMapper.class);
        LotPolicyReadPort policies = mock(LotPolicyReadPort.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        StoreIndustryReadPort industries = mock(StoreIndustryReadPort.class);
        ObjectProvider<PackageSigningPort> signingPorts = mock(ObjectProvider.class);
        PackageSigningPort signer = mock(PackageSigningPort.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "operator"));
        when(industries.requireCurrentIndustry(10L)).thenReturn(new IndustryBinding(10L, 20L, 30L, 1,
            "COMMUNITY_SUPERMARKET", "a".repeat(64), "Asia/Shanghai", LocalTime.of(4, 0)));
        when(policies.listEffective(eq(10L), any())).thenReturn(List.of(policy()));
        when(mapper.findPackageLots("TENANT_A", 10L, WAREHOUSE, 100001)).thenReturn(List.of(lot()));
        when(signingPorts.getIfAvailable()).thenReturn(signer);
        when(signer.sign(eq("TENANT_A"), any())).thenReturn(new PackageSigningPort.SigningResult(
            "kms-lot-v1", "Ed25519", new byte[64]));
        LotDataPackageService service = new LotDataPackageService(mapper, policies, context, authorization,
            industries, signingPorts, new ObjectMapper().findAndRegisterModules(), new UlidGenerator(CLOCK), CLOCK);

        PackageArtifact artifact = service.publish(10L, WAREHOUSE, RELEASE, "trace-lot-package");

        String json = new String(artifact.payload(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"packageVersion\":1", "\"previousVersion\":0",
            "\"industryTemplateVersionId\":\"30\"", "\"businessZoneId\":\"Asia/Shanghai\"",
            "\"businessDayStart\":\"04:00\"");
        ArgumentCaptor<LotPackageWrite> write = ArgumentCaptor.forClass(LotPackageWrite.class);
        verify(mapper).insertPackageRelease(write.capture());
        assertThat(write.getValue().packageVersion()).isEqualTo(1);
        assertThat(write.getValue().previousVersion()).isZero();
        assertThat(write.getValue().recordCount()).isEqualTo(2);
        verify(mapper).insertAudit(any());
        verify(mapper).insertOutbox(any());

        LotPackageWrite saved = write.getValue();
        when(mapper.findPackageByRelease("TENANT_A", RELEASE)).thenReturn(new LotPackageRelease(
            saved.releaseId(), saved.packageVersion(), saved.previousVersion(), saved.sourceSha256(),
            saved.payloadBytes(), saved.payloadSha256(), saved.signingKeyId(), saved.signatureBytes(),
            saved.recordCount(), saved.generatedAt()));
        when(signingPorts.getIfAvailable()).thenReturn(null);

        PackageArtifact replay = service.publish(10L, WAREHOUSE, RELEASE, "trace-lot-package-replay");

        assertThat(replay.payload()).isEqualTo(artifact.payload());
        assertThat(replay.payloadSha256()).isEqualTo(artifact.payloadSha256());
        verify(signer, times(1)).sign(eq("TENANT_A"), any());
        verify(mapper, times(1)).insertPackageRelease(any());

        when(mapper.findLatestPackage("TENANT_A", 10L, WAREHOUSE)).thenReturn(new LotPackageRelease(
            saved.releaseId(), saved.packageVersion(), saved.previousVersion(), saved.sourceSha256(),
            saved.payloadBytes(), saved.payloadSha256(), saved.signingKeyId(), saved.signatureBytes(),
            saved.recordCount(), saved.generatedAt()));
        assertThat(service.latest(10L, WAREHOUSE).payloadSha256()).isEqualTo(artifact.payloadSha256());

        when(industries.requireCurrentIndustry(10L)).thenReturn(new IndustryBinding(10L, 20L, 31L, 2,
            "COMMUNITY_SUPERMARKET", "c".repeat(64), "Asia/Shanghai", LocalTime.of(4, 0)));
        assertThatThrownBy(() -> service.latest(10L, WAREHOUSE)).hasMessageContaining("LOT-DPK-010");
    }

    private static PolicyView policy() {
        return new PolicyView(POLICY, 10L, 1001L, true, "EXPLICIT_EXPIRY_DATE", null, 3,
            "COMMUNITY_SUPERMARKET", 30L, Instant.parse("2026-08-01T00:00:00Z"), "b".repeat(64), "PUBLISHED");
    }

    private static LotView lot() {
        LocalDate day = LocalDate.of(2026, 8, 23);
        return new LotView(LOT, 10L, WAREHOUSE, 1001L, 2001L, "SUP-1", "INT-1", day.minusDays(2),
            day.minusDays(1), day.plusDays(5), POLICY, 3, new BigDecimal("2.000000"), 1L, "AVAILABLE",
            LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
    }
}
