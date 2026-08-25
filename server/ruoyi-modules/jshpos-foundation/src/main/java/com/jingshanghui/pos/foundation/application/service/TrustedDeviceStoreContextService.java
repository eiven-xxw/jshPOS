package com.jingshanghui.pos.foundation.application.service;

import com.jingshanghui.pos.foundation.application.port.TrustedDeviceStoreContextPort;
import com.jingshanghui.pos.foundation.application.context.VerifiedDeviceTenantScope;
import com.jingshanghui.pos.foundation.domain.BusinessDay;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;

/** 使用已验证设备所属租户读取最小门店资料，避免认证前建立用户租户上下文。 */
@Service
@RequiredArgsConstructor
public class TrustedDeviceStoreContextService implements TrustedDeviceStoreContextPort {
    private final StoreMapper storeMapper;
    private final VerifiedDeviceTenantScope deviceTenantScope;

    @Override
    @Transactional(readOnly = true)
    public TrustedDeviceStoreContext resolve(String tenantId, Long orgUnitId, Long storeId, Instant at) {
        if (tenantId == null || !tenantId.matches("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$")
            || orgUnitId == null || orgUnitId <= 0 || storeId == null || storeId <= 0 || at == null) {
            throw new ServiceException("TRM_STORE_CONTEXT_INVALID: 可信门店查询参数无效", 400);
        }
        deviceTenantScope.requireMatches(tenantId, orgUnitId, storeId);
        StoreEntity store = TenantHelper.dynamic(tenantId, () -> storeMapper.selectById(storeId));
        if (store == null || !tenantId.equals(store.getTenantId()) || !orgUnitId.equals(store.getOrgUnitId())
            || !"ACTIVE".equals(store.getStatus())) {
            throw new ServiceException("TRM_STORE_NOT_AVAILABLE: 终端绑定门店不存在、越权或未启用", 409);
        }
        ZoneId zone = BusinessDay.requireZoneId(store.getZoneId());
        return new TrustedDeviceStoreContext(store.getStoreCode(), store.getStoreName(), zone.getId(),
            BusinessDay.calculate(at, zone, store.getBusinessDayStart()), store.getStatus());
    }
}
