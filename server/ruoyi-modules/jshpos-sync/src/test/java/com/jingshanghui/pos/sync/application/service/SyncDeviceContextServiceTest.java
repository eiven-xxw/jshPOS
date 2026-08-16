package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceRecord;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncDeviceContextServiceTest {

    private static final String DEVICE = "01K2A000000000000000000011";

    @Test
    void derivesTenantStoreAndTerminalFromTrustedRegistry() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        ScopeAuthorizationService scopes = mock(ScopeAuthorizationService.class);
        SyncMapper mapper = mock(SyncMapper.class);
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "alice"));
        when(mapper.findDevice("TENANT_A", DEVICE)).thenReturn(device("ACTIVE", 101L));
        when(mapper.touchDevice(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any())).thenReturn(1);
        var context = service(tenants, scopes, mapper).require(DEVICE, "1.0");
        assertThat(context.tenantId()).isEqualTo("TENANT_A");
        assertThat(context.storeId()).isEqualTo(1101L);
        assertThat(context.terminalId()).isEqualTo(DEVICE);
        verify(scopes).requireStoreAccess(1101L);
    }

    @Test
    void crossTenantMissingWrongUserAndBlockedDevicesFailClosed() {
        TrustedTenantContext tenants = mock(TrustedTenantContext.class);
        ScopeAuthorizationService scopes = mock(ScopeAuthorizationService.class);
        SyncMapper mapper = mock(SyncMapper.class);
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "alice"));
        var service = service(tenants, scopes, mapper);
        assertThatThrownBy(() -> service.require(DEVICE, "1.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("not visible");
        when(mapper.findDevice("TENANT_A", DEVICE)).thenReturn(device("ACTIVE", 999L));
        assertThatThrownBy(() -> service.require(DEVICE, "1.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("not visible");
        when(mapper.findDevice("TENANT_A", DEVICE)).thenReturn(device("BLOCKED", 101L));
        assertThatThrownBy(() -> service.require(DEVICE, "1.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("DEVICE_BLOCKED");
        when(mapper.findDevice("TENANT_A", DEVICE)).thenReturn(device("REVOKED", 101L));
        assertThatThrownBy(() -> service.require(DEVICE, "1.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("not active");
        assertThatThrownBy(() -> service.require(DEVICE, "2.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("supported protocol");
    }

    private SyncDeviceContextService service(TrustedTenantContext tenants,
                                             ScopeAuthorizationService scopes, SyncMapper mapper) {
        return new SyncDeviceContextService(tenants, scopes, mapper,
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC));
    }

    private DeviceRecord device(String status, Long userId) {
        return new DeviceRecord(DEVICE, "TENANT_A", 1101L, DEVICE, userId, status, "1.0", "1.0", 1L);
    }
}
