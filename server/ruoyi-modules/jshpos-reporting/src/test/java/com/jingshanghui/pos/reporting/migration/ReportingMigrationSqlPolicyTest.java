package com.jingshanghui.pos.reporting.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** V32-V35 元数据、索引、权限和不可变来源保护静态门禁。 */
class ReportingMigrationSqlPolicyTest {
    @Test void migrationDeclaresCommentsIndexesStrategiesAndGuards() throws Exception {
        String core=resource("/db/migration/V202608170032__gate5d_reporting_core.sql").toLowerCase();
        String permissions=resource("/db/migration/V202608170033__gate5d_reporting_permissions.sql").toLowerCase();
        String reconciliation=resource("/db/migration/V202608170034__gate5d_payment_reconciliation.sql").toLowerCase();
        String reconciliationPermissions=resource(
            "/db/migration/V202608170035__gate5d_payment_reconciliation_permissions.sql").toLowerCase();
        assertThat(core).contains("comment='gate 5d来源事实幂等inbox；xml_only")
            .contains("read_projection").contains("uk_rpt_source_sequence")
            .contains("idx_rpt_source_replay").contains("trg_rpt_inbox_content_guard")
            .contains("trg_rpt_inbox_no_delete").contains("decimal(25,6)")
            .contains("content_sha256").contains("projection_version")
            .doesNotContain("float").doesNotContain("double");
        assertThat(permissions).contains("9201117").contains("9201125")
            .contains("report:operation:read").contains("report:export:approve")
            .contains("report:projection:rebuild");
        assertThat(reconciliation).contains("provider无关支付退款事实只追加inbox")
            .contains("internal_synthetic").contains("read_projection")
            .contains("uk_rpt_pay_fact_key").contains("uk_rpt_bill_key")
            .contains("idx_rpt_recon_query")
            .contains("trg_rpt_payment_fact_no_update").contains("trg_rpt_payment_fact_no_delete")
            .contains("trg_rpt_internal_bill_no_update").contains("trg_rpt_internal_bill_no_delete")
            .contains("trg_rpt_recon_audit_no_update").contains("trg_rpt_recon_audit_no_delete")
            .doesNotContain("provider_url").doesNotContain("provider_secret")
            .doesNotContain("float").doesNotContain("double");
        assertThat(reconciliationPermissions).contains("9201126").contains("9201129")
            .contains("report:payment:ingest").contains("report:bill:synthetic-import")
            .contains("report:payment-reconciliation:read")
            .contains("report:payment-reconciliation:manage");
    }
    private String resource(String path) throws Exception {
        try(var stream=getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
