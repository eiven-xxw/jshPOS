package com.jingshanghui.pos.reporting.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Reporting Owner 对外只读视图。 */
public final class ReportingViews {
    private ReportingViews() {
    }

    /**
     * 来源事件应用结果。
     * @param sourceEventId 来源事件标识
     * @param applied 是否首次应用
     * @param projectionStatus 投影完整状态
     * @param contiguousSequence 最大连续序号
     * @param maximumSeenSequence 最大已见序号
     */
    public record SourceApplyView(String sourceEventId, boolean applied, String projectionStatus,
                                  long contiguousSequence, long maximumSeenSequence) {
    }

    /**
     * 销售收银日投影。
     * @param businessDate 业务日
     * @param orgId 组织标识
     * @param storeId 门店标识
     * @param terminalId 终端标识
     * @param cashierId 收银员标识
     * @param currency 币种
     * @param orderCount 成交订单数
     * @param cancelledOrderCount 取消订单数
     * @param returnCount 退货次数
     * @param grossMinor 原价金额
     * @param discountMinor 优惠金额
     * @param surchargeMinor 附加金额
     * @param receivableMinor 应收金额
     * @param refundMinor 退款金额
     * @param cashReceivedMinor 现金实收
     * @param cashRefundedMinor 现金退回
     * @param shiftDifferenceMinor 班次差异
     * @param promotionSnapshotCount 促销快照数
     * @param projectionStatus 投影完整状态
     */
    public record SalesDailyView(LocalDate businessDate, Long orgId, Long storeId, String terminalId,
                                 Long cashierId, String currency, long orderCount, long cancelledOrderCount,
                                 long returnCount, long grossMinor, long discountMinor, long surchargeMinor,
                                 long receivableMinor, long refundMinor, long cashReceivedMinor,
                                 long cashRefundedMinor, long shiftDifferenceMinor,
                                 long promotionSnapshotCount, String projectionStatus) {
    }

    /**
     * 库存成本日投影。
     * @param businessDate 业务日
     * @param orgId 组织标识
     * @param storeId 门店标识
     * @param warehouseId 仓库 ULID
     * @param skuId SKU 标识
     * @param currency 币种
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
     * @param projectionStatus 投影完整状态
     */
    public record InventoryCostDailyView(LocalDate businessDate, Long orgId, Long storeId, String warehouseId,
                                         Long skuId, String currency, BigDecimal onHandDelta,
                                         BigDecimal availableDelta, BigDecimal reservedDelta,
                                         BigDecimal ledgerQuantityDelta, BigDecimal purchaseQuantityDelta,
                                         BigDecimal stocktakeQuantityDelta, BigDecimal transferQuantityDelta,
                                         BigDecimal inventoryValueDeltaMinor, BigDecimal cogsDeltaMinor,
                                         BigDecimal purchaseCostDeltaMinor, BigDecimal stocktakeCostDeltaMinor,
                                         BigDecimal transferCostDeltaMinor, String projectionStatus) {
    }

    /**
     * 报表导出状态视图。
     * @param exportId 导出标识
     * @param reportType 报表类型
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeIds 门店范围
     * @param fields 字段范围
     * @param state 状态
     * @param approvalRequired 是否要求独立审批
     * @param requestedBy 申请人
     * @param approvedBy 审批人
     * @param estimatedRows 预计行数
     * @param artifactSha256 制品摘要
     * @param expiresAt 制品过期时间
     * @param version 乐观锁版本
     */
    public record ExportView(String exportId, String reportType, LocalDate fromDate, LocalDate toDate,
                             Set<Long> storeIds, Set<String> fields, String state, boolean approvalRequired,
                             Long requestedBy, Long approvedBy, int estimatedRows, String artifactSha256,
                             Instant expiresAt, int version) {
    }

    /**
     * 单次下载令牌。
     * @param exportId 导出标识
     * @param token 只返回一次的高熵明文令牌
     * @param expiresAt 令牌过期时刻
     */
    public record DownloadTokenView(String exportId, String token, Instant expiresAt) {
    }

    /**
     * 已授权下载的制品。
     * @param fileName 安全文件名
     * @param contentType 媒体类型
     * @param content 文件内容
     */
    public record DownloadArtifact(String fileName, String contentType, byte[] content) {
        public DownloadArtifact {
            content = content == null ? new byte[0] : content.clone();
        }
        @Override public byte[] content() { return content.clone(); }
    }

    /**
     * 重建结果。
     * @param rebuildId 重建标识
     * @param projectionVersion 新投影版本
     * @param sourceEventCount 重放事件数
     * @param projectionDigest 投影摘要
     * @param state 重建状态
     */
    public record RebuildView(String rebuildId, String projectionVersion, long sourceEventCount,
                              String projectionDigest, String state) {
    }

    /**
     * Reporting 差异视图。
     * @param differenceId 差异标识
     * @param differenceType 差异类型
     * @param sourceEventId 来源事件标识
     * @param state 处理状态
     * @param detailSha256 去敏详情摘要
     * @param assignedTo 处理人
     * @param detectedAt 检出时刻
     * @param version 乐观锁版本
     */
    public record DifferenceView(String differenceId, String differenceType, String sourceEventId,
                                 String state, String detailSha256, Long assignedTo,
                                 Instant detectedAt, int version) {
    }
}
