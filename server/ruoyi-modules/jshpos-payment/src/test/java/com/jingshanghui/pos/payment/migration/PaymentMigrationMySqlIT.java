package com.jingshanghui.pos.payment.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 由 Gate 3A mysql-migration Job 在干净 MySQL 8.4 与纯合成租户中显式执行。 */
class PaymentMigrationMySqlIT {

    private final String url = required("GATE3A_MYSQL_JDBC_URL");
    private final String username = required("GATE3A_MYSQL_USERNAME");
    private final String password = required("GATE3A_MYSQL_PASSWORD");

    @Test
    void migratesAllTenVersionsAndEnforcesTenantFundsAndImmutableEvidence() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesAndPermissions();
        assertTwoTenantPaymentAndRefundConstraints();
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
        Set<String> tables = Set.of("pay_payment_intent", "pay_payment_attempt", "pay_refund", "pay_refund_line",
            "pay_provider_observation", "pay_observation_dead_letter", "pay_state_history", "pay_idempotency",
            "pay_reconciliation_run", "pay_statement_entry", "pay_reconciliation_case", "pay_audit_event",
            "pay_event_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200300 AND 9200309")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(10);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'fake\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertTwoTenantPaymentAndRefundConstraints() throws SQLException {
        String shiftA = "01K2A000000000000000000021";
        String shiftB = "01K2B000000000000000000021";
        String orderA = "01K2A000000000000000000031";
        String orderB = "01K2B000000000000000000031";
        String lineA = "01K2A000000000000000000041";
        String paymentA = "01K2A000000000000000000051";
        String paymentB = "01K2B000000000000000000051";
        String attemptA = "01K2A000000000000000000061";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
            statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
            statement.executeUpdate("INSERT INTO cat_category(category_id,tenant_id,category_code,category_name) VALUES(101,'TENANT_A','FOOD','Food'),(201,'TENANT_B','FOOD','Food')");
            statement.executeUpdate("INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name) VALUES(301,'TENANT_A','PCS','Piece'),(401,'TENANT_B','PCS','Piece')");
            statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES(501,'TENANT_A','A-SPU','A',101,'ACTIVE'),(601,'TENANT_B','B-SPU','B',201,'ACTIVE')");
            statement.executeUpdate("INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,status) VALUES(701,'TENANT_A',501,'A-SKU','A','STANDARD','ACTIVE'),(801,'TENANT_B',601,'B-SKU','B','STANDARD','ACTIVE')");
            statement.executeUpdate("INSERT INTO shf_shift(shift_id,tenant_id,store_id,terminal_id,cashier_user_id,cashier_name_snapshot,business_date,store_timezone,config_version,status,currency,opening_cash_minor,theoretical_cash_minor,opened_at) VALUES('" + shiftA + "','TENANT_A',1101,'01K2A000000000000000000011',101,'Alice','2026-08-16','Asia/Shanghai',1,'OPEN','CNY',0,0,UTC_TIMESTAMP(3)),('" + shiftB + "','TENANT_B',2101,'01K2B000000000000000000011',201,'Bob','2026-08-16','Asia/Shanghai',1,'OPEN','CNY',0,0,UTC_TIMESTAMP(3))");
            insertOrder(statement, orderA, "TENANT_A", "A-T1-1", 1101, shiftA, 101, 701, 301);
            insertOrder(statement, orderB, "TENANT_B", "B-T1-1", 2101, shiftB, 201, 801, 401);
            statement.executeUpdate("INSERT INTO ord_order_line(line_id,tenant_id,order_id,line_no,sku_id,sku_code,product_name_snapshot,unit_id,unit_code,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,payable_amount_minor,price_source) VALUES('" + lineA + "','TENANT_A','" + orderA + "',1,701,'A-SKU','A',301,'PCS',1.000000,1299,1299,0,0,1299,'TENANT_BASE')");

