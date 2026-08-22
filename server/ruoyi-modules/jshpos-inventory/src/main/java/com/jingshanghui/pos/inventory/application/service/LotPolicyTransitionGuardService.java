package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.catalog.application.port.LotPolicyTransitionGuardPort;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Inventory Owner 对批次能力关闭提供最小化、失败关闭的跨 Owner 守卫。 */
@Service
@RequiredArgsConstructor
public class LotPolicyTransitionGuardService implements LotPolicyTransitionGuardPort {
    private final LotInventoryMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public void requireCanDisable(Long storeId, Long skuId) {
        if (storeId == null || storeId <= 0 || skuId == null || skuId <= 0) {
            throw new ServiceException("LOT-POLICY-011: 批次能力关闭范围非法", 400);
        }
        authorization.requireStoreAccess(storeId);
        if (mapper.countLotFacts(tenantContext.requireTenantId(), storeId, skuId) > 0) {
            throw new ServiceException("LOT-POLICY-012: 已存在批次或可退历史事实，商业V1禁止关闭批次能力", 409);
        }
    }
}
