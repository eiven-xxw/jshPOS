package com.jingshanghui.pos.inventory.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LotInventoryMigrationSqlPolicyTest {
    private static final String RESOURCE = "/db/migration/V202608230069__gate7c_lot_inventory.sql";

    @Test
    void migrationDeclaresTenantKeysExactQuantitiesAndImmutableFacts() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(RESOURCE)) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(sql).contains("DECIMAL(19,6)", "PRIMARY KEY (tenant_id, lot_id)",
            "uk_inv_lot_ledger_source", "quantity_before + quantity_delta = quantity_after",
            "inv_lot_identity is immutable", "inv_lot_ledger is immutable",
            "inv_lot_allocation is immutable", "applied inv_lot_command is immutable",
            "inv_lot_package_release", "inv_lot_package_release is immutable",
            "previous_version = package_version - 1", "last_ledger_sequence BIGINT NOT NULL COMMENT '批次流水检查点'");
        assertThat(sql).doesNotContain("FLOAT", "DOUBLE", "ON DELETE CASCADE");
    }
}
