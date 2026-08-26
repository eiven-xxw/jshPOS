package com.jingshanghui.pos.reporting.infrastructure.persistence;

import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SourceEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** MyBatis XML 边界的具名参数与扁平来源行，避免 Map 和同类型参数错位。 */
public final class ReportingPersistenceParams {
    private ReportingPersistenceParams() {
    }

    /** @param tenantId 可信租户 @param sourceEventId 来源事件标识 */
    public record EventKey(String tenantId, String sourceEventId) {}
    /** @param tenantId 可信租户 @param objectId Reporting 对象标识 */
    public record ObjectKey(String tenantId, String objectId) {}
    /** @param tenantId 可信租户 @param event 已校验来源事件 */
    public record SourceEventParam(String tenantId, SourceEvent event) {}
    /** @param tenantId 可信租户 @param sourceEventId 来源事件 @param at 应用时刻 */
    public record EventTime(String tenantId, String sourceEventId, Instant at) {}
    /** @param tenantId 可信租户 @param sourceOwner 来源 Owner @param partitionKey 分区键 */
    public record CheckpointKey(String tenantId, String sourceOwner, String partitionKey) {}
    /**
     * @param tenantId 可信租户 @param sourceOwner 来源 Owner @param partitionKey 分区键
     * @param contiguous 连续序号 @param maximumSeen 最大已见 @param status 完整状态
     */
    public record CheckpointInsert(String tenantId, String sourceOwner, String partitionKey,
                                   long contiguous, long maximumSeen, String status) {}
    /**
     * @param tenantId 可信租户 @param sourceOwner 来源 Owner @param partitionKey 分区键
     * @param contiguous 连续序号 @param maximumSeen 最大已见 @param status 完整状态
     * @param expectedVersion 期望版本
     */
    public record CheckpointUpdate(String tenantId, String sourceOwner, String partitionKey,
                                   long contiguous, long maximumSeen, String status, int expectedVersion) {}
    /**
     * @param tenantId 可信租户 @param sourceOwner 来源 Owner @param partitionKey 分区键
     * @param sourceSequence 来源序号
     */
    public record SequenceKey(String tenantId, String sourceOwner, String partitionKey, long sourceSequence) {}
    /** @param tenantId 可信租户 @param family 指标族 @param projectionVersion 投影版本 */
    public record RegistryParam(String tenantId, String family, String projectionVersion) {}
    /**
     * @param tenantId 可信租户 @param targetProjectionVersion 目标投影版本
     * @param event 已校验来源事件 @param status 投影状态
     */
    public record ProjectionEvent(String tenantId, String targetProjectionVersion,
                                  SourceEvent event, String status) {}
    /**
     * @param tenantId 可信租户 @param targetProjectionVersion 目标投影版本
     * @param event 来源事件 @param checkpoint 处理检查点 @param dimensionSha256 维度摘要
     * @param processedAt 处理时刻
     */
    public record LineageParam(String tenantId, String targetProjectionVersion, SourceEvent event,
                               com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.CheckpointRow checkpoint,
                               String dimensionSha256, Instant processedAt) {}
    /** @param tenantId 可信租户 @param family 指标族 @param projectionVersion 投影版本 */
    public record FamilyStatusParam(String tenantId, String family, String projectionVersion, String status) {}
    /**
     * @param tenantId 可信租户 @param projectionVersion 活动投影版本 @param fromDate 起始日
     * @param toDate 结束日 @param storeId 门店 @param terminalId 终端 @param cashierId 收银员
     */
    public record SalesQueryParam(String tenantId, String projectionVersion, LocalDate fromDate,
                                  LocalDate toDate, Long storeId, String terminalId, Long cashierId) {}
    /**
     * @param tenantId 可信租户 @param projectionVersion 冻结投影版本 @param fromDate 起始业务日
     * @param toDate 结束业务日 @param storeIds 已授权门店集合 @param terminalId 可选终端
     * @param cashierId 可选收银员 @param afterBusinessDate 上批最后业务日 @param afterStoreId 上批最后门店
     * @param afterTerminalId 上批最后终端 @param afterCashierId 上批最后收银员
     * @param afterCurrency 上批最后币种 @param limit 本批最大行数
     */
    public record SalesPageParam(String tenantId, String projectionVersion, LocalDate fromDate,
                                 LocalDate toDate, List<Long> storeIds, String terminalId, Long cashierId,
                                 LocalDate afterBusinessDate, Long afterStoreId, String afterTerminalId,
                                 Long afterCashierId, String afterCurrency, int limit) {}
    /**
     * @param tenantId 可信租户 @param projectionVersion 活动投影版本 @param fromDate 起始日
     * @param toDate 结束日 @param storeId 门店 @param warehouseId 仓库 @param skuId SKU
     */
    public record InventoryQueryParam(String tenantId, String projectionVersion, LocalDate fromDate,
                                      LocalDate toDate, Long storeId, String warehouseId, Long skuId) {}
    /**
     * @param tenantId 可信租户 @param projectionVersion 投影版本 @param fromDate 起始日
     * @param toDate 结束日 @param storeIds 门店集合
     */
    public record CountParam(String tenantId, String projectionVersion, LocalDate fromDate,
                             LocalDate toDate, List<Long> storeIds) {}
    /**
     * @param tenantId 可信租户 @param projectionVersion 投影版本 @param fromDate 起始日 @param toDate 结束日
     */
    public record RangeParam(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate) {}
    /**
     * @param tenantId 可信租户 @param rebuildId 重建标识 @param state 状态 @param eventCount 事件数
     * @param digest 摘要 @param completedAt 完成时刻
     */
    public record RebuildCompletion(String tenantId, String rebuildId, String state, long eventCount,
                                    String digest, Instant completedAt) {}
    /** @param tenantId 可信租户 @param row 重建状态行 */
    public record RebuildParam(String tenantId,
                               com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.RebuildRow row) {}
    /** @param tenantId 可信租户 @param row 导出请求行 */
    public record ExportParam(String tenantId,
                              com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.ExportRow row) {}
    /** @param tenantId 可信租户 @param row 导出制品行 */
    public record ArtifactParam(String tenantId,
                                com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.ArtifactRow row) {}
    /** @param tenantId 可信租户 @param row 差异事实行 */
    public record DifferenceParam(String tenantId,
                                  com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.DifferenceRow row) {}
    /** @param tenantId 可信租户 @param limit 查询上限 */
    public record LimitParam(String tenantId, int limit) {}
    /**
     * @param tenantId 可信租户 @param exportId 导出标识 @param fromState 原状态 @param toState 目标状态
     * @param approvedBy 审批人 @param reasonSha256 原因摘要 @param expectedVersion 期望版本 @param changedAt 变更时刻
     */
    public record ExportTransition(String tenantId, String exportId, String fromState, String toState,
                                   Long approvedBy, String reasonSha256, int expectedVersion, Instant changedAt) {}
    /**
     * @param tenantId 可信租户 @param exportId 导出标识 @param tokenSha256 令牌摘要 @param userId 绑定用户
     * @param tokenExpiresAt 令牌过期 @param expectedVersion 期望版本
     */
    public record TokenIssue(String tenantId, String exportId, String tokenSha256, Long userId,
                             Instant tokenExpiresAt, int expectedVersion) {}
    /**
     * @param tenantId 可信租户 @param exportId 导出标识 @param tokenSha256 令牌摘要
     * @param userId 绑定用户 @param consumedAt 消费时刻
     */
    public record TokenConsume(String tenantId, String exportId, String tokenSha256,
                               Long userId, Instant consumedAt) {}
    /**
     * @param tenantId 可信租户 @param differenceId 差异标识 @param fromState 原状态 @param toState 目标状态
     * @param assignedTo 处理人 @param reasonSha256 原因摘要 @param expectedVersion 期望版本 @param changedAt 变更时刻
     */
    public record DifferenceTransitionParam(String tenantId, String differenceId, String fromState, String toState,
                                            Long assignedTo, String reasonSha256, int expectedVersion,
                                            Instant changedAt) {}
    /**
     * 扁平来源 Inbox 行。
     * @param sourceEventId 事件标识 @param sourceOwner 来源 Owner @param sourceAggregateId 聚合标识
     * @param sourceSequence 来源序号 @param partitionKey 分区 @param schemaVersion Schema 版本
     * @param projectionVersion 投影引擎版本 @param contentSha256 摘要 @param occurredAt 发生时刻
     * @param businessDate 业务日 @param orgId 组织 @param storeId 门店 @param terminalId 终端
     * @param cashierId 收银员 @param warehouseId 仓库 @param skuId SKU @param currency 币种
     * @param metricFamily 指标族 @param orderCount 订单数 @param cancelledOrderCount 取消数
     * @param returnCount 退货数 @param grossMinor 原价 @param discountMinor 优惠 @param surchargeMinor 附加
     * @param receivableMinor 应收 @param refundMinor 退款 @param cashReceivedMinor 现金实收
     * @param cashRefundedMinor 现金退回 @param shiftDifferenceMinor 班次差异 @param promotionSnapshotCount 快照数
     * @param onHandDelta 在手变化 @param availableDelta 可用变化 @param reservedDelta 预占变化
     * @param ledgerQuantityDelta 流水数量 @param purchaseQuantityDelta 采购数量 @param stocktakeQuantityDelta 盘点数量
     * @param transferQuantityDelta 调拨数量 @param inventoryValueDeltaMinor 库存价值 @param cogsDeltaMinor COGS
     * @param purchaseCostDeltaMinor 采购成本 @param stocktakeCostDeltaMinor 盘点成本
     * @param transferCostDeltaMinor 调拨成本 @param correlationId 关联标识
     */
    public record StoredEventRow(String sourceEventId, String sourceOwner, String sourceAggregateId,
                                 long sourceSequence, String partitionKey, String schemaVersion,
                                 String projectionVersion, String contentSha256, Instant occurredAt,
                                 LocalDate businessDate, Long orgId, Long storeId, String terminalId,
                                 Long cashierId, String warehouseId, Long skuId, String currency,
                                 String metricFamily, long orderCount, long cancelledOrderCount, long returnCount,
                                 long grossMinor, long discountMinor, long surchargeMinor, long receivableMinor,
                                 long refundMinor, long cashReceivedMinor, long cashRefundedMinor,
                                 long shiftDifferenceMinor, long promotionSnapshotCount, BigDecimal onHandDelta,
                                 BigDecimal availableDelta, BigDecimal reservedDelta, BigDecimal ledgerQuantityDelta,
                                 BigDecimal purchaseQuantityDelta, BigDecimal stocktakeQuantityDelta,
                                 BigDecimal transferQuantityDelta, BigDecimal inventoryValueDeltaMinor,
                                 BigDecimal cogsDeltaMinor, BigDecimal purchaseCostDeltaMinor,
                                 BigDecimal stocktakeCostDeltaMinor, BigDecimal transferCostDeltaMinor,
                                 String correlationId) {}
}
