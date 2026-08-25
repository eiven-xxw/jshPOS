package com.jingshanghui.pos.order.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gate 5B 服务端消费含促销快照的订单命令。 */
public final class PromotedOrderCommands {

    private PromotedOrderCommands() {
    }

    /**
     * 含促销快照的现金订单。
     *
     * @param commandId 命令ULID
     * @param idempotencyKey 终端稳定幂等键
     * @param orderId 订单ULID
     * @param localOrderNo 终端本地订单号
     * @param storeId 可信门店
     * @param terminalId 可信终端ULID
     * @param shiftId 班次ULID
     * @param cashierId 收银员ID字符串表示
     * @param businessDate 门店业务日
     * @param storeTimezone 门店IANA时区
     * @param catalogVersion 商品版本
     * @param priceVersion 价格版本
     * @param industryTemplateVersion 行业模板版本
     * @param promotionSnapshotId 促销成交快照ULID
     * @param promotionSnapshotSha256 促销成交快照规范摘要
     * @param quoteFingerprint 原促销报价摘要
     * @param settlementFingerprint 人工优惠后最终摘要
     * @param promotionPackageVersion 规则包版本
     * @param orderSnapshotSha256 POS订单快照规范摘要
     * @param manualEventRefs 已冻结人工优惠审计引用
     * @param grossAmountMinor 原金额分
     * @param discountAmountMinor 优惠分
     * @param surchargeAmountMinor 附加费分
     * @param receivableAmountMinor 应收分
     * @param tenderedAmountMinor 实收分
     * @param lines 成交行
     * @param printJobId POS 本地事务冻结的原始打印任务ULID；旧事件可为空并由服务端兼容分配
     * @param occurredAt POS成交时间UTC
     */
    public record SubmitPromotedCashOrder(String commandId, String idempotencyKey, String orderId,
                                           String localOrderNo, Long storeId, String terminalId, String shiftId,
                                           String cashierId, LocalDate businessDate, String storeTimezone,
                                           long catalogVersion, long priceVersion, String industryTemplateVersion,
                                           String promotionSnapshotId, String promotionSnapshotSha256,
                                           String quoteFingerprint, String settlementFingerprint,
                                           long promotionPackageVersion, String orderSnapshotSha256,
                                           List<String> manualEventRefs, long grossAmountMinor,
                                           long discountAmountMinor, long surchargeAmountMinor,
                                           long receivableAmountMinor, long tenderedAmountMinor,
                                           List<PromotedLine> lines, String printJobId, Instant occurredAt) {
        /** 兼容既有服务端/测试调用；同步新事件必须显式携带本地冻结的打印任务身份。 */
        public SubmitPromotedCashOrder(String commandId, String idempotencyKey, String orderId,
                                       String localOrderNo, Long storeId, String terminalId, String shiftId,
                                       String cashierId, LocalDate businessDate, String storeTimezone,
                                       long catalogVersion, long priceVersion, String industryTemplateVersion,
                                       String promotionSnapshotId, String promotionSnapshotSha256,
                                       String quoteFingerprint, String settlementFingerprint,
                                       long promotionPackageVersion, String orderSnapshotSha256,
                                       List<String> manualEventRefs, long grossAmountMinor,
                                       long discountAmountMinor, long surchargeAmountMinor,
                                       long receivableAmountMinor, long tenderedAmountMinor,
                                       List<PromotedLine> lines, Instant occurredAt) {
            this(commandId, idempotencyKey, orderId, localOrderNo, storeId, terminalId, shiftId,
                cashierId, businessDate, storeTimezone, catalogVersion, priceVersion,
                industryTemplateVersion, promotionSnapshotId, promotionSnapshotSha256,
                quoteFingerprint, settlementFingerprint, promotionPackageVersion, orderSnapshotSha256,
                manualEventRefs, grossAmountMinor, discountAmountMinor, surchargeAmountMinor,
                receivableAmountMinor, tenderedAmountMinor, lines, null, occurredAt);
        }

        public SubmitPromotedCashOrder {
            manualEventRefs = List.copyOf(manualEventRefs);
            lines = List.copyOf(lines);
        }
    }

    /**
     * 含促销分摊的订单行。
     *
     * @param lineId 成交行ULID
     * @param lineNo 稳定行号
     * @param skuId SKU平台ID
     * @param skuCode SKU编码快照
     * @param barcode 条码快照
     * @param productName 商品名称快照
     * @param unitId 单位平台ID
     * @param unitCode 单位编码快照
     * @param quantity 精确数量字符串
     * @param unitPriceMinor 单价分
     * @param grossAmountMinor 行原金额分
     * @param discountAmountMinor 行优惠分
     * @param surchargeAmountMinor 行附加费分
     * @param payableAmountMinor 行应收分
     * @param priceSource 价格来源
     * @param sourceAllocations 优惠来源到金额分的冻结映射
     */
    public record PromotedLine(String lineId, int lineNo, Long skuId, String skuCode, String barcode,
                               String productName, Long unitId, String unitCode, String quantity,
                               long unitPriceMinor, long grossAmountMinor, long discountAmountMinor,
                               long surchargeAmountMinor, long payableAmountMinor, String priceSource,
                               Map<String, Long> sourceAllocations,
                               MeasuredBarcodeSnapshot measuredBarcodeSnapshot) {
        /** 兼容未携带计量快照的既有标准商品调用方。 */
        public PromotedLine(String lineId, int lineNo, Long skuId, String skuCode, String barcode,
                            String productName, Long unitId, String unitCode, String quantity,
                            long unitPriceMinor, long grossAmountMinor, long discountAmountMinor,
                            long surchargeAmountMinor, long payableAmountMinor, String priceSource,
                            Map<String, Long> sourceAllocations) {
            this(lineId, lineNo, skuId, skuCode, barcode, productName, unitId, unitCode, quantity,
                unitPriceMinor, grossAmountMinor, discountAmountMinor, surchargeAmountMinor,
                payableAmountMinor, priceSource, sourceAllocations, null);
        }

        public PromotedLine {
            sourceAllocations = Map.copyOf(sourceAllocations);
        }
    }

    /** 成交行冻结的秤码/金额码事实，与 Flutter 字段和摘要语义保持一致。 */
    public record MeasuredBarcodeSnapshot(String rawBarcode, String skuCode, String encodedValue,
                                          String quantity, long amountMinor, long unitPriceMinor,
                                          String currency, String templateId, int templateVersion,
                                          String templateSha256, String parseSha256,
                                          boolean roundingApplied, Instant occurredAt) {
        public Map<String, Object> toCanonicalMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("rawBarcode", rawBarcode); value.put("skuCode", skuCode);
            value.put("encodedValue", encodedValue); value.put("quantity", quantity);
            value.put("amountMinor", amountMinor); value.put("unitPriceMinor", unitPriceMinor);
            value.put("currency", currency); value.put("templateId", templateId);
            value.put("templateVersion", templateVersion); value.put("templateSha256", templateSha256);
            value.put("parseSha256", parseSha256); value.put("roundingApplied", roundingApplied);
            value.put("occurredAt", occurredAt == null ? null : occurredAt.toString());
            return value;
        }
    }
}
