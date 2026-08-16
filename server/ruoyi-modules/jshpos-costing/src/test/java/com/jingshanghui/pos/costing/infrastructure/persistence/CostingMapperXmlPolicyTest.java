package com.jingshanghui.pos.costing.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、直接库存/采购写入和历史成本更新。 */
class CostingMapperXmlPolicyTest {

    @Test
    void mapperUsesExplicitTenantPredicatesAndOnlyOwnsCostTables() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/costing/CostingMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *")
                .contains("tenant_id=#{tenantid}")
                .contains("for update")
                .contains("insert into inv_cost_ledger")
                .contains("update inv_cost_balance")
                .doesNotContain("update inv_cost_ledger")
                .doesNotContain("delete from inv_cost_ledger")
                .doesNotContain("update inv_stock_balance")
                .doesNotContain("insert into inv_stock_ledger")
                .doesNotContain("update pur_")
                .doesNotContain("insert into pur_")
                .doesNotContain("trf_");
        }
    }
}
