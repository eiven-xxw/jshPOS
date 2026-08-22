package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShelfLabelRulesTest {

    @Test
    void validatesAndRendersOnlyApprovedPlainTextFields() {
        String template = "商品 {{productName}}\n条码 {{barcode}}\n新价 ¥{{newPrice}}\n门店 {{storeName}}";
        String rendered = ShelfLabelRules.render(template, Map.of(
            "productName", "合成牛奶\u0001", "barcode", "0012345678905",
            "newPrice", "9.90", "storeName", "虚构便利店"));

        assertThat(rendered).isEqualTo("商品 合成牛奶 \n条码 0012345678905\n新价 ¥9.90\n门店 虚构便利店");
        assertThat(ShelfLabelRules.requireSafeTemplate(template)).isEqualTo(template);
        assertThat(ShelfLabelRules.sha256("label")).matches("[a-f0-9]{64}");
    }

    @Test
    void rejectsScriptPathFormulaUnknownAndMalformedTemplates() {
        for (String value : new String[]{
            "<script>alert(1)</script>{{productName}}", "../etc/passwd {{productName}}",
            "=HYPERLINK(A1) {{productName}}", "{{unknown}}", "{{productName", "plain text", "\u0001{{productName}}"
        }) {
            assertThatThrownBy(() -> ShelfLabelRules.requireSafeTemplate(value)).isInstanceOf(ServiceException.class);
        }
        assertThatThrownBy(() -> ShelfLabelRules.requireSafeTemplate("x".repeat(2001) + "{{productName}}"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ShelfLabelRules.requireSafeTemplate(" ")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ShelfLabelRules.render("{{productName}}".repeat(130),
            Map.of("productName", "x".repeat(512))))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesNamedItemStateMachine() {
        ShelfLabelRules.requireItemTransition("PENDING", "PREVIEW_READY");
        ShelfLabelRules.requireItemTransition("PENDING", "EXCEPTION");
        ShelfLabelRules.requireItemTransition("PREVIEW_READY", "REPLACED_CONFIRMED");
        ShelfLabelRules.requireItemTransition("EXCEPTION", "PREVIEW_READY");
        ShelfLabelRules.requireItemTransition("EXCEPTION", "SUPERSEDED");
        assertThatThrownBy(() -> ShelfLabelRules.requireItemTransition("PENDING", "REPLACED_CONFIRMED"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ShelfLabelRules.requireItemTransition("REPLACED_CONFIRMED", "PENDING"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void convergesLateAndOutOfOrderEventsDeterministically() {
        Instant at = Instant.parse("2026-08-22T00:00:00Z");
        assertThat(ShelfLabelRules.isNewer(at.plusSeconds(1), 1, 1, 1, at, 2, 9, 9)).isTrue();
        assertThat(ShelfLabelRules.isNewer(at, 2, 1, 1, at, 1, 9, 9)).isTrue();
        assertThat(ShelfLabelRules.isNewer(at, 2, 3, 1, at, 2, 2, 9)).isTrue();
        assertThat(ShelfLabelRules.isNewer(at, 2, 3, 10, at, 2, 3, 9)).isTrue();
        assertThat(ShelfLabelRules.isNewer(at.minusSeconds(1), 2, 99, 99, at, 1, 1, 1)).isFalse();
        assertThat(ShelfLabelRules.isNewer(at, 1, 99, 99, at, 2, 1, 1)).isFalse();
        assertThat(ShelfLabelRules.isNewer(at, 2, 2, 9, at, 2, 3, 1)).isFalse();
        assertThat(ShelfLabelRules.isNewer(at, 2, 3, 9, at, 2, 3, 9)).isFalse();
    }
}
