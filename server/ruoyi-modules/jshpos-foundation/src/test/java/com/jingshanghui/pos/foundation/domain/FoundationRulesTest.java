package com.jingshanghui.pos.foundation.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FoundationRulesTest {

    @Test
    void normalizesValidCodesNamesAndEnums() {
        assertThat(FoundationRules.requireCode(" store_01 ")).isEqualTo("STORE_01");
        assertThat(FoundationRules.requireName("  虚构门店  ")).isEqualTo("虚构门店");
        assertThat(FoundationRules.requireEnum("active", FoundationRules.ACTIVE_STATUS, "X")).isEqualTo("ACTIVE");
        assertThat(FoundationRules.requireSchemaVersion("1.0")).isEqualTo("1.0");
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> FoundationRules.requireCode(null)).hasMessageContaining("FND-VAL-001");
        assertThatThrownBy(() -> FoundationRules.requireCode("a/b")).hasMessageContaining("FND-VAL-001");
        assertThatThrownBy(() -> FoundationRules.requireName(null)).hasMessageContaining("FND-VAL-002");
        assertThatThrownBy(() -> FoundationRules.requireName(" ")).hasMessageContaining("FND-VAL-002");
        assertThatThrownBy(() -> FoundationRules.requireName("x".repeat(101))).hasMessageContaining("FND-VAL-002");
        assertThatThrownBy(() -> FoundationRules.requireEnum(null, FoundationRules.ACTIVE_STATUS, "FND-X"))
            .hasMessageContaining("FND-X");
        assertThatThrownBy(() -> FoundationRules.requireEnum("UNKNOWN", FoundationRules.ACTIVE_STATUS, "FND-X"))
            .hasMessageContaining("FND-X");
        assertThatThrownBy(() -> FoundationRules.requireSchemaVersion(null)).hasMessageContaining("FND-CFG-003");
        assertThatThrownBy(() -> FoundationRules.requireSchemaVersion("v1")).hasMessageContaining("FND-CFG-003");
    }
}
