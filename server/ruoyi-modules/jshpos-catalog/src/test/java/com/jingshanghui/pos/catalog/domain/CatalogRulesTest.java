package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogRulesTest {

    @Test
    void normalizesCodesNamesBarcodesTypesAmountsQuantitiesAndRatios() {
        assertThat(CatalogRules.requireCode(" sku-01 ", "E")).isEqualTo("SKU-01");
        assertThat(CatalogRules.requireName("  商品 A ")).isEqualTo("商品 A");
        assertThat(CatalogRules.requireBarcode("0012345678905")).isEqualTo("0012345678905");
        assertThat(CatalogRules.requireProductType(" weight ")).isEqualTo("WEIGHT");
        assertThat(CatalogRules.requireMinorAmount(0L)).isZero();
        assertThat(CatalogRules.requireMinorAmount(12345L)).isEqualTo(12345L);
        assertThat(CatalogRules.requireQuantity("12.340000")).isEqualByComparingTo(new BigDecimal("12.34"));
        assertThat(CatalogRules.requireRatio(12L, 8L)).isEqualTo(new CatalogRules.UnitRatio(3, 2));
    }

    @Test
    void rejectsMalformedValuesWithoutFloatingPointCoercion() {
        assertBad(() -> CatalogRules.requireCode(null, "E"));
        assertBad(() -> CatalogRules.requireCode("a b", "E"));
        assertBad(() -> CatalogRules.requireName(" "));
        assertBad(() -> CatalogRules.requireName("x".repeat(201)));
        assertBad(() -> CatalogRules.requireBarcode("bad barcode"));
        assertBad(() -> CatalogRules.requireProductType("SERVICE"));
        assertBad(() -> CatalogRules.requireMinorAmount(null));
        assertBad(() -> CatalogRules.requireMinorAmount(-1L));
        assertBad(() -> CatalogRules.requireQuantity("NaN"));
        assertBad(() -> CatalogRules.requireQuantity("0"));
        assertBad(() -> CatalogRules.requireQuantity("1.0000001"));
        assertBad(() -> CatalogRules.requireQuantity("10000000000000"));
        assertBad(() -> CatalogRules.requireRatio(null, 1L));
        assertBad(() -> CatalogRules.requireRatio(1L, 0L));
    }

    @Test
    void enforcesLifecycleTransitions() {
        assertThat(CatalogRules.transitionState("DRAFT", "DRAFT")).isEqualTo("DRAFT");
        assertThat(CatalogRules.transitionState("DRAFT", "ACTIVE")).isEqualTo("ACTIVE");
        assertThat(CatalogRules.transitionState("ACTIVE", "INACTIVE")).isEqualTo("INACTIVE");
        assertThat(CatalogRules.transitionState("INACTIVE", "ACTIVE")).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> CatalogRules.transitionState("ACTIVE", "DRAFT"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CAT-PRD-006");
        assertBad(() -> CatalogRules.transitionState("UNKNOWN", "ACTIVE"));
        assertBad(() -> CatalogRules.transitionState("ACTIVE", "UNKNOWN"));
    }

    private void assertBad(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ServiceException.class);
    }
}
