package com.jingshanghui.pos.payment.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 长度前缀规范串避免字段拼接歧义并提供确定摘要。 */
class PaymentHashTest {

    @Test
    void canonicalEncodingSeparatesAmbiguousValuesAndHashIsStable() {
        String first = PaymentHash.canonical(List.of("ab", "c", 12));
        String second = PaymentHash.canonical(List.of("a", "bc", 12));
        assertThat(first).isNotEqualTo(second).isEqualTo("2:ab;1:c;2:12;");
        assertThat(PaymentHash.sha256(first)).hasSize(64).isEqualTo(PaymentHash.sha256(first));
    }
}
