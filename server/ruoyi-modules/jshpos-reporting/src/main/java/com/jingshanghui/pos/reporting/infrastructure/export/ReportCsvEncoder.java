package com.jingshanghui.pos.reporting.infrastructure.export;

import com.jingshanghui.pos.reporting.application.model.ReportingViews.InventoryCostDailyView;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.SalesDailyView;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import com.jingshanghui.pos.reporting.domain.ReportRules;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** 确定性 CSV 编码器，执行字段白名单、公式注入防护、换行归一化和水印。 */
public final class ReportCsvEncoder {
    public byte[] sales(String tenantId, String exportId, Set<String> requestedFields,
                        List<SalesDailyView> rows, Instant generatedAt) {
        List<String> fields = requestedFields.stream().sorted().toList();
        return encode(tenantId, exportId, fields, rows, generatedAt, field -> salesValue(field));
    }

    public byte[] inventoryCost(String tenantId, String exportId, Set<String> requestedFields,
                                List<InventoryCostDailyView> rows, Instant generatedAt) {
        List<String> fields = requestedFields.stream().sorted().toList();
        return encode(tenantId, exportId, fields, rows, generatedAt, field -> inventoryValue(field));
    }

    public byte[] paymentReconciliation(String tenantId, String exportId, Set<String> requestedFields,
                                        List<ReconciliationView> rows, Instant generatedAt) {
        List<String> fields = requestedFields.stream().sorted().toList();
        return encode(tenantId, exportId, fields, rows, generatedAt, this::paymentValue);
    }

    private <T> byte[] encode(String tenantId, String exportId, List<String> fields, List<T> rows,
                              Instant generatedAt, Function<String, Function<T, Object>> extractorFactory) {
        StringBuilder csv = new StringBuilder(256 + rows.size() * 128);
        csv.append("# tenant=").append(csvCell(tenantId)).append(",export=").append(csvCell(exportId))
            .append(",generatedAt=").append(csvCell(generatedAt)).append("\r\n");
        csv.append(String.join(",", fields.stream().map(this::csvCell).toList())).append("\r\n");
        List<Function<T, Object>> extractors = fields.stream().map(extractorFactory).toList();
        for (T row : rows) {
            List<String> values = new ArrayList<>(fields.size());
            for (Function<T, Object> extractor : extractors) {
                values.add(csvCell(extractor.apply(row)));
            }
            csv.append(String.join(",", values)).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Function<SalesDailyView, Object> salesValue(String field) {
        return switch (field) {
            case "businessDate" -> SalesDailyView::businessDate; case "storeId" -> SalesDailyView::storeId;
            case "terminalId" -> SalesDailyView::terminalId; case "cashierId" -> SalesDailyView::cashierId;
            case "currency" -> SalesDailyView::currency; case "orderCount" -> SalesDailyView::orderCount;
            case "cancelledOrderCount" -> SalesDailyView::cancelledOrderCount;
            case "returnCount" -> SalesDailyView::returnCount; case "grossMinor" -> SalesDailyView::grossMinor;
            case "discountMinor" -> SalesDailyView::discountMinor; case "surchargeMinor" -> SalesDailyView::surchargeMinor;
            case "receivableMinor" -> SalesDailyView::receivableMinor; case "refundMinor" -> SalesDailyView::refundMinor;
            case "cashReceivedMinor" -> SalesDailyView::cashReceivedMinor;
            case "cashRefundedMinor" -> SalesDailyView::cashRefundedMinor;
            case "shiftDifferenceMinor" -> SalesDailyView::shiftDifferenceMinor;
            case "promotionSnapshotCount" -> SalesDailyView::promotionSnapshotCount;
            case "projectionStatus" -> SalesDailyView::projectionStatus;
            default -> throw new IllegalArgumentException("RPT-CSV-001: 非法销售字段");
        };
    }

    private Function<InventoryCostDailyView, Object> inventoryValue(String field) {
        return switch (field) {
            case "businessDate" -> InventoryCostDailyView::businessDate;
            case "storeId" -> InventoryCostDailyView::storeId; case "warehouseId" -> InventoryCostDailyView::warehouseId;
            case "skuId" -> InventoryCostDailyView::skuId; case "currency" -> InventoryCostDailyView::currency;
            case "onHandDelta" -> InventoryCostDailyView::onHandDelta;
            case "availableDelta" -> InventoryCostDailyView::availableDelta;
            case "reservedDelta" -> InventoryCostDailyView::reservedDelta;
            case "ledgerQuantityDelta" -> InventoryCostDailyView::ledgerQuantityDelta;
            case "purchaseQuantityDelta" -> InventoryCostDailyView::purchaseQuantityDelta;
            case "stocktakeQuantityDelta" -> InventoryCostDailyView::stocktakeQuantityDelta;
            case "transferQuantityDelta" -> InventoryCostDailyView::transferQuantityDelta;
            case "inventoryValueDeltaMinor" -> InventoryCostDailyView::inventoryValueDeltaMinor;
            case "cogsDeltaMinor" -> InventoryCostDailyView::cogsDeltaMinor;
            case "purchaseCostDeltaMinor" -> InventoryCostDailyView::purchaseCostDeltaMinor;
            case "stocktakeCostDeltaMinor" -> InventoryCostDailyView::stocktakeCostDeltaMinor;
            case "transferCostDeltaMinor" -> InventoryCostDailyView::transferCostDeltaMinor;
            case "projectionStatus" -> InventoryCostDailyView::projectionStatus;
            default -> throw new IllegalArgumentException("RPT-CSV-002: 非法库存成本字段");
        };
    }

    private Function<ReconciliationView, Object> paymentValue(String field) {
        return switch (field) {
            case "reconciliationId" -> ReconciliationView::reconciliationId;
            case "factType" -> ReconciliationView::factType;
            case "businessDate" -> ReconciliationView::businessDate;
            case "storeId" -> ReconciliationView::storeId;
            case "terminalId" -> ReconciliationView::terminalId;
            case "currency" -> ReconciliationView::currency;
            case "internalAmountMinor" -> ReconciliationView::internalAmountMinor;
            case "billAmountMinor" -> ReconciliationView::billAmountMinor;
            case "internalStatus" -> ReconciliationView::internalStatus;
            case "billStatus" -> ReconciliationView::billStatus;
            case "internalBusinessDate" -> ReconciliationView::internalBusinessDate;
            case "billBusinessDate" -> ReconciliationView::billBusinessDate;
            case "differenceType" -> ReconciliationView::differenceType;
            case "handlingState" -> ReconciliationView::handlingState;
            case "handlerId" -> ReconciliationView::handlerId;
            default -> throw new IllegalArgumentException("RPT-CSV-003: 非法支付对账字段");
        };
    }

    private String csvCell(Object value) {
        String safe = ReportRules.safeCsvText(value);
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
