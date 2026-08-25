package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.PromotedLine;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.MeasuredBarcodeSnapshot;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;
import com.jingshanghui.pos.order.application.port.PromotedOrderSubmissionPort;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort.SnapshotCommand;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort.SnapshotLine;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将通过 Inbox 校验的 order.submitted.v2 映射为 Order Owner 命令。
 * tenant/store/terminal/cashier 以可信设备上下文为准，载荷只能用于一致性核验。
 */
@Component
public class PromotedOrderEventDispatcher {

    private final PromotedOrderSubmissionPort orders;
    private final PromotionSnapshotIngestionPort promotionSnapshots;

    public PromotedOrderEventDispatcher(PromotedOrderSubmissionPort orders,
                                        PromotionSnapshotIngestionPort promotionSnapshots) {
        this.orders = orders;
        this.promotionSnapshots = promotionSnapshots;
    }

    public void apply(DeviceContext context, EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        String storeId = text(payload, "storeId");
        String terminalId = text(payload, "terminalId");
        String cashierId = text(payload, "cashierId");
        if (!context.storeId().toString().equals(storeId) || !context.terminalId().equals(terminalId)
            || !context.userId().toString().equals(cashierId) || !event.aggregateId().equals(text(payload, "orderId"))) {
            throw new ServiceException("SYNC_CONTEXT_MISMATCH: 订单载荷试图覆盖可信设备上下文", 403);
        }
        List<PromotedLine> lines = array(payload, "lines").stream().map(this::line).toList();
        List<String> manualRefs = array(payload, "manualEventRefs").stream().map(value -> String.valueOf(value)).toList();
        promotionSnapshots.ingest(new SnapshotCommand(event.eventId(), event.correlationId(),
            text(payload, "quoteId"), text(payload, "promotionSnapshotId"), text(payload, "orderId"),
            context.storeId(), context.terminalId(), LocalDate.parse(text(payload, "businessDate")),
            number(payload, "packageVersion"), text(payload, "promotionEngineVersion"),
            text(payload, "quoteFingerprint"), text(payload, "settlementFingerprint"),
            hash(payload, "promotionSnapshotHash"),
            number(payload, "grossAmountMinor"), number(payload, "discountAmountMinor"),
            number(payload, "grossAmountMinor") - number(payload, "discountAmountMinor"),
            event.occurredAt(), array(payload, "lines").stream().map(this::snapshotLine).toList()));
        orders.submit(new SubmitPromotedCashOrder(event.correlationId(), event.idempotencyKey(),
            text(payload, "orderId"), text(payload, "localOrderNo"), context.storeId(), context.terminalId(),
            text(payload, "shiftId"), context.userId().toString(), LocalDate.parse(text(payload, "businessDate")),
            text(payload, "storeTimezone"), number(payload, "catalogVersion"), number(payload, "priceVersion"),
            text(payload, "industryTemplateVersion"), text(payload, "promotionSnapshotId"),
            hash(payload, "promotionSnapshotHash"), text(payload, "quoteFingerprint"),
            text(payload, "settlementFingerprint"), number(payload, "packageVersion"),
            hash(payload, "orderSnapshotHash"), manualRefs, number(payload, "grossAmountMinor"),
            number(payload, "discountAmountMinor"), number(payload, "surchargeAmountMinor"),
            number(payload, "receivableAmountMinor"), number(payload, "tenderedAmountMinor"), lines,
            optionalStrictText(payload, "printJobId"), event.occurredAt()));
    }

