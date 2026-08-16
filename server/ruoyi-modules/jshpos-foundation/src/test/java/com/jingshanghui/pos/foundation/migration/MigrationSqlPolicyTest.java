package com.jingshanghui.pos.foundation.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationSqlPolicyTest {

    @Test
    void migrationContainsOnlyGate0TenantTablesAndImmutableTriggers() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream("/db/migration/V202608160001__gate0_foundation.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        for (String table : new String[]{
            "jsh_org_unit", "jsh_store", "jsh_staff_scope", "jsh_config_template",
            "jsh_config_template_version", "jsh_config_binding", "jsh_audit_event"
        }) {
            assertThat(sql).contains("create table " + table);
        }
        for (String forbidden : new String[]{
            "jsh_order", "jsh_payment", "jsh_refund", "jsh_inventory", "jsh_product", "jsh_price", "jsh_promotion"
        }) {
            assertThat(sql).doesNotContain("create table " + forbidden);
        }
        assertThat(sql).contains("trg_jsh_audit_no_update", "trg_jsh_audit_no_delete",
            "trg_jsh_config_published_immutable", "tenant_id varchar(20) not null");
    }

    @Test
    void permissionMigrationContainsEveryServerEnforcedGate0Permission() throws IOException {
        String sql;
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V202608160002__gate0_foundation_permissions.sql")) {
            assertThat(input).isNotNull();
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String permission : new String[]{
            "foundation:org:query", "foundation:org:manage", "foundation:store:query",
            "foundation:store:manage", "foundation:scope:query", "foundation:scope:grant",
            "foundation:config:query", "foundation:config:manage", "foundation:config:publish",
            "foundation:config:activate", "foundation:audit:query"
        }) {
            assertThat(sql).contains(permission);
        }
        assertThat(sql).doesNotContain("foundation:product", "foundation:price", "foundation:order");
    }
}
