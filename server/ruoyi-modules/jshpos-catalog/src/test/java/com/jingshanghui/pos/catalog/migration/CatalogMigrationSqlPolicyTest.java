package com.jingshanghui.pos.catalog.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMigrationSqlPolicyTest {

    @Test
    void migrationOwnsOnlyGate1CatalogPriceAndPackageTables() throws IOException {
        String sql = resource("/db/migration/V202608160003__gate1_catalog_pricing.sql").toLowerCase();
        for (String table : new String[]{
            "cat_category", "cat_brand", "cat_unit", "cat_spu", "cat_sku", "cat_sku_unit",
            "cat_barcode", "cat_import_batch", "cat_import_record", "cat_import_error",
            "cat_catalog_binding", "prc_price_book", "prc_price_item", "dpk_catalog_package", "cat_event_outbox"
        }) {
            assertThat(sql).contains("create table " + table);
        }
        for (String forbidden : new String[]{
            "jsh_order", "jsh_payment", "jsh_refund", "jsh_inventory", "jsh_procurement",
            "jsh_cost", "jsh_promotion"
        }) {
            assertThat(sql).doesNotContain("create table " + forbidden);
        }
        assertThat(sql).contains("tenant_id varchar(20) not null", "amount_minor bigint not null",
            "ratio_numerator bigint not null", "trg_prc_published_book_immutable",
            "trg_cat_published_import_immutable", "uk_cat_barcode_value", "uk_cat_sku_primary");
    }

    @Test
    void permissionMigrationMatchesServerEnforcedPermissions() throws IOException {
        String sql = resource("/db/migration/V202608160004__gate1_catalog_permissions.sql");
        for (String permission : new String[]{
            "catalog:product:query", "catalog:product:manage", "catalog:definition:manage",
            "catalog:import:preflight", "catalog:import:publish", "catalog:price:query",
            "catalog:price:manage", "catalog:price:publish", "catalog:package:query",
            "catalog:package:publish"
        }) {
            assertThat(sql).contains(permission);
        }
        assertThat(sql).doesNotContain("catalog:order", "catalog:payment", "catalog:inventory");
    }

    @Test
    void weightedBarcodeMigrationKeepsTemplatesVersionedTenantScopedAndAppendOnly() throws IOException {
        String sql = resource("/db/migration/V202608220059__gate7c_weighted_barcode.sql").toLowerCase();
        assertThat(sql).contains("create table cat_weighted_barcode_template",
            "create table cat_weighted_barcode_history", "tenant_id varchar(20) not null",
            "prefix_value varchar(5)", "content_sha256 char(64)", "trg_cat_wbt_published_immutable",
            "trg_cat_wbh_no_update", "trg_cat_wbh_no_delete", "catalog:weighted-barcode:publish");
        assertThat(sql).doesNotContain("float", "double", "create table jsh_order", "provider_http");
    }

    @Test
    void shelfLabelMigrationFreezesSnapshotsAndKeepsPrinterFailClosed() throws IOException {
        String sql = resource("/db/migration/V202608220061__gate7c_shelf_label.sql").toLowerCase();
        for (String table : new String[]{"lbl_template", "lbl_label_task", "lbl_label_task_item",
            "lbl_task_event", "lbl_task_exception"}) {
            assertThat(sql).contains("create table " + table);
        }
        assertThat(sql).contains("trg_lbl_template_published_immutable", "trg_lbl_item_snapshot_immutable",
            "trg_lbl_event_no_update", "trg_lbl_event_no_delete", "catalog:label:task:dispatch",
            "软件任务投影状态，不表示真实打印成功", "由可信认证上下文注入的租户标识");
        assertThat(sql).doesNotContain("provider_http", "serialport", "bluetooth", "usb", "print_success");
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
