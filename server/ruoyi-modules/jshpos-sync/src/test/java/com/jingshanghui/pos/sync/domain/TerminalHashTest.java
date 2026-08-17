package com.jingshanghui.pos.sync.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalHashTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalizesNestedMapsWithoutReorderingArrays() {
        Map<String, Object> first = Map.of("z", List.of(3, 2, 1), "a", Map.of("y", true, "x", 1));
        Map<String, Object> second = Map.of("a", Map.of("x", 1, "y", true), "z", List.of(3, 2, 1));
        assertThat(TerminalHash.canonicalJson(mapper, first)).isEqualTo("{\"a\":{\"x\":1,\"y\":true},\"z\":[3,2,1]}");
        assertThat(TerminalHash.digest(mapper, first)).isEqualTo(TerminalHash.digest(mapper, second));
        assertThat(TerminalHash.digest(mapper, Map.of("z", List.of(1, 2, 3))))
            .isNotEqualTo(TerminalHash.digest(mapper, first));
    }
}
