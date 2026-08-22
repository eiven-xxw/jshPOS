package com.jingshanghui.pos.returns.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXG-001 CI 在干净 MySQL 8.4 中验证全部迁移、只追加关联、触发器与权限。 */
class ReturnsMigrationMySqlIT {
    private final String url = required("GATE5B_MYSQL_JDBC_URL");
    private final String username = required("GATE5B_MYSQL_USERNAME");
    private final String password = required("GATE5B_MYSQL_PASSWORD");

    @Test
    void migratesAllFilesThroughV58AndEnforcesReturnExchangeAndTenderConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        // 当前 Returns 依赖闭包包含 V1—V58 的 37 个已发布迁移；数量变化必须显式评审。
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(37);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("202608220058");
        assertTablesAndPermissions();
        assertCashLedgerIndexRepair();
        assertImmutableOutboxAndGuards();
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
        Set<String> tables = Set.of("ord_cash_refund", "ret_order_guard", "ret_return", "ret_return_line",
            "ret_state_history", "ret_inbox", "ret_outbox", "ret_idempotency", "ret_exchange",
            "ret_exchange_leg", "ret_exchange_event", "ret_exchange_idempotency", "pay_tender_plan",
            "pay_tender_allocation", "pay_tender_history", "ord_tender_settlement", "ord_cash_tender");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9201000 AND 9201003")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9201004 AND 9201007")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE 'syn\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    /** 锁定 V25 的 MySQL 8.4 修复：外键有独立支撑索引，销售入账仍按原现金支付唯一。 */
    private void assertCashLedgerIndexRepair() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='shf_cash_ledger' "
                + "AND index_name='idx_cash_ledger_payment' AND column_name IN ('tenant_id','cash_payment_id')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='shf_cash_ledger' "
                + "AND index_name='uk_cash_ledger_sale_payment' AND non_unique=0")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='shf_cash_ledger' "
                + "AND constraint_name='fk_cash_ledger_payment'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isOne();
            }
            try (var rows = statement.executeQuery("SELECT extra FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='shf_cash_ledger' "
                + "AND column_name='sale_cash_payment_id'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).containsIgnoringCase("STORED GENERATED");
            }
        }
    }

    private void assertImmutableOutboxAndGuards() throws SQLException {
        String event = "01K5E000000000000000000001";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO ret_order_guard(tenant_id,order_id) VALUES"
                + "('TENANT_A','01K5N000000000000000000001'),"
                + "('TENANT_B','01K5N000000000000000000001')");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ret_order_guard SET order_id="
                + "'01K5N000000000000000000002' WHERE tenant_id='TENANT_A'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("INSERT INTO ret_outbox(event_id,tenant_id,event_type,aggregate_id,"
                + "aggregate_version,correlation_id,payload_json,payload_sha256,delivery_state,available_at) VALUES('"
                + event + "','TENANT_A','return.inventory.receipt.requested.v1',"
                + "'01K5R000000000000000000001',1,'01K5Z000000000000000000001',"
                + "JSON_OBJECT('schemaVersion','1.0','returnId','01K5R000000000000000000001'),REPEAT('a',64),"
                + "'PENDING',UTC_TIMESTAMP(3))");
            statement.executeUpdate("UPDATE ret_outbox SET delivery_state='DELIVERED',delivered_at=UTC_TIMESTAMP(3) "
                + "WHERE tenant_id='TENANT_A' AND event_id='" + event + "'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ret_outbox SET payload_json="
                + "JSON_OBJECT('schemaVersion','1.0','returnId','01K5R000000000000000000099') "
                + "WHERE tenant_id='TENANT_A' AND event_id='" + event + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("payload is immutable");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ret_outbox WHERE tenant_id='TENANT_A' "
                + "AND event_id='" + event + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
