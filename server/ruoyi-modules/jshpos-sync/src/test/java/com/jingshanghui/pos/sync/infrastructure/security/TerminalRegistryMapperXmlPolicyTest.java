package com.jingshanghui.pos.sync.infrastructure.security;

import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.TerminalRegistryMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalRegistryMapperXmlPolicyTest {
    @Test
    void keepsEveryTerminalSqlStatementInXmlAndDocumentsNarrowAuthenticationRoots() throws Exception {
        for (var method : TerminalRegistryMapper.class.getDeclaredMethods()) {
            assertThat(method.getAnnotation(Select.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(Insert.class)).as(method.getName()).isNull();
            assertThat(method.getAnnotation(Update.class)).as(method.getName()).isNull();
        }
        String xml;
        try (var stream = getClass().getResourceAsStream("/mapper/sync/TerminalRegistryMapper.xml")) {
            assertThat(stream).isNotNull();
            xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(xml).contains("tenant_id=#{tenantid}", "secret_hmac", "for update",
            "status='active'", "record_version=#{expectedversion}");
        assertThat(xml).doesNotContain("${", "select *", "update jsh_", "update ord_", "update inv_",
            "update pay_", "update prm_", "delete from");
    }
}
