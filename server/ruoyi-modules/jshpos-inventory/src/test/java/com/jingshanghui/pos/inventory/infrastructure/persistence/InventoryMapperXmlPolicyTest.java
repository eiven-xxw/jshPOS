package com.jingshanghui.pos.inventory.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断 SELECT *、缺租户条件和未显式锁定的复杂库存 SQL。 */
class InventoryMapperXmlPolicyTest {

    @Test
    void mapperXmlUsesExplicitColumnsTenantPredicatesAndRowLocks() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/inventory/InventoryMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *");
            assertThat(xml).contains("tenant_id=#{tenantid}");
            assertThat(xml).contains("for update");
            assertThat(xml).contains("sum(quantity_delta)");
            assertThat(xml).contains("insert ignore into inv_stock_balance");
            assertThat(xml).doesNotContain("purchase_").doesNotContain("stocktake_").doesNotContain("cost_");
        }
    }
}
