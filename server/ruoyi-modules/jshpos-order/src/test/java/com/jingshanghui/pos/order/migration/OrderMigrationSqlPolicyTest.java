package com.jingshanghui.pos.order.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMigrationSqlPolicyTest {

    @Test
    void formalMigrationsContainNoProbeTablesAndProtectCashFacts() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V202608160005__gate2_order_shift_cash.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).doesNotContain("syn_");
        assertThat(sql).contains("tenant_id", "submitted order snapshot is immutable", "shift approval is immutable",
            "cash payment is immutable", "cash ledger is append-only",
            "order history is append-only", "order audit is append-only", "idempotency_key", "payload_sha256");
        assertThat(sql).doesNotContain("float", "double");
    }

    @Test
    void gate5bMigrationAllowsDiscountsAndKeepsPromotionBindingImmutable() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608170024__gate5b_order_promotion_binding.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains(
            "receivable_amount_minor = gross_amount_minor - discount_amount_minor + surcharge_amount_minor",
            "payable_amount_minor = gross_amount_minor - discount_amount_minor + surcharge_amount_minor",
            "create table ord_promotion_binding", "服务端可信租户标识", "最小货币单位分",
            "promotion owner成交快照ulid只读引用", "trg_ord_promotion_binding_no_update",
            "trg_ord_promotion_binding_no_delete", "ord_promotion_binding is immutable");
        assertThat(sql).doesNotContain("float", "double", "references prm_", "update prm_", "delete from prm_");
    }
}
