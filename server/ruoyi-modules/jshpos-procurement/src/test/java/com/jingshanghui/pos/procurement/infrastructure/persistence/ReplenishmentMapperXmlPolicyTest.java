package com.jingshanghui.pos.procurement.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 阻断 Replenishment Mapper 跨 Owner 读写或缺失租户谓词。 */
class ReplenishmentMapperXmlPolicyTest {

    @Test
    void mapperOnlyUsesOwnedTablesAndTrustedTenantPredicates() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/procurement/ReplenishmentMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *")
                .contains("tenant_id=#{tenantid}")
                .contains("for update")
                .contains("insert into rpl_suggestion")
                .doesNotContain(" inv_stock_")
                .doesNotContain("update pur_purchase_order")
                .doesNotContain("insert into pur_purchase_order")
                .doesNotContain("sup_supplier")
                .doesNotContain("cat_sku");
        }
    }
}
