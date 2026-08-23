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

    @Test
    void gate7bMigrationKeepsCashAndDrawerFactsAppendOnlyAndDeviceBlocked() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608210052__gate7b_shift_cash_drawer.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("create table shf_cash_movement", "create table shf_drawer_event",
            "shift cash movement is append-only", "drawer event is append-only", "blocked_external",
            "pos:shift:cash-manage", "pos:drawer:no-sale", "可信租户标识", "最小货币单位带符号金额");
        assertThat(sql).doesNotContain("float", "double", "device sdk", "http://", "https://");
    }

    @Test
    void gate7bReceiptMigrationFreezesDocumentsAndKeepsRealPrinterBlocked() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608210053__gate7b_receipt_reprint.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("create table ord_receipt_document", "create table ord_print_request",
            "receipt document is immutable", "print request is append-only", "blocked_external",
            "pos:print:preview", "pos:print:reprint", "content_sha256", "authorization_ref");
        assertThat(sql).doesNotContain("float", "double", "printed'", "device sdk", "http://", "https://");
    }

    @Test
    void gate7bTenderCashAndOrderSettlementAreAppendOnlyOwnerFacts() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608220058__gate7b_cash_tender.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("create table ord_tender_settlement", "create table ord_cash_tender",
            "原订单快照不更新", "ord_tender_settlement is immutable", "ord_cash_tender is immutable",
            "tender_receipt", "cash_refund' and signed_amount_minor<0 and cash_payment_id is not null",
            "foreign key (tenant_id,order_id)", "foreign key (tenant_id,shift_id)", "currency='cny'");
        assertThat(sql).doesNotContain("update ord_sales_order", "float", " double", "http://", "https://");
    }

    @Test
    void gate7cMeasurementSnapshotUsesForwardOnlyExactAndHashedColumns() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608220060__gate7c_order_measurement_snapshot.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("alter table ord_order_line", "measurement_snapshot_json",
            "measurement_template_sha256", "measurement_parse_sha256", "ck_ord_line_measurement_shape",
            "tenant_id, measurement_template_id, measurement_template_version");
        assertThat(sql).doesNotContain("drop table", "update ord_order_line", "float", " double");
    }

    @Test
    void gate7dMemberBenefitBindingIsNoPiiAndImmutable() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(
            "/db/migration/V202608230079__gate7d_order_member_benefit_binding.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
        assertThat(sql).contains("create table ord_member_benefit_binding", "服务端可信租户标识",
            "promotion_binding_sha256", "trg_ord_member_benefit_no_update",
            "trg_ord_member_benefit_no_delete", "ord_member_benefit_binding is immutable");
        assertThat(sql).doesNotContain("member_name", "phone", "mobile", "id_card", "float", " double",
            "insert into prm_", "update prm_", "delete from prm_");
    }
}
