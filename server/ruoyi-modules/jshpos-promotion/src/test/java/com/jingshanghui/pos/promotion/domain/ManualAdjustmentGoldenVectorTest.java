package com.jingshanghui.pos.promotion.domain;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.*;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Java 读取 PRM-002 Java/Dart 共用黄金向量。 */
class ManualAdjustmentGoldenVectorTest {
    @Test
    void matchesSharedManualVectorsExactly() throws Exception {
        Path path = Path.of("..", "..", "..", "contracts", "t2", "gate5a", "test-vectors",
            "manual-adjustment-vectors-v1.json").normalize();
        Map<String, Object> root = new ObjectMapper().readValue(Files.readString(path), new TypeReference<>() { });
        Map<String, Object> rawPolicy = map(root.get("policy"));
        Policy policy = new Policy(number(rawPolicy, "policyVersionId"), (String) rawPolicy.get("policySha256"),
            number(rawPolicy, "withoutApprovalMinor"), number(rawPolicy, "withApprovalMinor"),
            number(rawPolicy, "minimumLinePayableMinor"), number(rawPolicy, "maximumRoundingMinor"),
            list(rawPolicy.get("roundingMultiplesMinor")).stream().map(value -> ((Number) value).longValue()).toList());
        List<Map<String, Object>> rawLines = list(root.get("lines")).stream().map(this::map).toList();
        List<PromotionModels.BasketLine> lines = rawLines.stream().map(value -> new PromotionModels.BasketLine(
            (String) value.get("lineId"), ((Number) value.get("lineNo")).intValue(),
            Long.valueOf((String) value.get("skuId")), null, null,
            new BigDecimal((String) value.get("quantity")), number(value, "unitPriceMinor"))).toList();
        List<LineContext> contexts = lines.stream().map(value -> new LineContext(value.lineId(), value.lineNo(),
            value.skuId(), value.quantity())).toList();
        Map<String, Object> base = map(root.get("base"));
        Map<String, Long> baseDiscounts = longMap(base.get("lineDiscounts"));
        List<QuoteLine> quoteLines = lines.stream().map(value -> {
            long gross = value.quantity().multiply(BigDecimal.valueOf(value.unitPriceMinor())).longValueExact();
            return new QuoteLine(value.lineId(), gross, baseDiscounts.get(value.lineId()),
                gross - baseDiscounts.get(value.lineId()));
        }).toList();
        QuoteResult quote = new QuoteResult(number(base, "grossAmountMinor"), number(base, "discountAmountMinor"),
            number(base, "payableAmountMinor"), quoteLines, List.of(), List.of(), List.of());
        ManualAdjustmentEngine engine = new ManualAdjustmentEngine();
        for (Object raw : list(root.get("scenarios"))) {
            Map<String, Object> scenario = map(raw);
            Map<String, Object> expected = map(scenario.get("expected"));
            Preview result = engine.preview(quote, contexts, new Command("01K5R000000000000000000050",
                ActionType.valueOf((String) scenario.get("actionType")), (String) scenario.get("lineId"),
                (String) scenario.get("amountOrRate"), PaymentMethod.valueOf((String) scenario.get("paymentMethod"))), policy);
            assertThat(result.incrementalDiscountMinor()).as((String) scenario.get("id"))
                .isEqualTo(number(expected, "incrementalDiscountMinor"));
            assertThat(result.requiresApproval()).isEqualTo(expected.get("requiresApproval"));
            assertThat(result.result().payableAmountMinor()).isEqualTo(number(expected, "payableAmountMinor"));
            assertThat(result.result().lineDiscounts()).containsExactlyEntriesOf(longMap(expected.get("lineDiscounts")));
        }
    }

    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private List<Object> list(Object value) { return (List<Object>) value; }
    private long number(Map<String, Object> value, String key) { return ((Number) value.get(key)).longValue(); }
    private Map<String, Long> longMap(Object value) {
        Map<String, Long> result = new LinkedHashMap<>();
        map(value).forEach((key, item) -> result.put(key, ((Number) item).longValue()));
        return result;
    }
}
