package com.jingshanghui.pos.reporting.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Reporting Owner 的不可变命令和查询模型。 */
public final class ReportingCommands {
    private ReportingCommands() {
    }

    /**
     * 统一来源事件；tenantId 刻意缺席，只能由可信上下文注入。
     * @param sourceEventId 来源事件 ULID
     * @param sourceOwner 权威事实 Owner
     * @param sourceAggregateId 来源聚合标识
     * @param sourceSequence 来源分区内单调序号
     * @param partitionKey 来源分区键
     * @param schemaVersion 事件 Schema 版本
     * @param projectionVersion 投影引擎兼容版本
     * @param contentSha256 内容摘要
     * @param occurredAt 事实发生时刻
     * @param businessDate 来源 Owner 冻结的业务日
     * @param orgId 组织标识
     * @param storeId 门店标识
     * @param terminalId 可选终端标识
     * @param cashierId 可选收银员标识
     * @param warehouseId 可选仓库 ULID
     * @param skuId 可选 SKU 标识
     * @param currency 币种
     * @param metricFamily 指标族
     * @param sales 销售收银冻结增量
     * @param inventoryCost 库存成本冻结增量
     * @param correlationId 关联标识
     */
    public record SourceEvent(String sourceEventId, String sourceOwner, String sourceAggregateId,
                              long sourceSequence, String partitionKey, String schemaVersion,
                              String projectionVersion, String contentSha256, Instant occurredAt,
                              LocalDate businessDate, Long orgId, Long storeId, String terminalId,
                              Long cashierId, String warehouseId, Long skuId, String currency,
                              String metricFamily, SalesDelta sales, InventoryCostDelta inventoryCost,
                              String correlationId) {
    }

    /**
     * 来源 Owner 冻结的销售收银增量，金额单位均为最小货币单位整数。
     * @param orderCount 成交订单数增量
     * @param cancelledOrderCount 取消订单数增量
     * @param returnCount 退货次数增量
     * @param grossMinor 原价金额增量
     * @param discountMinor 优惠金额增量
     * @param surchargeMinor 附加金额增量
     * @param receivableMinor 应收金额增量
     * @param refundMinor 退款金额增量
     * @param cashReceivedMinor 现金实收增量
     * @param cashRefundedMinor 现金退回增量
     * @param shiftDifferenceMinor 班次差异增量
     * @param promotionSnapshotCount 成交促销快照计数增量
     */
    public record SalesDelta(long orderCount, long cancelledOrderCount, long returnCount, long grossMinor,
                             long discountMinor, long surchargeMinor, long receivableMinor, long refundMinor,
                             long cashReceivedMinor, long cashRefundedMinor, long shiftDifferenceMinor,
                             long promotionSnapshotCount) {
    }

    /**
     * 来源 Owner 冻结的库存成本增量，数量和最小货币单位成本均为 DECIMAL(25,6)。
     * @param onHandDelta 在手数量变化
     * @param availableDelta 可用数量变化
     * @param reservedDelta 预占数量变化
     * @param ledgerQuantityDelta 库存流水数量变化
     * @param purchaseQuantityDelta 采购数量影响
     * @param stocktakeQuantityDelta 盘点数量影响
     * @param transferQuantityDelta 调拨数量影响
     * @param inventoryValueDeltaMinor 库存价值变化
     * @param cogsDeltaMinor 销售成本变化
     * @param purchaseCostDeltaMinor 采购成本影响
     * @param stocktakeCostDeltaMinor 盘点成本影响
     * @param transferCostDeltaMinor 调拨成本影响
     */
    public record InventoryCostDelta(BigDecimal onHandDelta, BigDecimal availableDelta, BigDecimal reservedDelta,
                                     BigDecimal ledgerQuantityDelta, BigDecimal purchaseQuantityDelta,
                                     BigDecimal stocktakeQuantityDelta, BigDecimal transferQuantityDelta,
                                     BigDecimal inventoryValueDeltaMinor, BigDecimal cogsDeltaMinor,
                                     BigDecimal purchaseCostDeltaMinor, BigDecimal stocktakeCostDeltaMinor,
                                     BigDecimal transferCostDeltaMinor) {
    }

    /**
     * 销售收银日投影查询。
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeId 门店数据范围目标
     * @param terminalId 可选终端过滤
     * @param cashierId 可选收银员过滤
     */
    public record SalesQuery(LocalDate fromDate, LocalDate toDate, Long storeId,
                             String terminalId, Long cashierId) {
    }

    /**
     * 版本化销售 keyset 分页查询；tenantId 不进入客户端模型。
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeId 门店数据范围目标
     * @param terminalId 可选终端过滤
     * @param cashierId 可选收银员过滤
     * @param cursor 上一页服务端签发游标，首屏为空
     * @param limit 单页行数，范围 1 至 500
     */
    public record SalesPageQuery(LocalDate fromDate, LocalDate toDate, Long storeId,
                                 String terminalId, Long cashierId, String cursor, int limit) {
    }

    /**
     * 库存成本日投影查询。
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeId 门店数据范围目标
     * @param warehouseId 可选仓库过滤
     * @param skuId 可选 SKU 过滤
     */
    public record InventoryCostQuery(LocalDate fromDate, LocalDate toDate, Long storeId,
                                     String warehouseId, Long skuId) {
    }

    /**
     * 版本化库存成本 keyset 分页查询；tenantId 不进入客户端模型。
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeId 门店数据范围目标
     * @param warehouseId 可选仓库过滤
     * @param skuId 可选 SKU 过滤
     * @param cursor 上一页服务端签发游标，首屏为空
     * @param limit 单页行数，范围 1 至 500
     */
    public record InventoryCostPageQuery(LocalDate fromDate, LocalDate toDate, Long storeId,
                                         String warehouseId, Long skuId, String cursor, int limit) {
    }

    /**
     * 影子投影全量重建命令。
     * @param rebuildId 重建 ULID
     * @param projectionVersion 新投影版本
     * @param fromDate 重建起始业务日
     * @param toDate 重建结束业务日
     * @param correlationId 关联标识
     */
    public record Rebuild(String rebuildId, String projectionVersion, LocalDate fromDate,
                          LocalDate toDate, String correlationId) {
    }

    /**
     * 报表导出申请。
     * @param exportId 导出 ULID
     * @param reportType 服务端白名单报表类型
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeIds 门店范围
     * @param fields 字段白名单子集
     * @param correlationId 关联标识
     */
    public record ExportRequest(String exportId, String reportType, LocalDate fromDate, LocalDate toDate,
                                Set<Long> storeIds, Set<String> fields, String correlationId) {
        public ExportRequest {
            storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
            fields = fields == null ? Set.of() : Set.copyOf(fields);
        }
    }

    /**
     * 导出审批命令。
     * @param exportId 导出 ULID
     * @param approved 是否批准
     * @param reason 审批原因
     * @param expectedVersion 期望版本
     * @param correlationId 关联标识
     */
    public record ExportApproval(String exportId, boolean approved, String reason,
                                 int expectedVersion, String correlationId) {
    }

    /**
     * 导出生成命令。
     * @param exportId 导出 ULID
     * @param expectedVersion 期望版本
     * @param correlationId 关联标识
     */
    public record ExportGenerate(String exportId, int expectedVersion, String correlationId) {
    }

    /**
     * 差异状态处理命令。
     * @param differenceId 差异 ULID
     * @param toState 目标状态
     * @param reason 处理原因
     * @param expectedVersion 期望版本
     * @param correlationId 关联标识
     */
    public record DifferenceTransition(String differenceId, String toState, String reason,
                                       int expectedVersion, String correlationId) {
    }
}
