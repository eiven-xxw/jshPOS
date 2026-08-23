package com.jingshanghui.pos.promotion.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、注解 SQL、Provider 网络和历史事实更新。 */
class PromotionMapperXmlPolicyTest {
    @Test
    void mapperUsesExplicitTenantPredicatesAndAppendOnlyFacts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/promotion/PromotionPersistenceMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("insert into prm_rule_version").contains("insert into prm_quote")
                .contains("insert into prm_adjustment").contains("insert into prm_audit_event")
                .contains("insert into prm_event_outbox").contains("insert into prm_manual_price_audit")
                .contains("insert into prm_transaction_snapshot").contains("insert into prm_transaction_allocation")
                .contains("insert into prm_refund_allocation_ledger").contains("insert into prm_quote_member_benefit")
                .contains("from prm_quote_member_benefit where tenant_id=#{tenantid}")
                .contains("insert into prm_member_benefit_package")
                .contains("from prm_member_benefit_package where tenant_id=#{tenantid} and store_id=#{storeid}")
                .contains("from prm_manual_price_audit").contains("order by event_sequence")
                .contains("<select id=\"lockquote\"").contains("for update")
                .contains("<select id=\"locksnapshot\"")
                .doesNotContain("update prm_quote").doesNotContain("update prm_transaction_snapshot")
                .doesNotContain("update prm_manual_price_audit")
                .doesNotContain("update prm_transaction_allocation").doesNotContain("update prm_refund_allocation_ledger")
                .doesNotContain("update ord_").doesNotContain("update inv_").doesNotContain("update pay_")
                .doesNotContain("jsh_config_");
        }
    }

    @Test
    void mapperInterfaceContainsNoSqlAnnotations() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/promotion/infrastructure/persistence/mapper/PromotionPersistenceMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert")
            .doesNotContain("@Update").doesNotContain("@Delete");
    }
}
