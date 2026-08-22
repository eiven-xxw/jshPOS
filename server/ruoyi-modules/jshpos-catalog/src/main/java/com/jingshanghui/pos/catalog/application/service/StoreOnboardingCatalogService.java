package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.port.StoreOnboardingCatalogPort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.StoreOnboardingCatalogMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/** Catalog Owner 对开店检查返回商品价格覆盖和正式数据包事实。 */
@Service
@RequiredArgsConstructor
public class StoreOnboardingCatalogService implements StoreOnboardingCatalogPort {
    private final StoreOnboardingCatalogMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public CatalogReadiness readiness(Long storeId) {
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(storeId);
        String tenantId = tenantContext.requireTenantId();
        int active = mapper.countActiveSku(tenantId);
        int priced = mapper.countPricedSku(tenantId, storeId);
        PackageView dataPackage = mapper.findLatestPackage(tenantId, storeId);
        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("storeId", storeId);
        fact.put("activeSkuCount", active);
        fact.put("pricedSkuCount", priced);
        fact.put("packageVersion", dataPackage == null ? null : dataPackage.packageVersion());
        fact.put("packageSha256", dataPackage == null ? null : dataPackage.payloadSha256());
        return new CatalogReadiness(storeId, active, priced, dataPackage == null ? null : dataPackage.packageVersion(),
            dataPackage == null ? null : dataPackage.payloadSha256(), CanonicalJson.from(fact).sha256());
    }
}
