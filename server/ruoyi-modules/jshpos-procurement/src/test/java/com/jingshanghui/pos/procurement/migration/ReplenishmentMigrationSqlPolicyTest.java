package com.jingshanghui.pos.procurement.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态验证补货迁移的数据主权、只追加事实和非目标边界。 */
class ReplenishmentMigrationSqlPolicyTest {

    @Test
    void migrationContainsVersionedRulesTenantConstraintsAndNoInventoryWrites() throws Exception {
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608220062__gate7c_replenishment.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("create table rpl_policy_version")
                .contains("create table rpl_suggestion")
                .contains("primary key (tenant_id,suggestion_id)")
                .contains("trg_rpl_suggestion_fact_immutable")
                .contains("trg_rpl_event_no_update")
                .contains("source_type='replenishment'")
                .doesNotContain("update inv_stock_balance")
                .doesNotContain("insert into inv_stock_ledger")
                .doesNotContain("insert into pur_purchase_order(")
                .doesNotContain("auto_order")
                .doesNotContain("prediction");
        }
    }
}
