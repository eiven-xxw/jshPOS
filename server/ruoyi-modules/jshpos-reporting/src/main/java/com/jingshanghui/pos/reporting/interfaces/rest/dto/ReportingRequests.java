package com.jingshanghui.pos.reporting.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Gate 5D REST 请求模型；所有模型刻意不提供 tenantId。 */
public final class ReportingRequests {
    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256 = "^[a-f0-9]{64}$";
    private ReportingRequests() {}

    /**
     * @param sourceEventId 来源事件ULID @param sourceOwner 来源Owner @param sourceAggregateId 聚合标识
     * @param sourceSequence 来源序号 @param partitionKey 分区键 @param schemaVersion Schema版本
     * @param projectionVersion 投影引擎版本 @param contentSha256 内容摘要 @param occurredAt 发生时刻
     * @param businessDate 业务日 @param orgId 组织 @param storeId 门店 @param terminalId 终端
     * @param cashierId 收银员 @param warehouseId 仓库 @param skuId SKU @param currency 币种
     * @param metricFamily 指标族 @param sales 销售增量 @param inventoryCost 库存成本增量 @param correlationId 关联ULID
     */
    public record SourceEvent(@Pattern(regexp=ULID) String sourceEventId,
                              @Pattern(regexp="^[A-Z][A-Z0-9_]{0,23}$") String sourceOwner,
                              @NotBlank @Size(max=64) String sourceAggregateId,
                              @Positive long sourceSequence, @NotBlank @Size(max=96) String partitionKey,
                              @NotBlank @Size(max=16) String schemaVersion,
                              @NotBlank @Size(max=32) String projectionVersion,
                              @Pattern(regexp=SHA256) String contentSha256, @NotNull Instant occurredAt,
                              @NotNull LocalDate businessDate, @Positive Long orgId, @Positive Long storeId,
                              @Size(max=64) String terminalId, @Positive Long cashierId,
                              @Pattern(regexp=ULID) String warehouseId, @Positive Long skuId,
                              @NotBlank @Size(min=3,max=3) String currency,
                              @Pattern(regexp="^(SALES|INVENTORY_COST)$") String metricFamily,
                              @Valid SalesDelta sales, @Valid InventoryCostDelta inventoryCost,
                              @Pattern(regexp=ULID) String correlationId) {}

    /**
     * @param orderCount 订单数 @param cancelledOrderCount 取消数 @param returnCount 退货数
     * @param grossMinor 原价 @param discountMinor 优惠 @param surchargeMinor 附加 @param receivableMinor 应收
     * @param refundMinor 退款 @param cashReceivedMinor 现金实收 @param cashRefundedMinor 现金退回
     * @param shiftDifferenceMinor 班次差异 @param promotionSnapshotCount 促销快照数
     */
    public record SalesDelta(long orderCount, long cancelledOrderCount, long returnCount, long grossMinor,
                             long discountMinor, long surchargeMinor, long receivableMinor, long refundMinor,
                             long cashReceivedMinor, long cashRefundedMinor, long shiftDifferenceMinor,
                             long promotionSnapshotCount) {}

    /**
     * @param onHandDelta 在手 @param availableDelta 可用 @param reservedDelta 预占 @param ledgerQuantityDelta 流水
     * @param purchaseQuantityDelta 采购数量 @param stocktakeQuantityDelta 盘点数量 @param transferQuantityDelta 调拨数量
     * @param inventoryValueDeltaMinor 库存价值 @param cogsDeltaMinor COGS @param purchaseCostDeltaMinor 采购成本
     * @param stocktakeCostDeltaMinor 盘点成本 @param transferCostDeltaMinor 调拨成本
     */
    public record InventoryCostDelta(@NotNull BigDecimal onHandDelta, @NotNull BigDecimal availableDelta,
                                     @NotNull BigDecimal reservedDelta, @NotNull BigDecimal ledgerQuantityDelta,
                                     @NotNull BigDecimal purchaseQuantityDelta,
                                     @NotNull BigDecimal stocktakeQuantityDelta,
                                     @NotNull BigDecimal transferQuantityDelta,
                                     @NotNull BigDecimal inventoryValueDeltaMinor,
                                     @NotNull BigDecimal cogsDeltaMinor,
                                     @NotNull BigDecimal purchaseCostDeltaMinor,
                                     @NotNull BigDecimal stocktakeCostDeltaMinor,
                                     @NotNull BigDecimal transferCostDeltaMinor) {}

    /** @param rebuildId 重建ULID @param projectionVersion 新版本 @param fromDate 起始日 @param toDate 结束日 @param correlationId 关联ULID */
    public record Rebuild(@Pattern(regexp=ULID) String rebuildId, @NotBlank @Size(max=32) String projectionVersion,
                          @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                          @Pattern(regexp=ULID) String correlationId) {}
    /** @param exportId 导出ULID @param reportType 报表类型 @param fromDate 起始日 @param toDate 结束日 @param storeIds 门店集合 @param fields 字段集合 @param correlationId 关联ULID */
    public record Export(@Pattern(regexp=ULID) String exportId,
                         @Pattern(regexp="^(SALES_DAILY|INVENTORY_COST_DAILY)$") String reportType,
                         @NotNull LocalDate fromDate, @NotNull LocalDate toDate,
                         @NotEmpty @Size(max=50) List<@Positive Long> storeIds,
                         @NotEmpty @Size(max=32) List<@Pattern(regexp="^[a-z][A-Za-z0-9]{0,63}$") String> fields,
                         @Pattern(regexp=ULID) String correlationId) {}
    /** @param approved 是否批准 @param reason 审批原因 @param expectedVersion 期望版本 @param correlationId 关联ULID */
    public record Approval(boolean approved, @NotBlank @Size(max=256) String reason,
                           @PositiveOrZero int expectedVersion, @Pattern(regexp=ULID) String correlationId) {}
    /** @param expectedVersion 期望版本 @param correlationId 关联ULID */
    public record Generate(@PositiveOrZero int expectedVersion, @Pattern(regexp=ULID) String correlationId) {}
    /** @param toState 目标状态 @param reason 原因 @param expectedVersion 期望版本 @param correlationId 关联ULID */
    public record DifferenceState(@Pattern(regexp="^(OPEN|ACKNOWLEDGED|RESOLVED|IGNORED)$") String toState,
                                  @NotBlank @Size(max=256) String reason, @PositiveOrZero int expectedVersion,
                                  @Pattern(regexp=ULID) String correlationId) {}
}
