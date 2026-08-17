package com.jingshanghui.pos.returns.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨 Owner 内容摘要必须确定、无拼接歧义并复用规范 JSON。 */
class ReturnHashTest {
    @Test
    void canonicalEncodingSeparatesNullAndDifferentFieldBoundaries() {
        assertThat(ReturnHash.canonical(List.of("ab", "c"))).isEqualTo("2:ab|1:c");
        assertThat(ReturnHash.canonical(java.util.Arrays.asList(null, "<null>")))
            .isEqualTo("6:<null>|6:<null>");
        assertThat(ReturnHash.canonical(List.of("ab", "c")))
            .isNotEqualTo(ReturnHash.canonical(List.of("a", "bc")));
    }

    @Test
    void sha256AndPayloadAreStableAcrossMapInsertionOrder() {
        assertThat(ReturnHash.sha256("synthetic-return"))
            .isEqualTo("59e15bde216a5ac13e13aec29d02655f78944b07d75a57fc8124891744fc41f6");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("returnId", "01K5R000000000000000000001");
        first.put("amountMinor", 900);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("amountMinor", 900);
        second.put("returnId", "01K5R000000000000000000001");
        assertThat(ReturnHash.payload(first).sha256()).isEqualTo(ReturnHash.payload(second).sha256());
        assertThat(ReturnHash.payload(first).json()).isEqualTo(ReturnHash.payload(second).json());
    }
}
