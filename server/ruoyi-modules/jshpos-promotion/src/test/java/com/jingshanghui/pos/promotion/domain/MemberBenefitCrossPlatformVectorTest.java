package com.jingshanghui.pos.promotion.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.MemberLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.AppliedAdjustment;
import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java/Dart 共用的 T2-MEM-003 金标向量。
 * 金额向量逐字段执行，状态型向量在此校验契约完整性并由对应 Owner 故障测试执行。
 */
class MemberBenefitCrossPlatformVectorTest {
    private static final String LINE_ID = "01K7V000000000000000000001";
    private static final String SNAPSHOT_ID = "01K7V000000000000000000002";
    private static final String VERSION_ID = "01K7V000000000000000000003";
    private final MemberBenefitCombinationEngine engine = new MemberBenefitCombinationEngine();

    @Test
    void executesSharedCalculationVectorsAndLocksAllFortyCases() throws Exception {
        JsonNode vectors = new ObjectMapper().readTree(Files.readString(sharedVectorPath())).path("vectors");
        Set<String> ids = new HashSet<>();
        int calculationCount = 0;
        for (JsonNode vector : vectors) {
            assertThat(ids.add(vector.path("id").asText())).as("duplicate vector id").isTrue();
            assertThat(vector.path("case").asText()).isNotBlank();
            if (!"CALCULATION".equals(vector.path("mode").asText())) {
                assertThat(vector.path("expectedOutcome").asText()).isNotBlank();
                continue;
            }
            calculationCount++;
            var quantity = new BigDecimal(vector.path("quantity").asText());
            long gross = vector.path("expected").path("grossAmountMinor").asLong();
            long normalDiscount = vector.path("normalDiscountMinor").asLong();
            BasketLine basket = new BasketLine(LINE_ID, 1, 101L, null, null, quantity,
                vector.path("unitPriceMinor").asLong());
            QuoteLine normalLine = new QuoteLine(LINE_ID, gross, normalDiscount, gross - normalDiscount);
            List<AppliedAdjustment> adjustments = normalDiscount == 0 ? List.of()
                : List.of(new AppliedAdjustment("NORMAL_PROMOTION", normalDiscount,
                    Map.of(LINE_ID, normalDiscount)));
            QuoteResult normal = new QuoteResult(gross, normalDiscount, gross - normalDiscount,
                List.of(normalLine), normalDiscount == 0 ? List.of() : List.of("NORMAL_PROMOTION"),
                List.of(), adjustments);
            List<MemberLine> memberLines = new ArrayList<>();
            if (vector.path("capabilityEnabled").asBoolean()) {
                var candidate = new MemberPriceCandidate(VERSION_ID, "01K7V000000000000000000004",
                    SNAPSHOT_ID, "GOLD", 101L, 201L, 1101L,
                    vector.path("memberPriceMinor").asLong(), "CNY", "a".repeat(64), Instant.EPOCH, null);
                memberLines.add(new MemberLine(basket, 201L, candidate));
            }
            var actual = engine.combine(normal, memberLines, SNAPSHOT_ID,
                vector.path("entitlementAllowsStacking").asBoolean(),
                vector.path("promotionAllowsStacking").asBoolean());
            JsonNode expected = vector.path("expected");
            assertThat(actual.path().name()).as(vector.path("id").asText())
                .isEqualTo(expected.path("selectedPath").asText());
            assertThat(actual.quote().grossAmountMinor()).isEqualTo(expected.path("grossAmountMinor").asLong());
            assertThat(actual.quote().discountAmountMinor()).isEqualTo(expected.path("discountAmountMinor").asLong());
            assertThat(actual.quote().payableAmountMinor()).isEqualTo(expected.path("payableAmountMinor").asLong());
        }
        assertThat(vectors).hasSize(40);
        assertThat(calculationCount).isGreaterThanOrEqualTo(10);
        for (int index = 1; index <= 40; index++) {
            assertThat(ids).contains("MBP-%03d".formatted(index));
        }
    }

    private Path sharedVectorPath() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("contracts/t2/gate7d-mem003/member-benefit-price-vectors.json");
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到 T2-MEM-003 Java/Dart 共用金标向量");
    }
}
