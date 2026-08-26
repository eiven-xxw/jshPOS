package com.jingshanghui.pos.reporting.application.port;

import com.jingshanghui.pos.reporting.application.model.ReportingViews.InventoryCostDailyView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesDailyView;

import java.time.LocalDate;
import java.util.List;

/**
 * Reporting Owner 的受控批量读取端口。
 *
 * <p>本端口只读取 Reporting 自有投影，调用方必须先从可信上下文取得租户并完成全部门店授权。
 * 当前准入销售日与库存成本日投影；支付对账不得借此提前整改。</p>
 */
public interface ReportingBatchReadPort {
    int MAX_INTERACTIVE_ROWS = 500;
    int MAX_EXPORT_CHUNK_ROWS = 10_000;

    /**
     * 按冻结顺序读取一批销售日投影。
     *
     * @param request 已完成可信租户和门店范围校验的批量查询
     * @return 最多 request.limit 条、顺序稳定的投影行
     */
    List<SalesDailyView> readSales(SalesBatchRequest request);

    /**
     * 按冻结顺序读取一批库存成本日投影。
     *
     * @param request 已完成可信租户和门店范围校验的批量查询
     * @return 最多 request.limit 条、顺序稳定的投影行
     */
    List<InventoryCostDailyView> readInventoryCost(InventoryCostBatchRequest request);

    /**
     * 销售 keyset 的最后一行身份。
     *
     * @param businessDate 业务日
     * @param storeId 全局门店标识
     * @param terminalId 终端标识
     * @param cashierId 收银员标识
     * @param currency 币种
     */
    record SalesKey(LocalDate businessDate, Long storeId, String terminalId, Long cashierId, String currency) {
        public static SalesKey from(SalesDailyView row) {
            return new SalesKey(row.businessDate(), row.storeId(), row.terminalId(), row.cashierId(), row.currency());
        }
    }

    /**
     * 销售批量读取参数；tenantId 与 storeIds 只能由应用服务从可信上下文和授权范围注入。
     *
     * @param tenantId 可信租户标识
     * @param projectionVersion 冻结投影版本
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeIds 已逐项授权且去重排序的门店集合
     * @param terminalId 可选终端过滤
     * @param cashierId 可选收银员过滤
     * @param after 上一批最后一行，首批为 null
     * @param limit 本次最大返回行数
     * @param requestSha256 绑定租户、筛选和投影版本的规范摘要
     */
    record SalesBatchRequest(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate,
                             List<Long> storeIds, String terminalId, Long cashierId, SalesKey after,
                             int limit, String requestSha256) {
        public SalesBatchRequest {
            storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
        }
    }

    /**
     * 库存成本 keyset 的最后一行身份。
     * @param businessDate 业务日
     * @param storeId 全局门店标识
     * @param warehouseId 仓库标识
     * @param skuId SKU 标识
     * @param currency 币种
     */
    record InventoryCostKey(LocalDate businessDate, Long storeId, String warehouseId, Long skuId,
                            String currency) {
        public static InventoryCostKey from(InventoryCostDailyView row) {
            return new InventoryCostKey(row.businessDate(), row.storeId(), row.warehouseId(), row.skuId(),
                row.currency());
        }
    }

    /**
     * 库存成本批量读取参数；tenantId 与 storeIds 只能由应用服务从可信上下文和授权范围注入。
     * @param tenantId 可信租户标识
     * @param projectionVersion 冻结投影版本
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeIds 已逐项授权且去重排序的门店集合
     * @param warehouseId 可选仓库过滤
     * @param skuId 可选 SKU 过滤
     * @param after 上一批最后一行，首批为 null
     * @param limit 本次最大返回行数
     * @param requestSha256 绑定租户、筛选和投影版本的规范摘要
     */
    record InventoryCostBatchRequest(String tenantId, String projectionVersion, LocalDate fromDate,
                                     LocalDate toDate, List<Long> storeIds, String warehouseId, Long skuId,
                                     InventoryCostKey after, int limit, String requestSha256) {
        public InventoryCostBatchRequest {
            storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
        }
    }
}
