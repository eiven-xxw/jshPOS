package com.jingshanghui.pos.costing.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CostingHashTest {

    @Test
    void canonicalHashAndTenantDimensionAreDeterministicAndSeparated() {
        assertThat(CostingHash.canonical(List.of("ab", 1))).isEqualTo("2:ab;1:1;");
        assertThat(CostingHash.sha256("fact")).matches("^[a-f0-9]{64}$");
        assertThat(CostingHash.dimension("TENANT_A", "01K2A000000000000000000010", 701L))
            .isNotEqualTo(CostingHash.dimension("TENANT_B", "01K2A000000000000000000010", 701L));
    }
}