            statement.executeUpdate("INSERT INTO pay_payment_intent(payment_id,tenant_id,order_id,store_id,terminal_id,status,amount_minor,currency,occurred_at) VALUES('" + paymentA + "','TENANT_A','" + orderA + "',1101,'01K2A000000000000000000011','PROCESSING',1299,'CNY',UTC_TIMESTAMP(3)),('" + paymentB + "','TENANT_B','" + orderB + "',2101,'01K2B000000000000000000011','PROCESSING',1299,'CNY',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pay_payment_intent(payment_id,tenant_id,order_id,store_id,terminal_id,status,amount_minor,currency,occurred_at) VALUES('01K2A000000000000000000052','TENANT_A','" + orderB + "',1101,'01K2A000000000000000000011','CREATED',1299,'CNY',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO pay_payment_attempt(attempt_id,tenant_id,payment_id,provider_code,provider_request_no,provider_transaction_no,status,amount_minor,currency,occurred_at) VALUES('" + attemptA + "','TENANT_A','" + paymentA + "','LAKALA','req-a-1','txn-shared','SUCCEEDED',1299,'CNY',UTC_TIMESTAMP(3)),('01K2B000000000000000000061','TENANT_B','" + paymentB + "','LAKALA','req-b-1','txn-shared','SUCCEEDED',1299,'CNY',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pay_payment_attempt(attempt_id,tenant_id,payment_id,provider_code,provider_request_no,provider_transaction_no,status,amount_minor,currency,occurred_at) VALUES('01K2A000000000000000000062','TENANT_A','" + paymentA + "','LAKALA','req-a-2','txn-shared','SUCCEEDED',1299,'CNY',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);

            statement.executeUpdate("INSERT INTO pay_provider_observation(observation_id,tenant_id,aggregate_type,aggregate_id,attempt_id,source,observed_status,provider_code,provider_request_no,provider_transaction_no,amount_minor,currency,payload_sha256,merge_result,observed_at) VALUES('01K2A000000000000000000071','TENANT_A','PAYMENT','" + paymentA + "','" + attemptA + "','QUERY','SUCCEEDED','LAKALA','req-a-1','txn-shared',1299,'CNY',REPEAT('a',64),'APPLIED',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE pay_provider_observation SET payload_sha256=REPEAT('b',64) WHERE observation_id='01K2A000000000000000000071'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("INSERT INTO pay_refund(refund_id,tenant_id,payment_id,order_id,store_id,status,amount_minor,currency,reason_code,requester_user_id,approver_user_id,provider_code,provider_request_no,record_version,occurred_at) VALUES('01K2A000000000000000000081','TENANT_A','" + paymentA + "','" + orderA + "',1101,'PROCESSING',500,'CNY','CUSTOMER_RETURN',101,102,'LAKALA','refund-a-1',1,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO pay_refund_line(refund_line_id,tenant_id,refund_id,order_line_id,quantity,amount_minor) VALUES('01K2A000000000000000000082','TENANT_A','01K2A000000000000000000081','" + lineA + "',0.500000,500)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE pay_refund_line SET quantity=1 WHERE refund_line_id='01K2A000000000000000000082'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("INSERT INTO pay_idempotency(idempotency_id,tenant_id,command_type,command_id,idempotency_key,request_sha256,aggregate_id,result_json,created_at) VALUES('01K2A000000000000000000091','TENANT_A','CREATE_PAYMENT_INTENT','01K2A000000000000000000092','idempotency:tenant-a:01',REPEAT('c',64),'" + paymentA + "',JSON_OBJECT('paymentId','" + paymentA + "'),UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM pay_idempotency WHERE idempotency_id='01K2A000000000000000000091'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        }
    }

    private void insertOrder(Statement statement, String orderId, String tenant, String localNo, long storeId,
                             String shiftId, long cashierId, long skuId, long unitId) throws SQLException {
        String tenantPrefix = tenant.endsWith("A") ? "A" : "B";
        statement.executeUpdate("INSERT INTO ord_sales_order(order_id,tenant_id,local_order_no,store_id,terminal_id,shift_id,cashier_user_id,business_date,store_timezone,status,draft_disposition,payment_status,currency,gross_amount_minor,discount_amount_minor,surcharge_amount_minor,receivable_amount_minor,received_amount_minor,catalog_version,price_version,industry_template_version,snapshot_schema_version,snapshot_json,snapshot_sha256,idempotency_key,request_sha256,occurred_at,record_version) VALUES('" + orderId + "','" + tenant + "','" + localNo + "'," + storeId + ",'01K2" + tenantPrefix + "000000000000000000011','" + shiftId + "'," + cashierId + ",'2026-08-16','Asia/Shanghai','COMPLETED','ACTIVE','PAID','CNY',1299,0,0,1299,1299,1,1,'CONVENIENCE.1',1,JSON_OBJECT(),REPEAT('a',64),'payment-order-" + tenantPrefix + "-0001',REPEAT('b',64),UTC_TIMESTAMP(3),4)");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
