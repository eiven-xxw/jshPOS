package com.jingshanghui.pos.operations.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态证明 Operations 数据主权、只追加事实和跨 Owner SQL 禁令。 */
class DailyCloseSqlPolicyTest {
    @Test
    void ownsOnlyDailyCloseTablesAndAppendOnlyFacts() throws IOException {
        String sql=resource("/db/migration/V202608230071__gate7d_daily_close.sql").toLowerCase();
        for(String table:new String[]{"ops_daily_close","ops_daily_close_snapshot","ops_daily_close_checkpoint",
            "ops_daily_close_preflight","ops_daily_close_difference","ops_daily_close_approval",
            "ops_daily_close_signature","ops_daily_close_command_result","ops_daily_close_state_event",
            "ops_daily_close_audit","ops_daily_close_outbox"}) assertThat(sql).contains("create table "+table);
        assertThat(sql).contains("trg_ops_close_guard").contains("trg_ops_snapshot_no_delete")
            .contains("trg_ops_signature_no_delete").contains("trg_ops_audit_no_delete")
            .doesNotContain("update ord_").doesNotContain("update shf_").doesNotContain("update pay_")
            .doesNotContain("update inv_").doesNotContain("update rpt_");
    }

    @Test
    void operationsMapperIsTenantScopedAndHasNoCrossOwnerSql() throws IOException {
        String xml=resource("/mapper/operations/DailyCloseMapper.xml").toLowerCase();
        assertThat(xml).contains("tenant_id=#{tenantid}").contains("id=\"changestate\"")
            .contains("id=\"appendoutbox\"").doesNotContain("select *")
            .doesNotContain(" from ord_").doesNotContain(" from shf_").doesNotContain(" from pay_")
            .doesNotContain(" from inv_").doesNotContain(" from rpt_");
    }

    @Test
    void permissionMigrationRegistersOnlyDailyCloseActions() throws IOException {
        String sql=resource("/db/migration/V202608230072__gate7d_daily_close_permissions.sql").toLowerCase();
        assertThat(sql).contains("operations/daily-close/index").contains("operations:daily-close:create")
            .contains("operations:daily-close:preflight").contains("operations:daily-close:approve")
            .contains("operations:daily-close:sign").contains("operations:daily-close:late-fact")
            .doesNotContain("provider sdk").doesNotContain("hardware sdk");
    }

    private String resource(String name)throws IOException{
        try(var input=getClass().getResourceAsStream(name)){
            if(input==null)throw new IOException("missing "+name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
}
