package com.jingshanghui.pos.costing.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态确认成本表不可变、精度受约束，且 Gate 4C 不创建调拨运行时。 */
class CostingMigrationSqlPolicyTest {

    @Test
    void migrationContainsTenantConstraintsImmutableTriggersAndNoTransferRuntime() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608170016__gate4c_costing.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("primary key (tenant_id, cost_ledger_id)")
            .contains("foreign key (tenant_id, inventory_ledger_id)")
            .contains("decimal(25,6)")
            .contains("trg_inv_cost_ledger_no_update")
            .contains("trg_inv_cost_policy_no_update")
            .contains("trg_inv_cost_audit_no_update")
            .doesNotContain("float")
            .doesNotContain("double")
            .doesNotContain("create table trf_")
            .doesNotContain("update inv_stock_balance")
            .doesNotContain("update pur_");
    }
}
