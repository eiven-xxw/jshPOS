package com.jingshanghui.pos.payment.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Gate 3A 迁移静态策略：精确金额、租户复合约束、不可变资金证据。 */
class PaymentMigrationSqlPolicyTest {

    @Test
    void paymentMigrationProtectsImmutableFactsAndContainsNoNetworkOrProbeArtifacts() throws IOException {
        String sql = resource("/db/migration/V202608160009__gate3a_payment_refund_reconciliation.sql").toLowerCase();
        assertThat(sql).contains("tenant_id", "amount_minor bigint", "quantity decimal(19,6)",
            "provider observation is immutable", "payment state history is append-only",
            "payment audit is append-only",
            "statement entry is immutable", "idempotency result is immutable");
        assertThat(sql).contains("foreign key (tenant_id, order_id)",
            "foreign key (tenant_id, payment_id)", "foreign key (tenant_id, refund_id)");
        assertThat(sql).doesNotContain("float", " double", "syn_", "'fake_test'", "http://", "https://");
    }

    @Test
    void permissionsAreLimitedToProviderNeutralCoreAndDoNotUnblockSandbox() throws IOException {
        String sql = resource("/db/migration/V202608160010__gate3a_payment_permissions.sql").toLowerCase();
        assertThat(sql).contains("payment:intent:create", "payment:attempt:create", "refund:approve",
            "reconciliation:manage");
        assertThat(sql).doesNotContain("sandbox", "provider:network", "callback:receive", "secret");
    }

    private String resource(String path) throws IOException {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
