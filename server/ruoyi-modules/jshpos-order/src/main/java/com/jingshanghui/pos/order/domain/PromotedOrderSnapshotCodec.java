package com.jingshanghui.pos.order.domain;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.PromotedLine;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ORD-003 与 Flutter 共用字段、排序和摘要语义的订单快照编码器。 */
public final class PromotedOrderSnapshotCodec {

    public static final int MAX_BYTES = 1024 * 1024;

    private PromotedOrderSnapshotCodec() {
    }

    /**
     * 只编码已冻结事实，不执行任何促销算法。
     *
     * @param command 已验证的含促销订单命令
     * @param trustedCashierId 服务端可信收银员ID
     * @return 规范化JSON与摘要
     */
    public static CanonicalJson.Result encode(SubmitPromotedCashOrder command, Long trustedCashierId) {
        List<Map<String, Object>> lines = new ArrayList<>();
        command.lines().stream().sorted(Comparator.comparingInt(PromotedLine::lineNo)).forEach(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lineId", line.lineId()); item.put("lineNo", line.lineNo());
            item.put("skuId", line.skuId().toString()); item.put("skuCode", line.skuCode());
            if (line.barcode() != null) item.put("barcode", line.barcode());
            item.put("productName", line.productName()); item.put("unitId", line.unitId().toString());
            item.put("unitCode", line.unitCode());
            item.put("quantity", OrderRules.requireQuantity(line.quantity()).toPlainString());
            item.put("unitPriceMinor", line.unitPriceMinor()); item.put("grossAmountMinor", line.grossAmountMinor());
            item.put("discountAmountMinor", line.discountAmountMinor());
            item.put("surchargeAmountMinor", line.surchargeAmountMinor());
            item.put("payableAmountMinor", line.payableAmountMinor()); item.put("priceSource", line.priceSource());
            item.put("sourceAllocations", line.sourceAllocations());
            if (line.measuredBarcodeSnapshot() != null) {
                item.put("measuredBarcodeSnapshot", line.measuredBarcodeSnapshot().toCanonicalMap());
            }
            lines.add(item);
        });
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schemaVersion", 2); value.put("orderId", command.orderId());
        value.put("storeId", command.storeId().toString()); value.put("terminalId", command.terminalId());
        value.put("shiftId", command.shiftId()); value.put("cashierId", trustedCashierId.toString());
        value.put("businessDate", command.businessDate().toString()); value.put("storeTimezone", command.storeTimezone());
        value.put("currency", "CNY"); value.put("grossAmountMinor", command.grossAmountMinor());
        value.put("discountAmountMinor", command.discountAmountMinor());
        value.put("surchargeAmountMinor", command.surchargeAmountMinor());
        value.put("receivableAmountMinor", command.receivableAmountMinor());
        value.put("catalogVersion", command.catalogVersion()); value.put("priceVersion", command.priceVersion());
        value.put("industryTemplateVersion", command.industryTemplateVersion());
        value.put("promotionSnapshotId", command.promotionSnapshotId());
        value.put("promotionSnapshotHash", "sha256:" + command.promotionSnapshotSha256());
        value.put("quoteFingerprint", command.quoteFingerprint());
        value.put("settlementFingerprint", command.settlementFingerprint());
        value.put("promotionPackageVersion", command.promotionPackageVersion());
        value.put("manualEventRefs", command.manualEventRefs()); value.put("lines", lines);
        return CanonicalJson.from(value, MAX_BYTES);
    }
}
