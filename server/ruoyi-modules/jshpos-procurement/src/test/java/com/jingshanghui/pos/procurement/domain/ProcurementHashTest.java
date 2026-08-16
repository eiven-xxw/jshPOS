package com.jingshanghui.pos.procurement.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProcurementHashTest {

    @Test
    void canonicalEncodingIsUnambiguousAndHashStable() {
        assertThat(ProcurementHash.canonical(List.of("ab", "c")))
            .isNotEqualTo(ProcurementHash.canonical(List.of("a", "bc")));
        assertThat(ProcurementHash.sha256("same"))
            .isEqualTo(ProcurementHash.sha256("same")).hasSize(64);
    }
}
