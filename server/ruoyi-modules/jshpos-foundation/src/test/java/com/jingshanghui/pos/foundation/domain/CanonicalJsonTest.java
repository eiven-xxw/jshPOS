package com.jingshanghui.pos.foundation.domain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalJsonTest {

    @Test
    void producesStableJsonAndHashRegardlessOfMapOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", List.of(2, 1));
        first.put("a", Map.of("b", true, "a", 1));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("a", 1, "b", true));
        second.put("z", List.of(2, 1));

        assertThat(CanonicalJson.from(first)).isEqualTo(CanonicalJson.from(second));
        assertThat(CanonicalJson.from(first).sha256()).matches("[a-f0-9]{64}");
    }

    @Test
    void rejectsUnsupportedTypesAndOversizedContent() {
        assertThatThrownBy(() -> CanonicalJson.from(null)).hasMessageContaining("FND-CFG-001");
        assertThatThrownBy(() -> CanonicalJson.from(Map.of("bad", new Object())))
            .hasMessageContaining("FND-CFG-004");
        assertThatThrownBy(() -> CanonicalJson.from(Map.of("large", "x".repeat(70_000))))
            .hasMessageContaining("FND-CFG-002");
    }

    @Test
    void preservesJsonScalarsAndNulls() {
        var result = CanonicalJson.from(Map.of("values", java.util.Arrays.asList(null, "x", 1, true)));

        assertThat(result.json()).isEqualTo("{\"values\":[null,\"x\",1,true]}");
    }
}
