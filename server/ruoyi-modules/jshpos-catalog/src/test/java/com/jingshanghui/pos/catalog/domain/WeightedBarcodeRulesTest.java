package com.jingshanghui.pos.catalog.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules.ParsedMeasurement;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules.Template;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightedBarcodeRulesTest {

    private static final Instant AT = Instant.parse("2026-08-22T01:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test
    void parsesWeightBarcodeAndFreezesHalfEvenAmount() {
        String barcode = ean13("220012300250");
        ParsedMeasurement result = WeightedBarcodeRules.parse(template("WEIGHT", "22", 3), barcode,
            1990, 3, AT);

        assertThat(result.rawBarcode()).isEqualTo(barcode);
        assertThat(result.skuCode()).isEqualTo("00123");
        assertThat(result.encodedValue()).isEqualTo("00250");
        assertThat(result.quantity()).isEqualByComparingTo("0.250");
        assertThat(result.amountMinor()).isEqualTo(498);
        assertThat(result.roundingApplied()).isTrue();
        assertThat(result.parseSha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void parsesAmountBarcodeAndDerivesQuantityWithoutFloatingPoint() {
        String barcode = ean13("230012301234");
        ParsedMeasurement result = WeightedBarcodeRules.parse(template("AMOUNT", "23", 2), barcode,
            1990, 3, AT);

        assertThat(result.amountMinor()).isEqualTo(1234);
        assertThat(result.quantity()).isEqualByComparingTo("0.620");
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.roundingApplied()).isTrue();
    }

    @Test
    void rejectsBadChecksumPrecisionOverlapAndOverflow() {
        String valid = ean13("220012300251");
        String badChecksum = valid.substring(0, 12) + ((valid.charAt(12) - '0' + 1) % 10);
        assertBad(() -> WeightedBarcodeRules.parse(template("WEIGHT", "22", 3), badChecksum, 100, 3, AT),
            "CAT-WBC-010");
        assertBad(() -> WeightedBarcodeRules.parse(template("WEIGHT", "22", 4), valid, 100, 3, AT),
            "CAT-WBC-012");
        Template overlap = new Template(1L, "W", 1, "TENANT", null, "WEIGHT", "EAN13", "22", 13,
            2, 5, 7, 5, 3, 0, AT, null, HASH);
        assertBad(() -> WeightedBarcodeRules.requireTemplate(overlap), "CAT-WBC-005");
        String overflow = ean13("220012399999");
        assertBad(() -> WeightedBarcodeRules.parse(template("WEIGHT", "22", 3), overflow,
            9_007_199_254_740_991L, 3, AT),
            "CAT-WBC-015");
        assertBad(() -> WeightedBarcodeRules.parse(template("WEIGHT", "22", 3), overflow,
            Long.MAX_VALUE, 3, AT), "CAT-WBC-011");
    }

    @Test
    void checksumAndCanonicalDigestAreStable() {
        assertThat(WeightedBarcodeRules.checkDigit("220012300250")).isBetween(0, 9);
        assertThat(WeightedBarcodeRules.contentSha256(template("WEIGHT", "22", 3)))
            .matches("[a-f0-9]{64}")
            .isEqualTo(WeightedBarcodeRules.contentSha256(template("WEIGHT", "22", 3)));
        assertBad(() -> WeightedBarcodeRules.checkDigit("123"), "CAT-WBC-007");
    }

    @Test
    void rejectsEveryInvalidTemplateIdentityScopeShapeAndWindowBranch() {
        assertBad(() -> WeightedBarcodeRules.requireTemplate(null), "CAT-WBC-001");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(null, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-001");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(0L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-001");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-001");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 0, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-001");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "CODE128", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-002");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 12, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-002");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "2", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-003");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "PRICE",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-004");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", 1L, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-017");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "STORE", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-017");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 0, 5, 8, 5, 3, AT, null, HASH)), "CAT-WBC-014");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 9, 8, 5, 3, AT, null, HASH)), "CAT-WBC-014");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 12, 2, 3, AT, null, HASH)), "CAT-WBC-014");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 7, 5, 3, AT, null, HASH)), "CAT-WBC-005");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, -1, AT, null, HASH)), "CAT-WBC-006");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 7, AT, null, HASH)), "CAT-WBC-006");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, null, null, HASH)), "CAT-WBC-006");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(raw(1L, "W", 1, "TENANT", null, "WEIGHT",
            "EAN13", "22", 13, 3, 5, 8, 5, 3, AT, AT, HASH)), "CAT-WBC-006");
    }

    @Test
    void rejectsInvalidParseInputsAndCoversExactRoundingAndActivityWindows() {
        Template weight = template("WEIGHT", "22", 3);
        assertBad(() -> WeightedBarcodeRules.parse(weight, null, 100, 3, AT), "CAT-WBC-008");
        assertBad(() -> WeightedBarcodeRules.parse(weight, "abcdefghijklm", 100, 3, AT), "CAT-WBC-008");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("230012300250"), 100, 3, AT), "CAT-WBC-009");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("220012300250"), 0, 3, AT), "CAT-WBC-011");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("220012300250"), 100, -1, AT), "CAT-WBC-011");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("220012300250"), 100, 7, AT), "CAT-WBC-011");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("220012300250"), 100, 3, null), "CAT-WBC-011");
        assertBad(() -> WeightedBarcodeRules.parse(weight, ean13("220012300000"), 100, 3, AT), "CAT-WBC-013");
        Template noHash = raw(1L, "W", 1, "TENANT", null, "WEIGHT", "EAN13", "22", 13,
            3, 5, 8, 5, 3, Instant.parse("2026-01-01T00:00:00Z"), null, null);
        assertBad(() -> WeightedBarcodeRules.parse(noHash, ean13("220012300250"), 100, 3, AT), "CAT-WBC-016");

        ParsedMeasurement exactWeight = WeightedBarcodeRules.parse(weight, ean13("220012300500"), 2000, 3, AT);
        assertThat(exactWeight.amountMinor()).isEqualTo(1000);
        assertThat(exactWeight.roundingApplied()).isFalse();
        ParsedMeasurement exactAmount = WeightedBarcodeRules.parse(template("AMOUNT", "23", 2),
            ean13("230012301990"), 1990, 3, AT);
        assertThat(exactAmount.quantity().toPlainString()).isEqualTo("1");
        assertThat(exactAmount.roundingApplied()).isFalse();
        assertBad(() -> WeightedBarcodeRules.requireTemplate(template("AMOUNT", "23", 1)), "CAT-WBC-019");
        assertBad(() -> WeightedBarcodeRules.requireTemplate(template("AMOUNT", "23", 3)), "CAT-WBC-019");

        assertThat(weight.activeAt(Instant.parse("2025-12-31T23:59:59Z"))).isFalse();
        assertThat(weight.activeAt(AT)).isTrue();
        Template bounded = raw(1L, "W", 1, "TENANT", null, "WEIGHT", "EAN13", "22", 13,
            3, 5, 8, 5, 3, Instant.parse("2026-01-01T00:00:00Z"), AT, HASH);
        assertThat(bounded.activeAt(AT)).isFalse();
        assertThat(bounded.activeAt(null)).isFalse();
        assertBad(() -> WeightedBarcodeRules.parse(bounded, ean13("220012300250"), 100, 3, AT),
            "CAT-WBC-018");
    }

    @Test
    void matchesSharedJavaDartGoldenVectors() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(sharedVectorPath())).path("cases");
        for (JsonNode vector : cases) {
            Template template = new Template(vector.path("templateId").asLong(), vector.path("templateCode").asText(),
                vector.path("templateVersion").asInt(), "TENANT", null, vector.path("kind").asText(), "EAN13",
                vector.path("prefix").asText(), 13, 3, 5, 8, 5, vector.path("valueScale").asInt(), 10,
                Instant.parse("2026-01-01T00:00:00Z"), null, vector.path("templateSha256").asText());
            ParsedMeasurement actual = WeightedBarcodeRules.parse(template, vector.path("rawBarcode").asText(),
                vector.path("unitPriceMinor").asLong(), vector.path("unitDecimalScale").asInt(),
                Instant.parse(vector.path("occurredAt").asText()));
            JsonNode expected = vector.path("expected");
            assertThat(actual.skuCode()).isEqualTo(expected.path("skuCode").asText());
            assertThat(actual.quantity().toPlainString()).isEqualTo(expected.path("quantity").asText());
            assertThat(actual.amountMinor()).isEqualTo(expected.path("amountMinor").asLong());
            assertThat(actual.roundingApplied()).isEqualTo(expected.path("roundingApplied").asBoolean());
            assertThat(actual.parseSha256()).isEqualTo(expected.path("parseSha256").asText());
        }
    }

    private static Template template(String kind, String prefix, int scale) {
        return new Template(1L, "WBC", 1, "TENANT", null, kind, "EAN13", prefix, 13,
            3, 5, 8, 5, scale, 10, Instant.parse("2026-01-01T00:00:00Z"), null, HASH);
    }

    private static Template raw(Long id, String code, int version, String scope, Long storeId, String kind,
                                String symbology, String prefix, int totalLength, int skuStart, int skuLength,
                                int valueStart, int valueLength, int scale, Instant from, Instant to, String hash) {
        return new Template(id, code, version, scope, storeId, kind, symbology, prefix, totalLength,
            skuStart, skuLength, valueStart, valueLength, scale, 10, from, to, hash);
    }

    private static String ean13(String firstTwelve) {
        return firstTwelve + WeightedBarcodeRules.checkDigit(firstTwelve);
    }

    private Path sharedVectorPath() {
        Path cursor = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(
                "contracts/t2/gate7c-prd005/weighted-barcode-golden-vectors-v1.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("找不到 PRD-005 Java/Dart 共用金标");
    }

    private static void assertBad(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class).hasMessageContaining(code);
    }
}
