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

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
