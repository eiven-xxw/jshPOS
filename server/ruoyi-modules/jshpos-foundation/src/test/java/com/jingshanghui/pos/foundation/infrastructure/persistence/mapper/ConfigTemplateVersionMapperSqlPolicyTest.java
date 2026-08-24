package com.jingshanghui.pos.foundation.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Foundation 配置版本锁定查询的 XML、显式列和可信租户边界回归。 */
class ConfigTemplateVersionMapperSqlPolicyTest {

    @Test
    void shouldKeepLockingQueryInXmlWithExplicitColumnsAndTrustedTenant() throws Exception {
        var method = ConfigTemplateVersionMapper.class.getMethod(
            "selectLatestForUpdate", String.class, Long.class);
        String xml = resource("mapper/foundation/ConfigTemplateVersionMapper.xml");

        assertAll(
            () -> assertFalse(method.isAnnotationPresent(Select.class)),
            () -> assertTrue(xml.contains("resultMap id=\"configTemplateVersionEntityMap\"")),
            () -> assertTrue(xml.contains("WHERE tenant_id = #{trustedTenantId}")),
            () -> assertTrue(xml.contains("AND template_id = #{templateId}")),
            () -> assertTrue(xml.contains("ORDER BY version_no DESC, config_version_id DESC")),
            () -> assertTrue(xml.contains("FOR UPDATE")),
            () -> assertFalse(xml.matches("(?is).*SELECT\\s+\\*.*"))
        );
    }

    private String resource(String path) throws IOException {
        try (var stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "Mapper XML 必须进入运行时 classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
