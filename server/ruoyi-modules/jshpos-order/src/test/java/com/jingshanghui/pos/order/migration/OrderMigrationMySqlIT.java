package com.jingshanghui.pos.order.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 由 Gate 2 mysql-migration Job 在干净 MySQL 8.4 服务中显式执行。 */
class OrderMigrationMySqlIT {

    private final String url = required("GATE2_MYSQL_JDBC_URL");
    private final String username = required("GATE2_MYSQL_USERNAME");
    private final String password = required("GATE2_MYSQL_PASSWORD");

    @Test
    void migratesAllEighteenVersionsAndEnforcesTenantCashAndAppendOnlyConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(18);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesAndPermissions();
        assertTwoTenantCashConstraints();
        assertPromotedOrderConstraints();
        assertOrderFinalityAndDispositionConstraints();
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sys_menu (
                  menu_id BIGINT NOT NULL PRIMARY KEY,menu_name VARCHAR(50) NOT NULL,parent_id BIGINT DEFAULT 0,
                  order_num INT DEFAULT 0,path VARCHAR(200) DEFAULT '',component VARCHAR(255),query_param VARCHAR(255),
                  route_name VARCHAR(100),is_frame INT DEFAULT 1,is_cache INT DEFAULT 0,menu_type CHAR(1) DEFAULT '',
                  visible CHAR(1) DEFAULT '0',status CHAR(1) DEFAULT '0',perms VARCHAR(100),icon VARCHAR(100) DEFAULT '#',
                  create_dept BIGINT,create_by BIGINT,create_time DATETIME,update_by BIGINT,update_time DATETIME,
                  remark VARCHAR(500) DEFAULT '') ENGINE=InnoDB
                """);
        }
    }

    private void assertTablesAndPermissions() throws SQLException {
        Set<String> tables = Set.of("shf_shift", "shf_shift_approval", "ord_sales_order", "ord_order_line",
            "ord_state_history", "ord_cash_payment", "shf_cash_ledger", "ord_print_job",
            "ord_event_outbox", "ord_idempotency", "ord_audit_event", "ord_promotion_binding",
            "ord_cash_refund", "shf_cash_movement", "shf_drawer_event",
            "ord_receipt_document", "ord_print_request", "ord_order_disposition",
            "ord_order_finality_guard");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200200 AND 9200207")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(7);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id IN (9200208,9200209)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id IN (9200210,9200211)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id IN (9200212,9200213)")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'syn\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    /** 数据库唯一键必须串行化同一订单的取消/成交竞争，处置与仲裁事实均只追加。 */
    private void assertOrderFinalityAndDispositionConstraints() throws SQLException {
        String order = "01K2A000000000000000000201";
        String source = "01K2A000000000000000000202";
        String disposition = "01K2A000000000000000000203";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ord_order_finality_guard(tenant_id,order_id,finality_type,source_id,request_sha256,created_at) VALUES('TENANT_A','" + order + "','CANCELLED','" + source + "',REPEAT('a',64),UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_order_finality_guard(tenant_id,order_id,finality_type,source_id,request_sha256,created_at) VALUES('TENANT_A','" + order + "','COMPLETED','01K2A000000000000000000204',REPEAT('b',64),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ord_order_finality_guard SET finality_type='COMPLETED' WHERE tenant_id='TENANT_A' AND order_id='" + order + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ord_order_finality_guard WHERE tenant_id='TENANT_A' AND order_id='" + order + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");

            statement.executeUpdate("INSERT INTO ord_order_disposition(disposition_id,tenant_id,source_event_id,order_id,store_id,terminal_id,shift_id,actor_user_id,business_date,disposition_type,from_status,effective_status,reason_code,reason_text,order_snapshot_sha256,request_sha256,order_aggregate_version,occurred_at) VALUES('" + disposition + "','TENANT_A','01K2A000000000000000000205','" + order + "',1101,'01K2A000000000000000000011','01K2A000000000000000000021',101,'2026-08-21','CANCEL_BEFORE_COMPLETION','DRAFT','CANCELLED','CUSTOMER_CANCEL','虚构取消',REPEAT('c',64),REPEAT('d',64),2,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_order_disposition(disposition_id,tenant_id,source_event_id,order_id,store_id,terminal_id,shift_id,actor_user_id,business_date,disposition_type,from_status,effective_status,reason_code,reason_text,order_snapshot_sha256,request_sha256,order_aggregate_version,occurred_at) VALUES('01K2A000000000000000000206','TENANT_A','01K2A000000000000000000207','" + order + "',1101,'01K2A000000000000000000011','01K2A000000000000000000021',101,'2026-08-21','CANCEL_BEFORE_COMPLETION','DRAFT','CANCELLED','CUSTOMER_CANCEL','重复取消',REPEAT('c',64),REPEAT('e',64),2,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ord_order_disposition SET reason_text='篡改' WHERE disposition_id='" + disposition + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    private void assertPromotedOrderConstraints() throws SQLException {
        String order = "01K2A000000000000000000131";
        String line = "01K2A000000000000000000141";
        String binding = "01K2A000000000000000000151";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES('" + order + "','TENANT_A','A-PROMO-1',1101,'01K2A000000000000000000011','01K2A000000000000000000021',101,'2026-08-16','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',1299,200,1,1100,1100,1,1,'CONVENIENCE.1',2,JSON_OBJECT(),REPEAT('c',64),'promo-order-key-001',REPEAT('d',64),UTC_TIMESTAMP(3),4)");
            statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES('" + line + "','TENANT_A','" + order + "',1,701,'A-SKU','A',301,'PCS',1.000000,1299,1299,200,1,1100,'TENANT_BASE')");
            statement.executeUpdate("INSERT INTO ord_promotion_binding(binding_id,tenant_id,order_id,promotion_snapshot_id,quote_id,store_id,terminal_id,business_date,quote_fingerprint,settlement_fingerprint,package_version,promotion_snapshot_sha256,order_snapshot_sha256,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,correlation_id,created_at) VALUES('" + binding + "','TENANT_A','" + order + "','01K2A000000000000000000161','01K2A000000000000000000171',1101,'01K2A000000000000000000011','2026-08-16',REPEAT('1',64),REPEAT('2',64),1,REPEAT('3',64),REPEAT('4',64),1299,200,1,1100,'01K2A000000000000000000181',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ord_promotion_binding SET package_version=2 WHERE tenant_id='TENANT_A' AND binding_id='" + binding + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ord_promotion_binding WHERE tenant_id='TENANT_A' AND binding_id='" + binding + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_promotion_binding(binding_id,tenant_id,order_id,promotion_snapshot_id,quote_id,store_id,terminal_id,business_date,quote_fingerprint,settlement_fingerprint,package_version,promotion_snapshot_sha256,order_snapshot_sha256,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,correlation_id,created_at) VALUES('01K2B000000000000000000151','TENANT_B','" + order + "','01K2B000000000000000000161','01K2B000000000000000000171',2101,'01K2B000000000000000000011','2026-08-16',REPEAT('1',64),REPEAT('2',64),1,REPEAT('3',64),REPEAT('4',64),1299,200,1,1100,'01K2B000000000000000000181',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES('01K2A000000000000000000199','TENANT_A','BAD-AMOUNT',1101,'01K2A000000000000000000011','01K2A000000000000000000021',101,'2026-08-16','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',100,10,0,100,100,1,1,'CONVENIENCE.1',2,JSON_OBJECT(),REPEAT('c',64),'promo-order-bad-001',REPEAT('d',64),UTC_TIMESTAMP(3),4)"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void assertTwoTenantCashConstraints() throws SQLException {
        String shiftA = "01K2A000000000000000000021";
        String shiftB = "01K2B000000000000000000021";
        String orderA = "01K2A000000000000000000031";
        String paymentA = "01K2A000000000000000000061";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
            statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
            statement.executeUpdate("INSERT INTO cat_category(category_id,tenant_id,category_code,category_name) VALUES(101,'TENANT_A','FOOD','Food'),(201,'TENANT_B','FOOD','Food')");
            statement.executeUpdate("INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name) VALUES(301,'TENANT_A','PCS','Piece'),(401,'TENANT_B','PCS','Piece')");
            statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES(501,'TENANT_A','A-SPU','A',101,'ACTIVE'),(601,'TENANT_B','B-SPU','B',201,'ACTIVE')");
            statement.executeUpdate("INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,status) VALUES(701,'TENANT_A',501,'A-SKU','A','STANDARD','ACTIVE'),(801,'TENANT_B',601,'B-SKU','B','STANDARD','ACTIVE')");
            statement.executeUpdate("INSERT INTO shf_shift(shift_id,tenant_id,store_id,terminal_id,cashier_user_id,cashier_name_snapshot,business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,opened_at) VALUES('" + shiftA + "','TENANT_A',1101,'01K2A000000000000000000011',101,'Alice','2026-08-16','Asia/Shanghai',1,'OPEN','CNY',0,0,UTC_TIMESTAMP(3)),('" + shiftB + "','TENANT_B',2101,'01K2B000000000000000000011',201,'Bob','2026-08-16','Asia/Shanghai',1,'OPEN','CNY',0,0,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES('01K2A000000000000000000099','TENANT_A','BAD',1101,'01K2A000000000000000000011','" + shiftB + "',101,'2026-08-16','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',100,0,0,100,100,1,1,'CONVENIENCE.1',1,JSON_OBJECT(),REPEAT('a',64),'bad-key-0000000001',REPEAT('b',64),UTC_TIMESTAMP(3),4)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES('" + orderA + "','TENANT_A','A-T1-1',1101,'01K2A000000000000000000011','" + shiftA + "',101,'2026-08-16','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',1299,0,0,1299,1299,1,1,'CONVENIENCE.1',1,JSON_OBJECT(),REPEAT('a',64),'cash-order-key-0001',REPEAT('b',64),UTC_TIMESTAMP(3),4)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ord_sales_order SET receivable_amount_minor=1 WHERE tenant_id='TENANT_A' AND order_id='" + orderA + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES('01K2A000000000000000000041','TENANT_A','" + orderA + "',1,701,'A-SKU','A',301,'PCS',1.000000,1299,1299,0,0,1299,'TENANT_BASE')");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source,measurement_template_id) VALUES('01K2A000000000000000000043','TENANT_A','" + orderA + "',3,701,'A-SKU','A',301,'KG',0.250000,1990,498,0,0,498,'TENANT_BASE',12001)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,barcode_value,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source,measurement_template_id,measurement_template_version,measurement_template_sha256,measurement_parse_sha256,measurement_snapshot_json) VALUES('01K2A000000000000000000044','TENANT_A','" + orderA + "',4,701,'A-SKU','2200123002507','A',301,'KG',0.250000,1990,498,0,0,498,'TENANT_BASE',12001,1,REPEAT('a',64),REPEAT('b',64),JSON_OBJECT('rawBarcode','2200123002507'))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ord_order_line SET measurement_parse_sha256=REPEAT('c',64) WHERE line_id='01K2A000000000000000000044'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES('01K2A000000000000000000042','TENANT_A','" + orderA + "',2,801,'B-SKU','B',301,'PCS',1.000000,100,100,0,0,100,'TENANT_BASE')"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO ord_cash_payment(cash_payment_id,tenant_id,order_id,shift_id,status,currency,receivable_amount_minor,tendered_amount_minor,change_amount_minor,net_amount_minor,occurred_at) VALUES('" + paymentA + "','TENANT_A','" + orderA + "','" + shiftA + "','SUCCEEDED','CNY',1299,2000,700,1299,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO ord_cash_payment(cash_payment_id,tenant_id,order_id,shift_id,status,currency,receivable_amount_minor,tendered_amount_minor,change_amount_minor,net_amount_minor,occurred_at) VALUES('" + paymentA + "','TENANT_A','" + orderA + "','" + shiftA + "','SUCCEEDED','CNY',1299,2000,701,1299,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO shf_cash_ledger(cash_ledger_id,tenant_id,shift_id,order_id,cash_payment_id,movement_type,signed_amount_minor,currency,business_date,occurred_at) VALUES('01K2A000000000000000000071','TENANT_A','" + shiftA + "','" + orderA + "','" + paymentA + "','SALE_RECEIPT',1299,'CNY','2026-08-16',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO shf_cash_ledger(cash_ledger_id,tenant_id,shift_id,order_id,cash_payment_id,movement_type,signed_amount_minor,currency,business_date,occurred_at) VALUES('01K2A000000000000000000072','TENANT_A','" + shiftA + "','" + orderA + "','" + paymentA + "','SALE_RECEIPT',1299,'CNY','2026-08-16',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO ord_cash_refund(cash_refund_id,tenant_id,refund_id,order_id,original_cash_payment_id,refund_shift_id,store_id,terminal_id,business_date,status,amount_minor,currency,request_sha256,correlation_id,actor_user_id,occurred_at) VALUES('01K2A000000000000000000073','TENANT_A','01K2A000000000000000000083','" + orderA + "','" + paymentA + "','" + shiftA + "',1101,'01K2A000000000000000000011','2026-08-16','SUCCEEDED',300,'CNY',REPEAT('c',64),'01K2A000000000000000000093',101,UTC_TIMESTAMP(3)),('01K2A000000000000000000074','TENANT_A','01K2A000000000000000000084','" + orderA + "','" + paymentA + "','" + shiftA + "',1101,'01K2A000000000000000000011','2026-08-16','SUCCEEDED',200,'CNY',REPEAT('d',64),'01K2A000000000000000000094',101,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO shf_cash_ledger(cash_ledger_id,tenant_id,shift_id,order_id,cash_payment_id,cash_refund_id,movement_type,signed_amount_minor,currency,business_date,occurred_at) VALUES('01K2A000000000000000000075','TENANT_A','" + shiftA + "','" + orderA + "','" + paymentA + "','01K2A000000000000000000073','CASH_REFUND',-300,'CNY','2026-08-16',UTC_TIMESTAMP(3)),('01K2A000000000000000000076','TENANT_A','" + shiftA + "','" + orderA + "','" + paymentA + "','01K2A000000000000000000074','CASH_REFUND',-200,'CNY','2026-08-16',UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO shf_shift_approval(approval_id,tenant_id,shift_id,approver_user_id,reason_code,reason_text,theoretical_cash_minor,actual_cash_minor,difference_minor,expected_shift_version,status,approved_at) VALUES('01K2A000000000000000000091','TENANT_A','" + shiftA + "',102,'COUNT_CONFIRMED','Synthetic supervisor',1299,1300,1,1,'APPROVED',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE shf_shift_approval SET reason_text='tampered' WHERE approval_id='01K2A000000000000000000091'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE shf_cash_ledger SET signed_amount_minor=1 WHERE cash_ledger_id='01K2A000000000000000000071'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ord_order_line WHERE line_id='01K2A000000000000000000041'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
