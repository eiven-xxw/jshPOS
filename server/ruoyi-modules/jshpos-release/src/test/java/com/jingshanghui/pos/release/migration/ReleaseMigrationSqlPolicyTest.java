package com.jingshanghui.pos.release.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate 6B 前向迁移、中文Schema契约和只追加保护静态检查。 */
class ReleaseMigrationSqlPolicyTest {
    @Test void registersControlledAndAppendOnlyModelsWithoutProviderOrRemoteCommands() throws Exception {
        String schema=resource("/db/migration/V202608200040__gate6b_release_governance.sql");
        String guards=resource("/db/migration/V202608200041__gate6b_release_guards_permissions.sql");
        assertThat(schema).contains("comment='gate 6b", "controlled_write/xml", "append_only/xml",
            "artifact_sha256", "signature_base64", "build_commit", "sbom_sha256", "tenant_id");
        assertThat(guards).contains("frozen identity", "illegal transition", "append-only", "cannot be deleted");
        assertThat(schema+guards).doesNotContain("alter table ord_", "alter table pay_", "alter table inv_",
            "provider_url", "production_key", "silent_install", "firmware_command", "reboot_command");
    }
    private String resource(String path) throws Exception {
        try(var stream=getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull(); return new String(stream.readAllBytes(),StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
