package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryTransitionGuardPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inventory Owner 阻止有批次历史的门店丢失批次能力。 */
@Service
@RequiredArgsConstructor
public class LotIndustryTransitionGuardService implements StoreIndustryTransitionGuardPort {
    private static final String COMMUNITY_SUPERMARKET = "COMMUNITY_SUPERMARKET";
    private final LotInventoryMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public void requireCanActivate(IndustryTransition transition) {
        if (transition == null || transition.storeId() == null || transition.storeId() <= 0
            || transition.fromIndustry() == null || transition.toIndustry() == null) {
            throw new ServiceException("LOT-POLICY-013: 行业模板切换范围非法", 400);
        }
        authorization.requireStoreAccess(transition.storeId());
        if (COMMUNITY_SUPERMARKET.equals(transition.fromIndustry())
            && !COMMUNITY_SUPERMARKET.equals(transition.toIndustry())
            && mapper.countStoreLotFacts(tenantContext.requireTenantId(), transition.storeId()) > 0) {
            throw new ServiceException("LOT-POLICY-014: 门店已有批次或可退历史事实，禁止关闭批次行业能力", 409);
        }
    }
}
