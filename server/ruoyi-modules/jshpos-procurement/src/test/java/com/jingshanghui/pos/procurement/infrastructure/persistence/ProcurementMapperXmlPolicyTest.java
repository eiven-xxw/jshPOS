package com.jingshanghui.pos.procurement.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户查询和采购模块越权写库存。 */
class ProcurementMapperXmlPolicyTest {

    @Test
    void mapperUsesTenantPredicatesLocksAndNeverWritesInventoryTables() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/procurement/ProcurementMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}").contains("for update")
                .contains("insert into pur_receipt").contains("insert into pur_purchase_return")
                .contains("javatype=\"_int\"").contains("javatype=\"_long\"")
                .contains("resultmap=\"supplier\"").contains("resultmap=\"receiptcostsource\"")
                .contains("resultmap=\"returncostsource\"").contains("resultmap=\"returnhead\"")
                .contains("resultmap=\"returnline\"")
                .doesNotContain("javatype=\"int\"").doesNotContain("javatype=\"long\"")
                .doesNotContain("javatype=\"boolean\"")
                .doesNotContain("update inv_stock_balance")
                .doesNotContain("insert into inv_stock_ledger")
                .doesNotContain("cost_ledger")
                .doesNotContain("transfer_out");
        }
    }
}
