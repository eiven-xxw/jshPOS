package com.jingshanghui.pos.inventory.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态核验盘点 SQL 的显式租户条件、行锁和不可变事实写法。 */
class StocktakeMapperXmlPolicyTest {

    @Test
    void mapperUsesTenantPredicatesLocksAndAppendOnlyCounts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/inventory/StocktakeMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *");
            assertThat(xml).contains("tenant_id=#{tenantid}").contains("for update")
                .contains("insert into inv_stocktake_count")
                .doesNotContain("update inv_stocktake_count")
                .doesNotContain("update inv_stock_balance")
                .doesNotContain("insert into inv_stock_ledger");
        }
    }
}
