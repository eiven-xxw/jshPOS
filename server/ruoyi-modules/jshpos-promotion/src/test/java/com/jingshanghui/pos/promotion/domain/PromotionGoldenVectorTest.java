package com.jingshanghui.pos.promotion.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** 共享黄金向量是服务端与 Flutter 跨端一致性的唯一输入。 */
class PromotionGoldenVectorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromotionEngine engine = new PromotionEngine();

    @Test
    void shouldMatchEveryPrm1GoldenVectorExactly() throws Exception {
        Path path = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("contracts"))) path = path.getParent();
        path = path == null ? Path.of("missing") : path.resolve(Path.of("contracts", "t2", "gate5a",
            "test-vectors", "promotion-golden-vectors-v1.json"));
        assertThat(Files.isRegularFile(path)).isTrue();
        JsonNode root = objectMapper.readTree(path.toFile());
        assertThat(root.path("engineVersion").asText()).isEqualTo(PromotionEngine.ENGINE_VERSION);
        assertThat(root.path("scenarios")).hasSize(17);
        for (JsonNode scenario : root.path("scenarios")) {
            QuoteResult actual = engine.quote(toRequest(scenario));
            JsonNode expected = scenario.path("expected");
            assertThat(actual.grossAmountMinor()).as(scenario.path("id").asText())
                .isEqualTo(expected.path("grossAmountMinor").asLong());
            assertThat(actual.discountAmountMinor()).isEqualTo(expected.path("discountAmountMinor").asLong());
            assertThat(actual.payableAmountMinor()).isEqualTo(expected.path("payableAmountMinor").asLong());
            Map<String, Long> expectedLines = new LinkedHashMap<>();
            expected.path("lineDiscounts").fields().forEachRemaining(entry ->
                expectedLines.put(entry.getKey(), entry.getValue().asLong()));
            assertThat(actual.lineDiscounts()).containsExactlyEntriesOf(expectedLines);
            assertThat(actual.appliedRuleIds()).containsExactlyElementsOf(strings(expected.path("appliedRuleIds")));
            List<Explanation> expectedExplanations = new ArrayList<>();
            expected.path("explanations").forEach(node -> expectedExplanations.add(new Explanation(
                node.path("sourceId").asText(), node.path("code").asText())));
            assertThat(actual.explanations()).containsExactlyElementsOf(expectedExplanations);
            assertThat(actual.grossAmountMinor() - actual.discountAmountMinor())
                .isEqualTo(actual.payableAmountMinor());
        }
    }

    private QuoteRequest toRequest(JsonNode scenario) {
        List<BasketLine> lines = new ArrayList<>();
        scenario.path("lines").forEach(node -> lines.add(new BasketLine(node.path("lineId").asText(),
            node.path("lineNo").asInt(), node.path("skuId").asLong(), nullableLong(node, "categoryId"),
            nullableLong(node, "brandId"), new BigDecimal(node.path("quantity").asText()),
            node.path("unitPriceMinor").asLong())));
        List<RuleVersion> rules = new ArrayList<>();
        scenario.path("rules").forEach(node -> rules.add(new RuleVersion(node.path("ruleVersionId").asText(),
            RuleType.valueOf(node.path("ruleType").asText()), node.path("priority").asInt(),
            StackMode.valueOf(node.path("stackMode").asText()), nullableText(node, "exclusiveGroup"),
            OffsetDateTime.parse(node.path("effectiveFrom").asText()),
            nullableDateTime(node, "effectiveTo"), scope(node.path("scope")), benefit(node.path("benefit")))));
        return new QuoteRequest(OffsetDateTime.parse(scenario.path("businessTime").asText()),
            scenario.path("storeId").asLong(), scenario.path("channel").asText(), lines, rules);
    }

    private RuleScope scope(JsonNode node) {
        return new RuleScope(longs(node.path("skuIds")), longs(node.path("categoryIds")),
            longs(node.path("brandIds")), longs(node.path("storeIds")),
            Set.copyOf(strings(node.path("channels"))), integers(node.path("businessDays")));
    }

    private RuleBenefit benefit(JsonNode node) {
        List<BundleComponent> components = new ArrayList<>();
        node.path("bundleComponents").forEach(value -> components.add(new BundleComponent(
            value.path("skuId").asLong(), new BigDecimal(value.path("quantity").asText()))));
        return new RuleBenefit(nullableLong(node, "amountMinor"), decimal(node, "discountRate"),
            node.hasNonNull("nth") ? node.path("nth").asInt() : null, nullableLong(node, "thresholdMinor"),
            decimal(node, "thresholdQuantity"), nullableLong(node, "bundlePriceMinor"), components);
    }

    private Set<Long> longs(JsonNode node) {
        Set<Long> values = new LinkedHashSet<>();
        node.forEach(value -> values.add(value.asLong()));
        return values;
    }

    private Set<Integer> integers(JsonNode node) {
        Set<Integer> values = new LinkedHashSet<>();
        node.forEach(value -> values.add(value.asInt()));
        return values;
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asText()));
        return values;
    }

    private Long nullableLong(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asLong() : null;
    }

    private String nullableText(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        return node.hasNonNull(field) ? new BigDecimal(node.path(field).asText()) : null;
    }

    private OffsetDateTime nullableDateTime(JsonNode node, String field) {
        return node.hasNonNull(field) ? OffsetDateTime.parse(node.path(field).asText()) : null;
    }
}
