package com.jingshanghui.pos.foundation.application.service;

import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedDeviceStoreContextServiceTest {
    private final StoreMapper mapper = mock(StoreMapper.class);
    private final TrustedDeviceStoreContextService service = new TrustedDeviceStoreContextService(mapper);

    @Test
    void resolvesBusinessDateFromVerifiedTenantStore() {
        StoreEntity store = store("TENANT_A", 1001L, "ACTIVE");
        when(mapper.selectById(1101L)).thenReturn(store);

        var result = service.resolve("TENANT_A", 1001L, 1101L,
            Instant.parse("2026-08-17T21:30:00Z"));

        assertThat(result.storeName()).isEqualTo("合成便利店一店");
        assertThat(result.zoneId()).isEqualTo("Asia/Shanghai");
        assertThat(result.businessDate()).hasToString("2026-08-17");
    }

    @Test
    void rejectsCrossTenantOrInactiveStore() {
        when(mapper.selectById(1101L)).thenReturn(store("TENANT_B", 1001L, "ACTIVE"));
        assertThatThrownBy(() -> service.resolve("TENANT_A", 1001L, 1101L, Instant.now()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_STORE_NOT_AVAILABLE");

        when(mapper.selectById(1101L)).thenReturn(store("TENANT_A", 1001L, "SUSPENDED"));
        assertThatThrownBy(() -> service.resolve("TENANT_A", 1001L, 1101L, Instant.now()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_STORE_NOT_AVAILABLE");
    }

    private StoreEntity store(String tenantId, Long orgUnitId, String status) {
        StoreEntity entity = new StoreEntity();
        entity.setTenantId(tenantId);
        entity.setStoreId(1101L);
        entity.setOrgUnitId(orgUnitId);
        entity.setStoreCode("S001");
        entity.setStoreName("合成便利店一店");
        entity.setZoneId("Asia/Shanghai");
        entity.setBusinessDayStart(LocalTime.of(6, 0));
        entity.setStatus(status);
        return entity;
    }
}
