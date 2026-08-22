package com.jingshanghui.pos.procurement.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 稳定摘要用于检测同键异内容和跨端输入篡改。 */
class ReplenishmentHashTest {

    @Test
    void canonicalEncodingPreservesNullAndFieldBoundaries() {
        assertThat(ReplenishmentHash.canonical(List.of())).isEmpty();
        assertThat(ReplenishmentHash.canonical(java.util.Arrays.asList("ab", "c", null)))
            .isEqualTo("2:ab|1:c|6:<null>");
        assertThat(ReplenishmentHash.canonical(List.of("ab", "c")))
            .isNotEqualTo(ReplenishmentHash.canonical(List.of("a", "bc")));
    }

    @Test
    void sha256IsStableLowercaseHex() {
        assertThat(ReplenishmentHash.sha256("鲸熵汇"))
            .hasSize(64).matches("[a-f0-9]{64}")
            .isEqualTo(ReplenishmentHash.sha256("鲸熵汇"));
        assertThat(ReplenishmentHash.sha256("a")).isNotEqualTo(ReplenishmentHash.sha256("b"));
    }
}
