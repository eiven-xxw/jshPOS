package com.jingshanghui.pos.release.performance;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Gate 10A SQL 基线专用的确定性合成数据夹具。
 *
 * <p>夹具只运行在 CI 临时 MySQL；通过关闭当前会话外键检查避免复制全部 Owner 父事实，
 * 但仍接受正式 CHECK、唯一键和列类型校验。夹具不属于生产初始化数据，也不得迁入正式目录。</p>
 */
final class SqlBaselineFixture {
    static final List<String> PRIMARY_TABLES = List.of(
        "inv_lot_identity", "inv_lot_balance", "inv_lot_expiry_projection",
        "prm_rule_version", "prm_rule_scope", "prm_rule_benefit", "prm_quote_line",
        "rpt_sales_daily", "rpt_inventory_cost_daily", "rpt_payment_reconciliation",
        "pay_payment_intent", "pay_payment_attempt", "pay_refund",
        "pur_purchase_order_line", "inv_transfer_line", "mbr_points_lot"
    );

    private static final int BATCH_SIZE = 500;
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 8, 1);
    private static final LocalDateTime FIRST_TIME = LocalDateTime.of(2026, 8, 1, 0, 0);

    private SqlBaselineFixture() {
    }

    /** 将所有关键事实从 fromExclusive 扩容到 targetRows，保持80/20租户与60%热点门店分布。 */
    static void extendAll(Connection connection, int fromExclusive, int targetRows) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            statement.execute("SET UNIQUE_CHECKS=1");
        }
        try (
            PreparedStatement lotIdentity = connection.prepareStatement("""
                INSERT INTO inv_lot_identity(lot_id,tenant_id,store_id,warehouse_id,sku_id,base_unit_id,
                  supplier_lot_code,internal_lot_code,production_date,received_date,expiry_date,policy_version_id,
                  near_expiry_days,content_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement lotBalance = connection.prepareStatement("""
                INSERT INTO inv_lot_balance(tenant_id,lot_id,on_hand_quantity,last_ledger_sequence,record_version,updated_at)
                VALUES(?,?,?,?,?,?)
                """);
            PreparedStatement lotExpiry = connection.prepareStatement("""
                INSERT INTO inv_lot_expiry_projection(tenant_id,lot_id,expiry_status,as_of_business_date,
                  near_expiry_days,on_hand_quantity,last_ledger_sequence,updated_at) VALUES(?,?,?,?,?,?,?,?)
                """);
            PreparedStatement ruleVersion = connection.prepareStatement("""
                INSERT INTO prm_rule_version(rule_version_id,tenant_id,rule_id,version_no,rule_type,priority,
                  stack_mode,exclusive_group,effective_from,effective_to,state,content_sha256,engine_version,
                  approved_by,approved_at,published_at,paused_at,version,created_by,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement ruleScope = connection.prepareStatement("""
                INSERT INTO prm_rule_scope(scope_id,tenant_id,rule_version_id,dimension_type,dimension_value,created_at)
                VALUES(?,?,?,?,?,?)
                """);
            PreparedStatement ruleBenefit = connection.prepareStatement("""
                INSERT INTO prm_rule_benefit(benefit_id,tenant_id,rule_version_id,amount_minor,discount_rate,
                  nth_item_value,threshold_minor,threshold_quantity,bundle_price_minor,bundle_components_json,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement quoteLine = connection.prepareStatement("""
                INSERT INTO prm_quote_line(quote_line_id,tenant_id,quote_id,source_line_id,line_no,sku_id,
                  quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,payable_amount_minor,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement sales = connection.prepareStatement("""
                INSERT INTO rpt_sales_daily(tenant_id,projection_version,business_date,org_id,store_id,terminal_id,
                  cashier_id,currency,order_count,cancelled_order_count,return_count,gross_minor,discount_minor,
                  surcharge_minor,receivable_minor,refund_minor,cash_received_minor,cash_refunded_minor,
                  shift_difference_minor,promotion_snapshot_count,projection_status,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement inventory = connection.prepareStatement("""
                INSERT INTO rpt_inventory_cost_daily(tenant_id,projection_version,business_date,org_id,store_id,
                  warehouse_id,sku_id,currency,on_hand_delta,available_delta,reserved_delta,ledger_quantity_delta,
                  purchase_quantity_delta,stocktake_quantity_delta,transfer_quantity_delta,inventory_value_delta_minor,
                  cogs_delta_minor,purchase_cost_delta_minor,stocktake_cost_delta_minor,transfer_cost_delta_minor,
                  projection_status,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement reconciliation = connection.prepareStatement("""
                INSERT INTO rpt_payment_reconciliation(reconciliation_id,tenant_id,reconciliation_key,fact_type,
                  source_event_id,bill_entry_id,business_date,org_id,store_id,terminal_id,currency,internal_amount_minor,
                  bill_amount_minor,internal_status,bill_status,internal_business_date,bill_business_date,difference_type,
                  handling_state,handler_id,source_content_sha256,bill_content_sha256,version,detected_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement payment = connection.prepareStatement("""
                INSERT INTO pay_payment_intent(payment_id,tenant_id,order_id,store_id,terminal_id,status,amount_minor,
                  currency,succeeded_refund_minor,record_version,occurred_at,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement attempt = connection.prepareStatement("""
                INSERT INTO pay_payment_attempt(attempt_id,tenant_id,payment_id,provider_code,provider_request_no,
                  provider_transaction_no,status,amount_minor,currency,record_version,occurred_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement refund = connection.prepareStatement("""
                INSERT INTO pay_refund(refund_id,tenant_id,payment_id,order_id,store_id,status,amount_minor,currency,
                  reason_code,requester_user_id,approver_user_id,provider_code,provider_request_no,provider_refund_no,
                  record_version,occurred_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement purchaseLine = connection.prepareStatement("""
                INSERT INTO pur_purchase_order_line(order_line_id,tenant_id,order_id,sku_id,purchase_unit_id,
                  conversion_numerator,conversion_denominator,ordered_quantity,received_quantity,unit_price_minor,
                  tax_rate_bps,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement transferLine = connection.prepareStatement("""
                INSERT INTO inv_transfer_line(transfer_line_id,tenant_id,transfer_id,sku_id,requested_unit_id,
                  conversion_numerator,conversion_denominator,input_quantity,base_unit_id,requested_quantity,
                  dispatched_quantity,received_quantity,difference_quantity,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """);
            PreparedStatement pointsLot = connection.prepareStatement("""
                INSERT INTO mbr_points_lot(lot_id,tenant_id,member_id,earn_ledger_id,original_points,
                  available_points,frozen_points,policy_version,expires_at,occurred_at,version,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """)
        ) {
            List<PreparedStatement> batches = List.of(lotIdentity, lotBalance, lotExpiry, ruleVersion, ruleScope,
                ruleBenefit, quoteLine, sales, inventory, reconciliation, payment, attempt, refund,
                purchaseLine, transferLine, pointsLot);
            for (int row = fromExclusive; row < targetRows; row++) {
                addLot(row, lotIdentity, lotBalance, lotExpiry);
                addPromotion(row, ruleVersion, ruleScope, ruleBenefit, quoteLine);
                addReporting(row, sales, inventory, reconciliation);
                addPayment(row, payment, attempt, refund);
                addDocuments(row, purchaseLine, transferLine);
                addMember(row, pointsLot);
                if ((row + 1) % BATCH_SIZE == 0) {
                    executeBatches(batches);
                    connection.commit();
                }
            }
            executeBatches(batches);
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            connection.setAutoCommit(true);
        }
    }

    /** 1m 趋势仅扩容获批的销售日报事实，不扩大其他表。 */
    static void extendSalesTrend(Connection connection, int fromExclusive, int targetRows) throws SQLException {
        connection.setAutoCommit(false);
        try (PreparedStatement sales = connection.prepareStatement("""
            INSERT INTO rpt_sales_daily(tenant_id,projection_version,business_date,org_id,store_id,terminal_id,
              cashier_id,currency,order_count,cancelled_order_count,return_count,gross_minor,discount_minor,
              surcharge_minor,receivable_minor,refund_minor,cash_received_minor,cash_refunded_minor,
              shift_difference_minor,promotion_snapshot_count,projection_status,updated_at)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            for (int row = fromExclusive; row < targetRows; row++) {
                addSales(row, sales);
                if ((row + 1) % BATCH_SIZE == 0) {
                    sales.executeBatch();
                    connection.commit();
                }
            }
            sales.executeBatch();
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static void analyze(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String table : PRIMARY_TABLES) {
                statement.execute("ANALYZE TABLE " + table);
            }
            statement.execute("ANALYZE TABLE cat_lot_policy_version");
        }
    }

    static void seedLotPolicies(Connection connection, List<Long> skuIds) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT IGNORE INTO cat_lot_policy_version(tenant_id,policy_version_id,store_id,sku_id,enabled,
              expiry_basis,shelf_life_days,near_expiry_days,industry,template_version_id,effective_from,
              content_sha256,state,published_by,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """)) {
            for (Long skuId : skuIds.stream().distinct().toList()) {
                String identity = SqlBaselineQueries.id(20_000_000_000L + skuId);
                set(insert, SqlBaselineQueries.TENANT_B, identity, SqlBaselineQueries.HOT_STORE, skuId, true,
                    "EXPLICIT_EXPIRY_DATE", null, 7, "COMMUNITY_SUPERMARKET", 1L,
                    Timestamp.valueOf("2026-08-01 00:00:00"), digest("policy-" + skuId), "PUBLISHED", 1L,
                    Timestamp.valueOf("2026-08-01 00:00:00"));
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static void addLot(int row, PreparedStatement identity, PreparedStatement balance,
                               PreparedStatement expiry) throws SQLException {
        String tenant = tenant(row);
        long store = store(row);
        long sku = hotSku(row) ? SqlBaselineQueries.HOT_SKU : 10002L + row % 10_000L;
        String lotId = SqlBaselineQueries.id(1_000_000_000L + row);
        String warehouse = warehouse(store);
        LocalDate received = FIRST_DAY.plusDays(row % 20L);
        LocalDate expires = received.plusDays(30L + row % 90L);
        String policy = SqlBaselineQueries.id(20_000_000_000L + sku);
        set(identity, lotId, tenant, store, warehouse, sku, 1L, "SUP-" + row, "LOT-" + row,
            row % 10 == 0 ? null : Date.valueOf(received.minusDays(7)), Date.valueOf(received), Date.valueOf(expires),
            policy, 7, digest("lot-" + row), Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        identity.addBatch();
        BigDecimal onHand = row % 20 == 0 ? BigDecimal.ZERO : new BigDecimal("10.000000");
        set(balance, tenant, lotId, onHand, 1L, 0L, Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        balance.addBatch();
        String status = expires.isBefore(SqlBaselineQueries.BUSINESS_DATE) ? "EXPIRED"
            : expires.isBefore(SqlBaselineQueries.BUSINESS_DATE.plusDays(8)) ? "NEAR_EXPIRY" : "AVAILABLE";
        set(expiry, tenant, lotId, status, Date.valueOf(SqlBaselineQueries.BUSINESS_DATE), 7, onHand, 1L,
            Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        expiry.addBatch();
    }

    private static void addPromotion(int row, PreparedStatement version, PreparedStatement scope,
                                     PreparedStatement benefit, PreparedStatement quoteLine) throws SQLException {
        String tenant = tenant(row);
        String versionId = SqlBaselineQueries.id(16_000_000_000L + row);
        set(version, versionId, tenant, SqlBaselineQueries.id(17_000_000_000L + row), 1, "AMOUNT_OFF",
            row % 100, "EXCLUSIVE", null, Timestamp.valueOf("2026-08-01 00:00:00"),
            Timestamp.valueOf("2026-09-01 00:00:00"), row % 5 == 0 ? "PUBLISHED" : "PAUSED",
            digest("rule-" + row), "v1", 2L, Timestamp.valueOf("2026-08-01 00:00:00"),
            Timestamp.valueOf("2026-08-01 00:00:00"), null, 1, 1L,
            Timestamp.valueOf("2026-08-01 00:00:00"), Timestamp.valueOf("2026-08-01 00:00:00"));
        version.addBatch();
        set(scope, SqlBaselineQueries.id(18_000_000_000L + row), tenant, versionId, "STORE",
            String.valueOf(store(row)), Timestamp.valueOf("2026-08-01 00:00:00"));
        scope.addBatch();
        set(benefit, SqlBaselineQueries.id(19_000_000_000L + row), tenant, versionId, 100L, null,
            null, 1000L, new BigDecimal("1.000000"), null, null,
            Timestamp.valueOf("2026-08-01 00:00:00"));
        benefit.addBatch();
        long group = row / 500L;
        set(quoteLine, SqlBaselineQueries.id(3_000_000_000L + row), tenant,
            SqlBaselineQueries.id(4_000_000_000L + group), SqlBaselineQueries.id(4_500_000_000L + row),
            row % 500 + 1, 1_000_000L + row % 500L, new BigDecimal("1.000000"), 1000L,
            1000L, 100L, 900L, Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        quoteLine.addBatch();
    }

    private static void addReporting(int row, PreparedStatement sales, PreparedStatement inventory,
                                     PreparedStatement reconciliation) throws SQLException {
        addSales(row, sales);
        String tenant = tenant(row);
        long store = store(row);
        LocalDate day = FIRST_DAY.plusDays(row % 31L);
        String warehouse = warehouse(store);
        set(inventory, tenant, "v1", Date.valueOf(day), 10L, store, warehouse, 2_000_000L + row,
            "CNY", decimal("1"), decimal("1"), decimal("0"), decimal("1"), decimal("0"),
            decimal("0"), decimal("0"), decimal("100"), decimal("20"), decimal("100"),
            decimal("0"), decimal("0"), "CURRENT", Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        inventory.addBatch();
        boolean difference = row % 20 == 0;
        String fact = row % 2 == 0 ? "PAYMENT" : "REFUND";
        String id = SqlBaselineQueries.id(21_000_000_000L + row);
        set(reconciliation, id, tenant, SqlBaselineQueries.id(22_000_000_000L + row), fact,
            SqlBaselineQueries.id(23_000_000_000L + row), SqlBaselineQueries.id(24_000_000_000L + row),
            Date.valueOf(day), 10L, store, "TERM-" + row, "CNY", 1000L,
            difference ? 900L : 1000L, "SUCCEEDED", "SUCCEEDED", Date.valueOf(day), Date.valueOf(day),
            difference ? "AMOUNT_MISMATCH" : "MATCHED", difference ? "OPEN" : "MATCHED", null,
            digest("internal-" + row), digest("bill-" + row), 0,
            Timestamp.valueOf(FIRST_TIME.plusSeconds(row)), Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        reconciliation.addBatch();
    }

    private static void addSales(int row, PreparedStatement sales) throws SQLException {
        String tenant = tenant(row);
        long store = store(row);
        set(sales, tenant, "v1", Date.valueOf(FIRST_DAY.plusDays(row % 31L)), 10L, store,
            "TERM-" + row, 100L + row % 1000L, "CNY", 1L, 0L, 0L,
            1000L, 100L, 0L, 900L, 0L, 900L, 0L, 0L, 1L, "CURRENT",
            Timestamp.valueOf(FIRST_TIME.plusSeconds(row)));
        sales.addBatch();
    }

    private static void addPayment(int row, PreparedStatement payment, PreparedStatement attempt,
                                   PreparedStatement refund) throws SQLException {
        String tenant = tenant(row);
        long store = store(row);
        String paymentId = SqlBaselineQueries.id(5_000_000_000L + row);
        String orderId = SqlBaselineQueries.id(8_000_000_000L + row);
        Timestamp occurred = Timestamp.valueOf(FIRST_TIME.plusDays(row % 31L).plusSeconds(row % 86_400L));
        set(payment, paymentId, tenant, orderId, store, SqlBaselineQueries.id(25_000_000_000L + row),
            "SUCCEEDED", 1000L, "CNY", 100L, 1L, occurred, occurred, occurred);
        payment.addBatch();
        set(attempt, SqlBaselineQueries.id(6_000_000_000L + row), tenant, paymentId, "LAKALA",
            "REQ-" + row, "TXN-" + String.format("%010d", row), "SUCCEEDED", 1000L, "CNY", 1L,
            occurred, occurred);
        attempt.addBatch();
        set(refund, SqlBaselineQueries.id(7_000_000_000L + row), tenant, paymentId, orderId, store,
            "SUCCEEDED", 100L, "CNY", "CUSTOMER_RETURN", 101L, 102L, "LAKALA",
            "RREQ-" + row, "RFN-" + String.format("%010d", row), 1L, occurred, occurred);
        refund.addBatch();
    }

    private static void addDocuments(int row, PreparedStatement purchase, PreparedStatement transfer)
        throws SQLException {
        String tenant = tenant(row);
        long sku = 1_000_000L + row % 500L;
        Timestamp occurred = Timestamp.valueOf(FIRST_TIME.plusSeconds(row));
        set(purchase, SqlBaselineQueries.id(9_000_000_000L + row), tenant,
            SqlBaselineQueries.id(10_000_000_000L + row / 500L), sku, 1L, 1L, 1L,
            decimal("10"), decimal("0"), 1000L, 0, occurred, occurred);
        purchase.addBatch();
        set(transfer, SqlBaselineQueries.id(11_000_000_000L + row), tenant,
            SqlBaselineQueries.id(12_000_000_000L + row / 500L), sku, 1L, 1L, 1L,
            decimal("10"), 1L, decimal("10"), decimal("0"), decimal("0"), decimal("0"),
            occurred, occurred);
        transfer.addBatch();
    }

    private static void addMember(int row, PreparedStatement pointsLot) throws SQLException {
        String tenant = tenant(row);
        String member = hotSku(row) ? SqlBaselineQueries.id(14_000_000_000L)
            : SqlBaselineQueries.id(14_000_000_001L + row % 10_000L);
        Timestamp occurred = Timestamp.valueOf(FIRST_TIME.plusSeconds(row));
        Timestamp expires = row % 10 == 0 ? null : Timestamp.valueOf("2027-08-01 00:00:00");
        set(pointsLot, SqlBaselineQueries.id(13_000_000_000L + row), tenant, member,
            SqlBaselineQueries.id(15_000_000_000L + row), decimal("100"), decimal("90"),
            decimal("10"), "points-v1", expires, occurred, 0, occurred);
        pointsLot.addBatch();
    }

    private static void executeBatches(List<PreparedStatement> statements) throws SQLException {
        for (PreparedStatement statement : statements) {
            statement.executeBatch();
        }
    }

    private static void set(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static String tenant(int row) {
        return row % 5 == 4 ? SqlBaselineQueries.TENANT_B : SqlBaselineQueries.TENANT_A;
    }

    private static long store(int row) {
        return (row / 5) % 5 < 3 ? SqlBaselineQueries.HOT_STORE : 1002L + (row / 25) % 9L;
    }

    private static boolean hotSku(int row) {
        return (row / 25) % 5 == 0;
    }

    private static String warehouse(long store) {
        return SqlBaselineQueries.id(2_099_000L + store);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(6);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
