package com.jingshanghui.pos.returns.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、注解 SQL、跨 Owner 表写入和历史事实覆盖。 */
class ReturnMapperXmlPolicyTest {
    @Test
    void mapperUsesExplicitTenantPredicatesLocksAndAppendOnlyFacts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/returns/ReturnMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("<select id=\"lockorderguard\"").contains("<select id=\"lockreturn\"")
                .contains("for update").contains("insert into ret_state_history")
                .contains("insert into ret_inbox").contains("insert into ret_outbox")
                .contains("insert into ret_idempotency")
                .doesNotContain("update ret_state_history").doesNotContain("delete from ret_")
                .doesNotContain("update ord_").doesNotContain("update pay_")
                .doesNotContain("update inv_").doesNotContain("update prm_");
        }
    }

    @Test
    void mapperInterfaceContainsNoSqlAnnotations() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/returns/infrastructure/persistence/mapper/ReturnMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert")
            .doesNotContain("@Update").doesNotContain("@Delete");
    }

    @Test
    void exchangeMapperUsesControlledStateUpdatesAndNeverWritesOtherOwners() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/returns/ExchangeMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("<select id=\"lockexchange\"").contains("for update")
                .contains("insert into ret_exchange_leg").contains("insert into ret_exchange_event")
                .contains("insert into ret_inbox").contains("insert into ret_outbox")
                .contains("status=#{nextstatus}").contains("record_version=#{expectedversion}")
                .doesNotContain("delete from ret_").doesNotContain("update ord_")
                .doesNotContain("update pay_").doesNotContain("update inv_").doesNotContain("update prm_");
        }
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/returns/infrastructure/persistence/mapper/ExchangeMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert")
            .doesNotContain("@Update").doesNotContain("@Delete");
    }
}
