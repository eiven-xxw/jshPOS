package com.jingshanghui.pos.promotion.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态确认 PRM-001—003 中文注释、租户复合键、守恒约束和不可变触发器。 */
class PromotionMigrationSqlPolicyTest {
    @Test
    void migrationOwnsPromotionFactsAndCommentsEveryColumn() throws Exception {
        String sql = resource("/db/migration/V202608170020__gate5a_promotion.sql");
        assertThat(count(sql, "create table prm_")).isEqualTo(12);
        assertThat(sql).contains("primary key (tenant_id,rule_id)")
            .contains("foreign key (tenant_id,rule_id)")
            .contains("gross_amount_minor=discount_amount_minor+payable_amount_minor")
            .contains("trg_prm_rule_version_content_immutable")
            .contains("trg_prm_package_item_no_update")
            .contains("trg_prm_quote_no_update")
            .contains("trg_prm_audit_no_update")
            .doesNotContain("prm_manual_price_audit").doesNotContain("prm_transaction_snapshot")
            .doesNotContain("prm_transaction_allocation").doesNotContain("prm_refund_allocation_ledger")
            .doesNotContain("update ord_").doesNotContain("update inv_").doesNotContain("update pay_");
        Matcher tables = Pattern.compile("(?is)create table prm_[^(]+\\((.*?)\\) comment='").matcher(sql);
        while (tables.find()) {
            Matcher columns = Pattern.compile("(?m)^  [a-z_]+ [^,\n]+(?:,)?$").matcher(tables.group(1));
            while (columns.find()) {
                String line = columns.group().stripLeading();
                if (!line.startsWith("primary ") && !line.startsWith("unique ") && !line.startsWith("key ")
                    && !line.startsWith("constraint ")) assertThat(line).as(line).contains("comment '");
            }
        }
        assertThat(sql).contains("comment='gate 5a");
    }

    @Test
    void permissionsMatchOpenApiAndReservedRange() throws Exception {
        String sql = resource("/db/migration/V202608170021__gate5a_permissions.sql");
        assertThat(sql).contains("between 9200900 and 9200914")
            .contains("promotion:quote:calculate").contains("promotion:package:read")
            .doesNotContain("promotion:manual:authorize").doesNotContain("promotion:snapshot:freeze")
            .doesNotContain("promotion:refund:read");
    }

    @Test
    void prm2MigrationAddsOnlyAppendOnlyManualFactsAndThreePermissions() throws Exception {
        String sql = resource("/db/migration/V202608170022__gate5a_manual_promotion.sql");
        assertThat(count(sql, "create table prm_")).isEqualTo(1);
        assertThat(sql).contains("create table prm_manual_price_audit")
            .contains("foreign key (tenant_id,quote_id)")
            .contains("trg_prm_manual_no_update")
            .contains("trg_prm_manual_no_delete")
            .contains("promotion:manual:authorize")
            .contains("promotion:manual:approve")
            .contains("promotion:manual:audit:read")
            .doesNotContain("prm_transaction_snapshot")
            .doesNotContain("prm_transaction_allocation")
            .doesNotContain("prm_refund_allocation_ledger")
            .doesNotContain("update ord_").doesNotContain("update inv_").doesNotContain("update pay_");
    }

    @Test
    void prm3MigrationAddsOnlyThreeImmutableAllocationFactsAndThreePermissions() throws Exception {
        String sql = resource("/db/migration/V202608170023__gate5a_transaction_allocation.sql");
        assertThat(count(sql, "create table prm_")).isEqualTo(3);
        assertThat(sql).contains("create table prm_transaction_snapshot")
            .contains("create table prm_transaction_allocation")
            .contains("create table prm_refund_allocation_ledger")
            .contains("gross_amount_minor=discount_amount_minor+payable_amount_minor")
            .contains("cumulative_gross_amount_minor=cumulative_discount_amount_minor+cumulative_payable_amount_minor")
            .contains("trg_prm_snapshot_no_update").contains("trg_prm_allocation_no_update")
            .contains("trg_prm_refund_alloc_no_update").contains("promotion:snapshot:freeze")
            .contains("promotion:refund:allocate").contains("promotion:snapshot:read")
            .doesNotContain("update ord_").doesNotContain("update inv_").doesNotContain("update pay_");
    }

    @Test
    void memberBenefitMigrationAddsOneNoPiiImmutableQuoteBinding() throws Exception {
        String sql = resource("/db/migration/V202608230078__gate7d_member_benefit_promotion.sql");
        assertThat(count(sql, "create table prm_")).isEqualTo(1);
        assertThat(sql).contains("create table prm_quote_member_benefit")
            .contains("tenant_id varchar(20) not null")
            .contains("rights_digest char(64)")
            .contains("trg_prm_quote_member_benefit_no_update")
            .contains("trg_prm_quote_member_benefit_no_delete")
            .doesNotContain("member_name").doesNotContain("mobile").doesNotContain("identity_ciphertext")
            .doesNotContain("float").doesNotContain("double")
            .doesNotContain("update ord_").doesNotContain("update inv_").doesNotContain("update pay_");
    }

    @Test
    void memberBenefitPackageMigrationIsTenantScopedContinuousAndImmutable() throws Exception {
        String sql = resource("/db/migration/V202608230080__gate7d_member_benefit_package.sql");
        assertThat(count(sql, "create table prm_")).isEqualTo(1);
        assertThat(sql).contains("create table prm_member_benefit_package")
            .contains("tenant_id varchar(20) not null")
            .contains("unique key uk_prm_mbp_tenant_store_version")
            .contains("package_version = previous_version + 1")
            .contains("payload_sha256 char(64)")
            .contains("trg_prm_mbp_no_update")
            .contains("trg_prm_mbp_no_delete")
            .doesNotContain("member_name").doesNotContain("mobile").doesNotContain("identity_ciphertext")
            .doesNotContain("float").doesNotContain("double")
            .doesNotContain("update mbr_").doesNotContain("update prc_").doesNotContain("update ord_");
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream(name)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }

    private long count(String value, String token) {
        return Pattern.compile(Pattern.quote(token)).matcher(value).results().count();
    }
}
