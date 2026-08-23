package com.jingshanghui.pos.service.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** V85/V86 前向迁移的租户、精度、附件和只追加约束静态门禁。 */
class ServiceMigrationPolicyTest {
    @Test
    void shouldKeepServiceFactsTenantScopedAndAppendOnly() throws Exception {
        String sql = resource("/db/migration/V202608240085__gate8a_service_operations.sql");
        assertAll(
            () -> assertEquals(10, count(sql, "CREATE TABLE svc_")),
            () -> assertFalse(sql.toLowerCase().contains(" float")),
            () -> assertFalse(sql.toLowerCase().contains(" double")),
            () -> assertFalse(sql.contains("attachment_body")),
            () -> assertTrue(sql.contains("object_key VARCHAR(512) NOT NULL COMMENT")),
            () -> assertTrue(sql.contains("tenant_id VARCHAR(20) NOT NULL COMMENT")),
            () -> assertTrue(sql.contains("service history is append only")),
            () -> assertTrue(sql.contains("service command result is append only")),
            () -> assertTrue(sql.contains("service audit is append only"))
        );
    }

    @Test
    void shouldInstallEveryControllerPermission() throws Exception {
        String sql = resource("/db/migration/V202608240086__gate8a_service_permissions.sql");
        for (String permission : new String[]{"service:catalog:manage", "service:project:read", "service:project:create",
            "service:project:operate", "service:ticket:read", "service:ticket:create", "service:ticket:operate",
            "service:attachment:upload", "service:attachment:download", "service:attachment:cleanup"}) {
            assertTrue(sql.contains(permission), permission);
        }
    }

    private String resource(String path) throws Exception {
        try (var input = getClass().getResourceAsStream(path)) {
            assertNotNull(input, path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int count(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
