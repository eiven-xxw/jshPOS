package com.jingshanghui.pos.reporting.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** V32/V33 元数据、索引、权限和不可变来源保护静态门禁。 */
class ReportingMigrationSqlPolicyTest {
    @Test void migrationDeclaresCommentsIndexesStrategiesAndGuards() throws Exception {
        String core=resource("/db/migration/V202608170032__gate5d_reporting_core.sql").toLowerCase();
        String permissions=resource("/db/migration/V202608170033__gate5d_reporting_permissions.sql").toLowerCase();
        assertThat(core).contains("comment='gate 5d来源事实幂等inbox；xml_only")
            .contains("read_projection").contains("uk_rpt_source_sequence")
            .contains("idx_rpt_source_replay").contains("trg_rpt_inbox_content_guard")
            .contains("trg_rpt_inbox_no_delete").contains("decimal(25,6)")
            .contains("content_sha256").contains("projection_version")
            .doesNotContain("float").doesNotContain("double");
        assertThat(permissions).contains("9201117").contains("9201125")
            .contains("report:operation:read").contains("report:export:approve")
            .contains("report:projection:rebuild");
    }
    private String resource(String path) throws Exception {
        try(var stream=getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
