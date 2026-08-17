package com.jingshanghui.pos.member.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

/** T2-MEM-001 Flyway 静态策略：加密身份、复合租户键、只追加隐私证据和权限一致。 */
class MemberMigrationSqlPolicyTest {
    @Test void memberMigrationContainsPrivacyAndTenantInvariants() throws Exception {
        String sql=resource("/db/migration/V202608170028__gate5c_member_privacy.sql");
        assertThat(sql).contains("create table mbr_member").contains("create table mbr_identity")
            .contains("lookup_hmac char(64)").contains("cipher_text text")
            .contains("create table mbr_consent_ledger").contains("create table mbr_privacy_history")
            .contains("create table mbr_member_link_ledger").contains("create table mbr_event_outbox")
            .contains("primary key (tenant_id,member_id)").contains("xml_only")
            .doesNotContain(" float").doesNotContain(" double").doesNotContain("phone_number")
            .doesNotContain("update ord_").doesNotContain("update pay_").doesNotContain("update inv_");
    }

    @Test void permissionsUseReservedRangeAndNoProviderCapability() throws Exception {
        String sql=resource("/db/migration/V202608170029__gate5c_member_permissions.sql");
        assertThat(sql).contains("between 9201100 and 9201110").contains("member:profile:create")
            .contains("member:identity:bind").contains("member:privacy:process")
            .contains("member:identity:merge").doesNotContain("provider");
    }

    @Test void pointsMigrationUsesExactDecimalsAppendOnlyFactsAndRebuildableProjection() throws Exception {
        String sql=resource("/db/migration/V202608170030__gate5c_member_points.sql");
        assertThat(sql).contains("create table mbr_points_account").contains("create table mbr_points_ledger")
            .contains("create table mbr_points_lot").contains("create table mbr_points_allocation")
            .contains("create table mbr_level_history").contains("decimal(19,6)")
            .contains("debt_points").contains("business_date").contains("actor_user_id")
            .contains("approval_user_id").contains("approval_ref").contains("fk_mbr_points_ledger_store")
            .contains("trg_mbr_points_ledger_no_update")
            .contains("trg_mbr_points_allocation_no_delete").contains("trg_mbr_level_history_no_update")
            .doesNotContain(" float").doesNotContain(" double").doesNotContain("update ord_")
            .doesNotContain("update ret_").doesNotContain("update pay_");
        String permissions=resource("/db/migration/V202608170031__gate5c_member_points_permissions.sql");
        assertThat(permissions).contains("between 9201111 and 9201116").contains("member:points:read")
            .contains("member:points:freeze").contains("member:points:settle")
            .contains("member:points:rebuild").contains("member:level:manage").doesNotContain("provider");
    }

    private String resource(String name) throws Exception {
        try (var stream=getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
