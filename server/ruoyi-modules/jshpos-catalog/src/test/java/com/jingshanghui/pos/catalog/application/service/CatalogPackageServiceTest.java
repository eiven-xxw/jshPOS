package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.packagev1.PackageObjectPort;
import com.jingshanghui.pos.catalog.application.packagev1.PackageSigningPort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.security.TenantResourceNamespace;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class CatalogPackageServiceTest {

    @SuppressWarnings("unchecked")
    @Test
    void requiresExternalPortsAndUsesTrustedTenantNamespaceWhenConfigured() {
        CatalogMapper mapper = mock(CatalogMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        TenantResourceNamespace namespace = mock(TenantResourceNamespace.class);
        ObjectProvider<PackageSigningPort> signerProvider = mock(ObjectProvider.class);
        ObjectProvider<PackageObjectPort> objectProvider = mock(ObjectProvider.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        CatalogPackageService missing = new CatalogPackageService(mapper, context, authorization, namespace,
            mock(DomainAuditService.class), signerProvider, objectProvider,
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC), mock(CatalogOutboxService.class));
        assertThatThrownBy(() -> missing.publish(11L, 1, 0)).isInstanceOf(ServiceException.class).hasMessageContaining("KMS/HSM");

        PackageSigningPort signer = mock(PackageSigningPort.class);
        PackageObjectPort objects = mock(PackageObjectPort.class);
        when(signerProvider.getIfAvailable()).thenReturn(signer);
        when(objectProvider.getIfAvailable()).thenReturn(objects);
        when(mapper.listProductPackageRows("TENANT_A")).thenReturn(List.of("PRODUCT|SKU-A|A|STANDARD|ACTIVE"));
        when(mapper.listPricePackageRows("TENANT_A", 11L)).thenReturn(List.of("PRICE|TENANT_BASE|0|1|2|199"));
        when(signer.sign(eq("TENANT_A"), any())).thenReturn(new PackageSigningPort.SigningResult("kms-key-1", "Ed25519", new byte[]{1, 2}));
        when(namespace.objectKey(anyString())).thenReturn("tenant/TENANT_A/object/catalog.jshpkg");
        PackageView stored = new PackageView(99L, 11L, 1L, 0L, "1.0", "a".repeat(64), "Ed25519",
            "kms-key-1", "tenant/TENANT_A/object/catalog.jshpkg", 2, Instant.parse("2026-08-16T00:00:00Z"));
        when(mapper.findLatestPackage("TENANT_A", 11L)).thenReturn(null, stored);

        CatalogPackageService configured = new CatalogPackageService(mapper, context, authorization, namespace,
            mock(DomainAuditService.class), signerProvider, objectProvider,
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC), mock(CatalogOutboxService.class));
        assertThat(configured.publish(11L, 1, 0).recordCount()).isEqualTo(2);
        verify(authorization, times(2)).requireStoreAccess(11L);
        verify(objects).put(eq("tenant/TENANT_A/object/catalog.jshpkg"), any(), eq(new byte[]{1, 2}));
        verify(mapper).insertPackage(eq("TENANT_A"), anyLong(), eq(11L), eq(1L), eq(0L), eq("1.0"),
            anyString(), eq("kms-key-1"), eq("tenant/TENANT_A/object/catalog.jshpkg"), eq(2), any());

        when(mapper.findLatestPackage("TENANT_A", 11L)).thenReturn(stored);
        assertThatThrownBy(() -> configured.publish(11L, 3, 1)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("严格连续");
    }
}
