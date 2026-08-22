package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.inventory.application.port.StoreOnboardingInventoryPort;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.StoreOnboardingInventoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 只返回目标门店当前生效的仓库库存策略数量，不暴露余额或历史流水。 */
@Service
@RequiredArgsConstructor
public class StoreOnboardingInventoryService implements StoreOnboardingInventoryPort {
    private final StoreOnboardingInventoryMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public InventoryReadiness readiness(Long storeId) {
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(storeId);
        int count = mapper.countEffectiveWarehousePolicies(tenantContext.requireTenantId(), storeId);
        String hash = CanonicalJson.from(Map.of("storeId", storeId, "activePolicyCount", count)).sha256();
        return new InventoryReadiness(storeId, count, hash);
    }
}
