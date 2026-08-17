package com.jingshanghui.pos.promotion.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Java 读取 PRM-003 Java/Dart 共用成交与退款黄金向量。 */
class TransactionAllocationGoldenVectorTest {
    @Test
    void matchesEverySharedRefundVectorExactly() throws Exception {
        Path path = Path.of("..", "..", "..", "contracts", "t2", "gate5a", "test-vectors",
            "transaction-allocation-vectors-v1.json").normalize();
        Map<String, Object> root = new ObjectMapper().readValue(Files.readString(path), new TypeReference<>() { });
        Map<String, Object> rawSnapshot = map(root.get("snapshot"));
        TransactionAllocationEngine engine = new TransactionAllocationEngine();
        Snapshot snapshot = engine.freeze(list(rawSnapshot.get("lines")).stream().map(this::map).map(value ->
            new SnapshotLine((String) value.get("lineId"), ((Number) value.get("lineNo")).intValue(),
                Long.valueOf((String) value.get("skuId")), new BigDecimal((String) value.get("quantity")),
                number(value, "grossAmountMinor"), number(value, "discountAmountMinor"),
                number(value, "payableAmountMinor"))).toList());
        assertThat(snapshot.grossAmountMinor()).isEqualTo(number(rawSnapshot, "grossAmountMinor"));
        Map<String, PriorRefund> history = new LinkedHashMap<>();
        for (Object raw : list(root.get("refunds"))) {
            Map<String, Object> scenario = map(raw); Map<String, Object> expected = map(scenario.get("expected"));
            List<RefundRequestLine> requests = list(scenario.get("lines")).stream().map(this::map).map(value ->
                new RefundRequestLine((String) value.get("lineId"), new BigDecimal((String) value.get("quantity"))))
                .toList();
            RefundResult result = engine.refund(snapshot, new ArrayList<>(history.values()), requests);
            assertThat(result.grossAmountMinor()).as((String) scenario.get("id"))
                .isEqualTo(number(expected, "grossAmountMinor"));
            assertThat(result.recoveredDiscountMinor()).isEqualTo(number(expected, "recoveredDiscountMinor"));
            assertThat(result.refundableAmountMinor()).isEqualTo(number(expected, "refundableAmountMinor"));
            result.lines().forEach(value -> history.put(value.lineId(), new PriorRefund(value.lineId(),
                value.cumulativeQuantity(), value.cumulativeGrossAmountMinor(),
                value.cumulativeDiscountAmountMinor(), value.cumulativePayableAmountMinor())));
        }
    }

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private List<Object> list(Object value) { return (List<Object>) value; }
    private long number(Map<String, Object> value, String key) { return ((Number) value.get(key)).longValue(); }
}
