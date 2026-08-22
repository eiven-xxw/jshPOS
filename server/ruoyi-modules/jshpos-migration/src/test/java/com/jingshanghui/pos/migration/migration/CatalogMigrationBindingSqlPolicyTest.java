package com.jingshanghui.pos.migration.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Catalog 迁移绑定必须只追加并以租户复合外键绑定既有商品和单位。 */
class CatalogMigrationBindingSqlPolicyTest {
    @Test
    void bindsOnlyCatalogOwnedIdentities() throws IOException {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V202608220063__gate7c_catalog_business_migration_binding.sql")) {
            if (input == null) throw new IOException("catalog migration binding missing");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(sql).contains("create table cat_migration_product")
                .contains("foreign key (tenant_id,sku_id)")
                .contains("foreign key (tenant_id,base_unit_id)")
                .contains("trg_cat_migration_product_no_update")
                .contains("trg_cat_migration_product_no_delete")
                .doesNotContain("mig_staging_row");
        }
    }

    @Test
    void keepsCatalogMigrationBindingInAppendOnlyXmlBoundary() throws IOException {
        try (var input = getClass().getResourceAsStream("/mapper/catalog/CatalogMigrationMapper.xml")) {
            if (input == null) throw new IOException("catalog migration mapper XML missing");
            String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).contains("insert into cat_migration_product")
                .contains("tenant_id=#{tenantid}")
                .doesNotContain("update cat_migration_product")
                .doesNotContain("delete from cat_migration_product")
                .doesNotContain("select *");
        }
    }
}
