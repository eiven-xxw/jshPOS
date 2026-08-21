package com.jingshanghui.pos.returns.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** T2-REF-002 Flyway 静态策略：只追加、复合租户键、精确金额数量与权限一致。 */
class ReturnsMigrationSqlPolicyTest {
    @Test
    void returnSagaMigrationContainsLocksInvariantsAndImmutableEvidence() throws Exception {
        String sql = resource("/db/migration/V202608170026__gate5b_return_refund_saga.sql");
        assertThat(sql).contains("create table ret_order_guard")
            .contains("create table ret_return").contains("create table ret_return_line")
            .contains("create table ret_state_history").contains("create table ret_inbox")
            .contains("create table ret_outbox").contains("create table ret_idempotency")
            .contains("primary key (tenant_id,return_id)")
            .contains("requested_quantity decimal(19,6)")
            .contains("gross_amount_minor-recovered_discount_minor=refundable_amount_minor")
            .contains("trg_ret_history_no_update").contains("trg_ret_outbox_guard")
            .contains("trg_ret_return_no_delete").doesNotContain(" float")
            .doesNotContain(" double").doesNotContain("update ord_")
            .doesNotContain("update pay_").doesNotContain("update inv_").doesNotContain("update prm_");
    }

    @Test
    void permissionsMatchPublicApiAndReservedRange() throws Exception {
        String sql = resource("/db/migration/V202608170027__gate5b_return_permissions.sql");
        assertThat(sql).contains("between 9201000 and 9201003")
            .contains("return:request:create").contains("return:request:approve")
            .contains("return:request:read").doesNotContain("provider");
    }

    @Test
    void exchangeMigrationFreezesTwoOwnerLegsAndAppendOnlyRecoveryEvidence() throws Exception {
        String sql = resource("/db/migration/V202608220055__gate7b_exchange_orchestration.sql");
        assertThat(sql).contains("add column request_command_id")
            .contains("create table ret_exchange").contains("create table ret_exchange_leg")
            .contains("create table ret_exchange_event").contains("create table ret_exchange_idempotency")
            .contains("original_order_id<>new_order_id")
            .contains("leg_type='return' and owner_code='return'")
            .contains("leg_type='sale' and owner_code='order'")
            .contains("trg_ret_exchange_leg_no_update").contains("trg_ret_exchange_event_no_delete")
            .doesNotContain("create table pay_").doesNotContain("create table inv_")
            .doesNotContain(" float").doesNotContain(" double");
        String permission = resource("/db/migration/V202608220056__gate7b_exchange_permissions.sql");
        assertThat(permission).contains("pos:exchange:read").contains("pos:exchange:create")
            .contains("pos:exchange:approve").contains("pos:exchange:recover")
            .doesNotContain("provider");
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
