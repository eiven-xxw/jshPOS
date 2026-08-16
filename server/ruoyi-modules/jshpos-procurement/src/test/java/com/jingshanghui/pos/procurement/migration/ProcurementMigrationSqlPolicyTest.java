package com.jingshanghui.pos.procurement.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态确认采购迁移不创建成本/调拨运行时，且不越权写库存表。 */
class ProcurementMigrationSqlPolicyTest {

    @Test
    void migrationContainsTenantConstraintsAndImmutableTriggersOnlyForAdmittedScope() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608170014__gate4b_procurement.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("primary key (tenant_id, supplier_id)")
            .contains("foreign key (tenant_id, supplier_id)")
            .contains("trg_pur_order_line_core_immutable")
            .contains("trg_pur_receipt_confirmed_immutable")
            .contains("trg_pur_return_posted_immutable")
            .contains("trg_pur_return_line_no_update")
            .doesNotContain("update inv_stock_balance")
            .doesNotContain("insert into inv_stock_ledger")
            .doesNotContain("create table cst_")
            .doesNotContain("create table trf_");
    }
}
