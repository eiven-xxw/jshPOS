package com.jingshanghui.pos.costing.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 执行 V1—V19，验证调拨复合租户键、不可变事实和既有账本准入。 */
class TransferMigrationMySqlIT {
    private final String url = required("GATE4D_MYSQL_JDBC_URL");
    private final String username = required("GATE4D_MYSQL_USERNAME");
    private final String password = required("GATE4D_MYSQL_PASSWORD");

    @Test
    void migratesNineteenVersionsAndEnforcesTransferConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(19);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesPermissionsAndLedgerChecks();
        assertTenantAndImmutableFacts();
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

    private void assertTablesPermissionsAndLedgerChecks() throws SQLException {
        Set<String> tables = Set.of("inv_transfer_order", "inv_transfer_line", "inv_transfer_command",
            "inv_transfer_dispatch", "inv_transfer_dispatch_line", "inv_transfer_receipt",
            "inv_transfer_receipt_line", "inv_transfer_transit_ledger", "inv_transfer_audit_event",
            "inv_transfer_event_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200800 AND 9200808")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(9);
            }
            try (var rows = statement.executeQuery(
                "SELECT GROUP_CONCAT(check_clause) FROM information_schema.check_constraints " +
                    "WHERE constraint_schema=DATABASE() AND constraint_name IN " +
                    "('ck_inv_command_source','ck_inv_ledger_movement','ck_inv_cost_ledger_movement'," +
                    "'ck_trf_transit_reason')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).contains("TRANSFER_DISPATCH").contains("TRANSFER_OUT")
                    .contains("TRANSFER_IN").contains("TRANSIT_LOSS");
            }
        }
    }

    private void assertTenantAndImmutableFacts() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            seedFoundationAndCatalog(statement);
            statement.executeUpdate("INSERT INTO inv_transfer_order(transfer_id,tenant_id,source_store_id,source_warehouse_id,destination_store_id,destination_warehouse_id,status,request_sha256,reason,correlation_id,creator_user_id,approver_user_id,approved_at,version,created_at,updated_at) VALUES('01K2A000000000000000000101','TENANT_A',1101,'01K2A000000000000000000010',1102,'01K2A000000000000000000011','APPROVED',REPEAT('a',64),'restock','trace-trf',101,102,UTC_TIMESTAMP(3),2,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_transfer_line(transfer_line_id,tenant_id,transfer_id,sku_id,requested_unit_id,conversion_numerator,conversion_denominator,input_quantity,base_unit_id,requested_quantity,dispatched_quantity,received_quantity,difference_quantity,created_at,updated_at) VALUES('01K2A000000000000000000102','TENANT_A','01K2A000000000000000000101',701,301,1,1,10,301,10,0,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_transfer_dispatch(dispatch_id,tenant_id,transfer_id,source_event_id,status,business_date,correlation_id,posted_at) VALUES('01K2A000000000000000000103','TENANT_A','01K2A000000000000000000101','01K2A000000000000000000104','POSTED','2026-08-17','trace-trf',UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_transfer_dispatch_line(dispatch_line_id,tenant_id,dispatch_id,transfer_line_id,sku_id,base_unit_id,base_quantity,created_at) VALUES('01K2A000000000000000000105','TENANT_A','01K2A000000000000000000103','01K2A000000000000000000102',701,301,10,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_transfer_transit_ledger(transit_ledger_id,tenant_id,transfer_id,transfer_line_id,fact_type,source_fact_id,quantity,business_date,correlation_id,occurred_at) VALUES('01K2A000000000000000000106','TENANT_A','01K2A000000000000000000101','01K2A000000000000000000102','DISPATCHED','01K2A000000000000000000105',10,'2026-08-17','trace-trf',UTC_TIMESTAMP(3))");

            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_transfer_dispatch SET status='POSTED' WHERE tenant_id='TENANT_A' AND dispatch_id='01K2A000000000000000000103'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_transfer_transit_ledger SET quantity=9 WHERE tenant_id='TENANT_A' AND transit_ledger_id='01K2A000000000000000000106'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_transfer_transit_ledger(transit_ledger_id,tenant_id,transfer_id,transfer_line_id,fact_type,source_fact_id,quantity,business_date,correlation_id,occurred_at) VALUES('01K2A000000000000000000108','TENANT_A','01K2A000000000000000000101','01K2A000000000000000000102','DIFFERENCE_APPROVED','01K2A000000000000000000109',1,'2026-08-17','trace-diff',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO inv_transfer_transit_ledger(transit_ledger_id,tenant_id,transfer_id,transfer_line_id,fact_type,source_fact_id,quantity,business_date,reason_code,correlation_id,occurred_at) VALUES('01K2A000000000000000000110','TENANT_A','01K2A000000000000000000101','01K2A000000000000000000102','DIFFERENCE_APPROVED','01K2A000000000000000000111',1,'2026-08-17','SHORTAGE','trace-diff',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_transfer_order(transfer_id,tenant_id,source_store_id,source_warehouse_id,destination_store_id,destination_warehouse_id,status,request_sha256,reason,correlation_id,creator_user_id,version,created_at,updated_at) VALUES('01K2A000000000000000000107','TENANT_A',1101,'01K2A000000000000000000010',2101,'01K2A000000000000000000012','DRAFT',REPEAT('b',64),'bad','trace-bad',101,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void seedFoundationAndCatalog(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
        statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Source','Asia/Shanghai','06:00:00','ACTIVE',0),(1102,'TENANT_A',1001,'A102','A Destination','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
        statement.executeUpdate("INSERT INTO cat_category(category_id,tenant_id,category_code,category_name) VALUES(101,'TENANT_A','FOOD','Food')");
        statement.executeUpdate("INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name) VALUES(301,'TENANT_A','PCS','Piece')");
        statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES(501,'TENANT_A','A-SPU','A',101,'ACTIVE')");
        statement.executeUpdate("INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,status) VALUES(701,'TENANT_A',501,'A-SKU','A','STANDARD','ACTIVE')");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
