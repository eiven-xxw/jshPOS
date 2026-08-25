package com.jingshanghui.pos.transfer.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态确认租户复合键、不可变事实、数量恒等式与权限 ID。 */
class TransferMigrationSqlPolicyTest {
    @Test
    void forwardRepairAllowsDraftVersionZeroWithoutAllowingNegativeVersions() throws Exception {
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608260087__g9a_r4_transfer_outbox_version_constraint.sql")) {
            assertThat(stream).as("approved V87 forward repair").isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql)
                .contains("drop check ck_trf_outbox_version")
                .contains("add constraint ck_trf_outbox_version")
                .contains("check (aggregate_version >= 0)")
                .doesNotContain("alter column")
                .doesNotContain("drop table")
                .doesNotContain("delete from");
        }
    }

    @Test
    void migrationFreezesFactsWithoutOwningInventoryOrCostBalances() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V202608170018__gate4d_transfer.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("primary key (tenant_id, transfer_id)")
            .contains("foreign key (tenant_id, transfer_id)")
            .contains("'transfer_dispatch','transfer_receipt'")
            .contains("'transfer_out','transfer_in'")
            .contains("received_quantity + difference_quantity <= dispatched_quantity")
            .contains("ck_trf_line_conversion").contains("conversion_numerator > 0")
            .contains("trg_trf_dispatch_no_update").contains("trg_trf_receipt_no_update")
            .contains("trg_trf_transit_no_update").contains("trg_trf_audit_no_update")
            .contains("ck_trf_transit_reason")
            .contains("reason_code in ('shortage','damaged','rejected','transit_loss')")
            .doesNotContain("update inv_stock_balance").doesNotContain("insert into inv_stock_ledger")
            .doesNotContain("update inv_cost_balance").doesNotContain("insert into inv_cost_ledger");
    }

    @Test
    void permissionsUseReservedRange() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V202608170019__gate4d_permissions.sql")) {
            assertThat(stream).isNotNull();
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("between 9200800 and 9200808")
                .contains("transfer:dispatch:post").contains("transfer:difference:approve");
        }
    }
}
