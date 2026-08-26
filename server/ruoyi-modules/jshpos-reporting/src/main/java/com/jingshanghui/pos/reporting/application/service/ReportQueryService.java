package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.InventoryCostQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SalesQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SalesPageQuery;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.InventoryCostDailyView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesDailyView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesPageView;
import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import com.jingshanghui.pos.reporting.domain.ReportRules;
import com.jingshanghui.pos.reporting.domain.SalesReportReadIdentity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 可信租户和门店数据范围内的报表查询入口。 */
@Service
@RequiredArgsConstructor
public class ReportQueryService {
    private final ReportingPersistencePort persistence;
    private final ReportingBatchReadPort batchReadPort;
    private final SalesPageCursorCodec cursorCodec;
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

    /** 新客户端使用的有界销售 keyset 分页；旧列表契约在兼容窗口内保持不变。 */
    @Transactional(readOnly = true)
    public SalesPageView salesPage(SalesPageQuery query) {
        String tenantId = tenantContext.requireTenantId();
        ReportRules.requireDateRange(query.fromDate(), query.toDate());
        int limit = ReportRules.requireSalesPageLimit(query.limit());
        authorizationService.requireStoreAccess(query.storeId());
        String projectionVersion = persistence.activeProjectionVersion(tenantId, "SALES");
        if (projectionVersion == null) {
            return new SalesPageView(List.of(), null, false, null);
        }
        String filterSha256 = SalesReportReadIdentity.filterSha256(tenantId, projectionVersion, query.fromDate(),
            query.toDate(), List.of(query.storeId()), query.terminalId(), query.cashierId());
        ReportingBatchReadPort.SalesKey after = query.cursor() == null || query.cursor().isBlank() ? null
            : cursorCodec.decodeAndVerify(query.cursor(), tenantId, filterSha256, projectionVersion).after();
        List<SalesDailyView> fetched = batchReadPort.readSales(new ReportingBatchReadPort.SalesBatchRequest(
            tenantId, projectionVersion, query.fromDate(), query.toDate(), List.of(query.storeId()),
            query.terminalId(), query.cashierId(), after, limit + 1, filterSha256));
        boolean hasMore = fetched.size() > limit;
        List<SalesDailyView> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore ? cursorCodec.encode(new SalesPageCursorCodec.CursorEnvelope(tenantId,
            filterSha256, projectionVersion, ReportingBatchReadPort.SalesKey.from(items.get(items.size() - 1))))
            : null;
        return new SalesPageView(items, nextCursor, hasMore, projectionVersion);
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
