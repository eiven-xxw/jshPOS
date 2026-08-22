package com.jingshanghui.pos.procurement.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 执行模块迁移，并验证采购、盘点和补货数据库级不变量。 */
class ProcurementMigrationMySqlIT {

    private final String url = required("GATE4B_MYSQL_JDBC_URL");
    private final String username = required("GATE4B_MYSQL_USERNAME");
    private final String password = required("GATE4B_MYSQL_PASSWORD");

    @Test
    void migratesSixteenVersionsAndEnforcesGate7cConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(16);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesPermissionsAndNoDeferredRuntime();
        assertTenantAndFrozenFactConstraints();
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

    private void assertTablesPermissionsAndNoDeferredRuntime() throws SQLException {
        Set<String> tables = Set.of("inv_stocktake", "inv_stocktake_count", "inv_stocktake_adjustment",
            "sup_supplier", "pur_purchase_order", "pur_purchase_order_line", "pur_receipt",
            "pur_receipt_line", "pur_purchase_return", "pur_purchase_return_line",
            "pur_audit_event", "pur_event_outbox");
        tables = new java.util.HashSet<>(tables);
        tables.addAll(Set.of("rpl_policy_version", "rpl_policy_item", "rpl_generation_run",
            "rpl_suggestion", "rpl_suggestion_event", "rpl_audit_event", "rpl_event_outbox"));
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200510 AND 9200533")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(20);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200534 AND 9200539")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(6);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                    "AND (table_name LIKE 'cst\\_%' OR table_name LIKE 'trf\\_%')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() " +
                    "AND trigger_name IN ('trg_pur_order_line_core_immutable'," +
                    "'trg_pur_receipt_confirmed_immutable','trg_inv_stocktake_count_no_update')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(3);
            }
        }
    }

    private void assertTenantAndFrozenFactConstraints() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            seedFoundationAndCatalog(statement);
            statement.executeUpdate("INSERT INTO sup_supplier(supplier_id,tenant_id,supplier_code,supplier_name,status,version,creator_user_id,created_at,updated_at) VALUES('01K2A000000000000000000101','TENANT_A','SUP-A','A Supplier','ACTIVE',0,101,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)),('01K2B000000000000000000101','TENANT_B','SUP-B','B Supplier','ACTIVE',0,201,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO pur_purchase_order(order_id,tenant_id,supplier_id,store_id,warehouse_id,expected_date,status,over_receipt_tolerance_bps,request_sha256,correlation_id,creator_user_id,version,created_at,updated_at) VALUES('01K2A000000000000000000102','TENANT_A','01K2A000000000000000000101',1101,'01K2A000000000000000000010','2026-08-20','DRAFT',0,REPEAT('a',64),'trace-a',101,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO pur_purchase_order_line(order_line_id,tenant_id,order_id,sku_id,purchase_unit_id,conversion_numerator,conversion_denominator,ordered_quantity,received_quantity,unit_price_minor,tax_rate_bps,created_at,updated_at) VALUES('01K2A000000000000000000103','TENANT_A','01K2A000000000000000000102',701,301,1,1,10,0,100,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");

            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pur_purchase_order(order_id,tenant_id,supplier_id,store_id,warehouse_id,expected_date,status,over_receipt_tolerance_bps,request_sha256,correlation_id,creator_user_id,version,created_at,updated_at) VALUES('01K2A000000000000000000104','TENANT_A','01K2B000000000000000000101',1101,'01K2A000000000000000000010','2026-08-20','DRAFT',0,REPEAT('b',64),'trace-x',101,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE pur_purchase_order_line SET ordered_quantity=11 WHERE tenant_id='TENANT_A' AND order_line_id='01K2A000000000000000000103'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pur_purchase_order(order_id,tenant_id,supplier_id,store_id,warehouse_id,expected_date,status,over_receipt_tolerance_bps,request_sha256,correlation_id,creator_user_id,version,created_at,updated_at) VALUES('01K2A000000000000000000105','TENANT_A','01K2A000000000000000000101',1101,'01K2A000000000000000000010','2026-08-20','DRAFT',1001,REPEAT('c',64),'trace-y',101,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);

            statement.executeUpdate("INSERT INTO rpl_policy_version(policy_version_id,tenant_id,store_id,warehouse_id,version_no,state,effective_from,idempotency_key,request_sha256,actor_user_id,version,created_at,updated_at) VALUES('01K2A000000000000000000201','TENANT_A',1101,'01K2A000000000000000000010',1,'DRAFT',UTC_TIMESTAMP(3),'idem-rpl-policy',REPEAT('d',64),101,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO rpl_policy_item(policy_item_id,tenant_id,policy_version_id,sku_id,sku_code,base_unit_id,purchase_unit_id,conversion_numerator,conversion_denominator,supplier_id,minimum_base_quantity,maximum_base_quantity,minimum_order_quantity,order_multiple,include_confirmed_in_transit,unit_price_minor,tax_rate_bps,item_sha256,created_at) VALUES('01K2A000000000000000000202','TENANT_A','01K2A000000000000000000201',701,'A-SKU',301,301,1,1,'01K2A000000000000000000101',5,20,1,2,TRUE,100,0,REPEAT('e',64),UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO rpl_policy_item(policy_item_id,tenant_id,policy_version_id,sku_id,sku_code,base_unit_id,purchase_unit_id,conversion_numerator,conversion_denominator,supplier_id,minimum_base_quantity,maximum_base_quantity,minimum_order_quantity,order_multiple,include_confirmed_in_transit,unit_price_minor,tax_rate_bps,item_sha256,created_at) VALUES('01K2A000000000000000000203','TENANT_A','01K2A000000000000000000201',801,'B-SKU',301,301,1,1,'01K2A000000000000000000101',5,20,1,2,TRUE,100,0,REPEAT('f',64),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE rpl_policy_item SET maximum_base_quantity=21 WHERE tenant_id='TENANT_A' AND policy_item_id='01K2A000000000000000000202'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE pur_purchase_order SET source_id='01K2A000000000000000000202' WHERE tenant_id='TENANT_A' AND order_id='01K2A000000000000000000102'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        }
    }

    private void seedFoundationAndCatalog(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
        statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
        statement.executeUpdate("INSERT INTO cat_category(category_id,tenant_id,category_code,category_name) VALUES(101,'TENANT_A','FOOD','Food'),(201,'TENANT_B','FOOD','Food')");
        statement.executeUpdate("INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name) VALUES(301,'TENANT_A','PCS','Piece'),(401,'TENANT_B','PCS','Piece')");
        statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES(501,'TENANT_A','A-SPU','A',101,'ACTIVE'),(601,'TENANT_B','B-SPU','B',201,'ACTIVE')");
        statement.executeUpdate("INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,status) VALUES(701,'TENANT_A',501,'A-SKU','A','STANDARD','ACTIVE'),(801,'TENANT_B',601,'B-SKU','B','STANDARD','ACTIVE')");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
