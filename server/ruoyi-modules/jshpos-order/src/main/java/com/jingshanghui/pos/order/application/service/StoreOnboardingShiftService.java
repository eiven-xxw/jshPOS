package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.port.StoreOnboardingShiftPort;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.StoreOnboardingShiftMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** 只读取 OPEN/CLOSING 班次数量，禁止 Onboarding Owner 直接访问班次私有表。 */
@Service
@RequiredArgsConstructor
public class StoreOnboardingShiftService implements StoreOnboardingShiftPort {
    private final StoreOnboardingShiftMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public ShiftReadiness readiness(Long storeId) {
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(storeId);
        int count = mapper.countOpenOrClosing(tenantContext.requireTenantId(), storeId);
        String hash = CanonicalJson.from(Map.of("storeId", storeId, "openOrClosingCount", count)).sha256();
        return new ShiftReadiness(storeId, count, hash);
    }
}
