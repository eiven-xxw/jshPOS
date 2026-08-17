package com.jingshanghui.pos.transfer.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、调拨越权写库存/成本和历史事实更新。 */
class TransferMapperXmlPolicyTest {
    @Test
    void mapperUsesTenantPredicatesLocksAndOwnerPorts() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapper/transfer/TransferMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("for update").contains("insert into inv_transfer_dispatch")
                .contains("requested_unit_id,conversion_numerator").contains("input_quantity,base_unit_id")
                .contains("insert into inv_transfer_receipt").contains("insert into inv_transfer_transit_ledger")
                .contains("reason_code,correlation_id").contains("select coalesce(sum(quantity),0)")
                .doesNotContain("update inv_stock_balance").doesNotContain("insert into inv_stock_ledger")
                .doesNotContain("update inv_cost_balance").doesNotContain("insert into inv_cost_ledger")
                .doesNotContain("update inv_transfer_dispatch").doesNotContain("update inv_transfer_receipt")
                .doesNotContain("update inv_transfer_transit_ledger");
        }
    }
}
