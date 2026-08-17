package com.jingshanghui.pos.sync.migration;

import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SyncMigrationSqlPolicyTest {

    @Test
    void migrationsAreAppendOnlyTenantScopedAndContainNoLaterGateRuntime() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V202608160007__sprint3_pos_sync.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("tenant_id", "pos_sync_inbox", "pos_sync_business_fact",
            "pos_sync_change_feed", "pos_sync_cursor", "pos_sync_dead_letter", "append-only");
        assertThat(sql).doesNotContain("create table syn_", "float", "double", "payment_provider",
            "refund", "inventory", "promotion");
    }

    @Test
    void cursorUpsertIsMonotonicEvenDuringConcurrentFirstAck() throws Exception {
        Method method = SyncMapper.class.getMethod("upsertCursor", String.class, String.class, String.class,
            long.class, String.class, String.class);
        String sql = method.getAnnotation(org.apache.ibatis.annotations.Insert.class).value()[0].toLowerCase();
        assertThat(sql).contains("greatest(acked_sequence,values(acked_sequence))")
            .contains("if(values(acked_sequence)>=acked_sequence");
    }

    @Test
    void gate6aTerminalMigrationExpandsTheExistingOwnerAndProtectsSecretsAndHistory() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/migration/V202608160036__gate6a_terminal_registry.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("alter table pos_sync_device", "dev_terminal_activation",
            "dev_terminal_credential", "dev_capability_snapshot", "dev_terminal_audit",
            "secret_hmac", "activation_evidence_level", "append-only");
        assertThat(sql).doesNotContain("activation_secret", "device_credential varchar", "private_key",
            "create table dev_terminal (");
    }
}
