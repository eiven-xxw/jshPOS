package com.jingshanghui.pos.migration.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态证明 T2-DMT-001 迁移表、租户外键、只追加事实和权限脚本未创建跨 Owner 后门。 */
class BusinessMigrationSqlPolicyTest {

    @Test
    void freezesMigrationOwnerTablesAndTenantScopedReferences() throws IOException {
        String sql = resource("/db/migration/V202608220064__gate7c_business_data_migration.sql").toLowerCase();
        for (String table : new String[]{"mig_batch", "mig_file", "mig_staging_row", "mig_preflight_error",
            "mig_approval", "mig_owner_checkpoint", "mig_reconciliation", "mig_state_event",
            "mig_audit_event", "mig_outbox"}) {
            assertThat(sql).contains("create table " + table);
        }
        assertThat(sql).contains("fk_mig_stage_file").contains("fk_mig_error_file")
            .contains("fk_mig_checkpoint_row")
            .contains("trg_mig_stage_guard")
            .contains("trg_mig_error_no_update")
            .contains("content_hmac").contains("key_version")
            .doesNotContain("insert into cat_")
            .doesNotContain("insert into sup_")
            .doesNotContain("insert into inv_")
            .doesNotContain("insert into mem_");
    }

    @Test
    void addsOnlyDmtPermissionsAndExistingVueRoute() throws IOException {
        String sql = resource("/db/migration/V202608220065__gate7c_business_migration_permissions.sql").toLowerCase();
        assertThat(sql).contains("operations/business-migration/index")
            .contains("migration:upload").contains("migration:read")
            .contains("migration:approve").contains("migration:execute").contains("migration:activate")
            .doesNotContain("payment").doesNotContain("hardware");
    }

    @Test
    void keepsControlledAndAppendOnlyBusinessSqlInTenantScopedXml() throws IOException {
        String xml = resource("/mapper/migration/BusinessMigrationMapper.xml").toLowerCase();
        assertThat(xml).contains("namespace=\"com.jingshanghui.pos.migration.infrastructure.persistence.mapper.businessmigrationmapper\"")
            .contains("tenant_id=#{tenantid}")
            .contains("id=\"changebatchstate\"")
            .contains("id=\"clearstaging\"")
            .doesNotContain("select *");
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            if (input == null) throw new IOException("missing " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
