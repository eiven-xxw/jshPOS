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

    private String resource(String name) throws Exception {
        try (var stream=getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