    private SnapshotLine snapshotLine(Object source) {
        if (!(source instanceof Map<?, ?> raw)) {
            throw invalid("lines must contain objects");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, item) -> value.put(String.valueOf(key), item));
        Map<String, Long> allocations = new LinkedHashMap<>();
        Object sources = value.get("sourceAllocations");
        if (!(sources instanceof Map<?, ?> sourceMap)) {
            throw invalid("sourceAllocations must be an object");
        }
        sourceMap.forEach((key, amount) -> allocations.put(String.valueOf(key),
            asLong(amount, "sourceAllocations")));
        try {
            return new SnapshotLine(text(value, "lineId"), Math.toIntExact(number(value, "lineNo")),
                number(value, "skuId"), new java.math.BigDecimal(text(value, "quantity")),
                number(value, "unitPriceMinor"), number(value, "grossAmountMinor"),
                number(value, "discountAmountMinor"),
                number(value, "grossAmountMinor") - number(value, "discountAmountMinor"), allocations);
        } catch (NumberFormatException exception) {
            throw invalid("quantity must be an exact decimal");
        }
    }

    private PromotedLine line(Object source) {
        if (!(source instanceof Map<?, ?> raw)) {
            throw invalid("lines must contain objects");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, item) -> value.put(String.valueOf(key), item));
        Map<String, Long> allocations = new LinkedHashMap<>();
        Object sources = value.get("sourceAllocations");
        if (!(sources instanceof Map<?, ?> sourceMap)) {
            throw invalid("sourceAllocations must be an object");
        }
        sourceMap.forEach((key, amount) -> allocations.put(String.valueOf(key), asLong(amount, "sourceAllocations")));
        return new PromotedLine(text(value, "lineId"), Math.toIntExact(number(value, "lineNo")),
            number(value, "skuId"), text(value, "skuCode"), optionalText(value.get("barcode")),
            text(value, "productName"), number(value, "unitId"), text(value, "unitCode"),
            text(value, "quantity"), number(value, "unitPriceMinor"), number(value, "grossAmountMinor"),
            number(value, "discountAmountMinor"), number(value, "surchargeAmountMinor"),
            number(value, "payableAmountMinor"), text(value, "priceSource"), allocations,
            measurement(value.get("measuredBarcodeSnapshot")));
    }

    private MeasuredBarcodeSnapshot measurement(Object source) {
        if (source == null) {
            return null;
        }
        if (!(source instanceof Map<?, ?> raw)) {
            throw invalid("measuredBarcodeSnapshot must be an object");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        raw.forEach((key, item) -> value.put(String.valueOf(key), item));
        Object rounding = value.get("roundingApplied");
        if (!(rounding instanceof Boolean roundingApplied)) {
            throw invalid("measuredBarcodeSnapshot.roundingApplied must be boolean");
        }
        try {
            return new MeasuredBarcodeSnapshot(text(value, "rawBarcode"), text(value, "skuCode"),
                text(value, "encodedValue"), text(value, "quantity"), number(value, "amountMinor"),
                number(value, "unitPriceMinor"), text(value, "currency"), text(value, "templateId"),
                Math.toIntExact(number(value, "templateVersion")), text(value, "templateSha256"),
                text(value, "parseSha256"), roundingApplied,
                java.time.Instant.parse(text(value, "occurredAt")));
        } catch (java.time.format.DateTimeParseException | ArithmeticException exception) {
            throw invalid("measuredBarcodeSnapshot contains an invalid version or timestamp");
        }
    }

    private String hash(Map<String, Object> value, String field) {
        String result = text(value, field);
        if (!result.startsWith("sha256:") || result.length() != 71) {
            throw invalid(field + " must use sha256 prefix");
        }
        return result.substring(7);
    }

    private String text(Map<String, Object> value, String field) {
        Object source = value.get(field);
        if (!(source instanceof String result) || result.isBlank()) {
            throw invalid(field + " must be a non-blank string");
        }
        return result;
    }

    private String optionalText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 可选契约字段仍必须保持字符串类型，禁止数字或对象被 String.valueOf 静默接受。 */
    private String optionalStrictText(Map<String, Object> value, String field) {
        return value.get(field) == null ? null : text(value, field);
    }

    private long number(Map<String, Object> value, String field) {
        return asLong(value.get(field), field);
    }

    private long asLong(Object value, String field) {
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw invalid(field + " must be an integer");
        }
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(field + " is outside integer range");
        }
    }

    private List<Object> array(Map<String, Object> value, String field) {
        Object source = value.get(field);
        if (!(source instanceof List<?> list)) {
            throw invalid(field + " must be an array");
        }
        return new ArrayList<>(list);
    }

    private ServiceException invalid(String message) {
        return new ServiceException("SYNC_PAYLOAD_INVALID: " + message, 400);
    }
}
