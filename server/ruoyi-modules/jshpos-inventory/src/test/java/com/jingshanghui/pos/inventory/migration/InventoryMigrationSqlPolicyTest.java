package com.jingshanghui.pos.inventory.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryMigrationSqlPolicyTest {

    @Test
    void migrationContainsTenantConstraintsChecksAndImmutableTriggers() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608160011__gate4a_inventory_ledger.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("primary key (tenant_id, ledger_id)")
            .contains("quantity_before + quantity_delta = quantity_after")
            .contains("uk_inv_ledger_source_line")
            .contains("trg_inv_ledger_no_update")
            .contains("trg_inv_policy_no_update")
            .contains("foreign key (tenant_id, sku_id)");
        assertThat(sql).doesNotContain("purchase_receipt").doesNotContain("stocktake")
            .doesNotContain("cost_ledger").doesNotContain("transfer_out");
    }
}
