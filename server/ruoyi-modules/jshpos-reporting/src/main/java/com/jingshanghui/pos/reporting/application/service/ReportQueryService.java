package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.InventoryCostQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SalesQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.InventoryCostDailyView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesDailyView;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 可信租户和门店数据范围内的报表查询入口。 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {
    private final ReportingPersistencePort persistence;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public List<SalesDailyView> sales(SalesQuery query) {
        String tenantId = tenantContext.requireTenantId();
        ReportRules.requireDateRange(query.fromDate(), query.toDate());
        authorizationService.requireStoreAccess(query.storeId());
        String version = persistence.activeProjectionVersion(tenantId, "SALES");
        return version == null ? List.of() : persistence.querySales(tenantId, version, query.fromDate(),
            query.toDate(), query.storeId(), query.terminalId(), query.cashierId());
    }

    @Transactional(readOnly = true)
    public List<InventoryCostDailyView> inventoryCost(InventoryCostQuery query) {
        String tenantId = tenantContext.requireTenantId();
        ReportRules.requireDateRange(query.fromDate(), query.toDate());
        authorizationService.requireStoreAccess(query.storeId());
        String version = persistence.activeProjectionVersion(tenantId, "INVENTORY_COST");
        return version == null ? List.of() : persistence.queryInventoryCost(tenantId, version, query.fromDate(),
            query.toDate(), query.storeId(), query.warehouseId(), query.skuId());
    }
}
