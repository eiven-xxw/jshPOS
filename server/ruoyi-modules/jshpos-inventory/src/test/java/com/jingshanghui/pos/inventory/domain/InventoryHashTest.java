package com.jingshanghui.pos.inventory.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryHashTest {

    @Test
    void canonicalFramingAndDimensionAreStableAndTenantScoped() {
        assertThat(InventoryHash.canonical(List.of("ab", "c"))).isEqualTo("2:ab;1:c;");
        String first = InventoryHash.dimension("TENANT_A", "01K2A000000000000000000010", 701L);
        assertThat(first).hasSize(64).matches("[a-f0-9]{64}");
        assertThat(first).isEqualTo(InventoryHash.dimension("TENANT_A", "01K2A000000000000000000010", 701L));
        assertThat(first).isNotEqualTo(InventoryHash.dimension("TENANT_B", "01K2A000000000000000000010", 701L));
        assertThat(InventoryHash.sha256("abc"))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
