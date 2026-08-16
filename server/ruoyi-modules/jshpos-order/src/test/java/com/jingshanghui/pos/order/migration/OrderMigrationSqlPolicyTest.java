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
}
