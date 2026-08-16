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

/** 在干净 MySQL 8.4 执行 V1—V17，并验证成本租户键、方程和不可变触发器。 */
class CostingMigrationMySqlIT {

    private final String url = required("GATE4C_MYSQL_JDBC_URL");
    private final String username = required("GATE4C_MYSQL_USERNAME");
    private final String password = required("GATE4C_MYSQL_PASSWORD");

    @Test
    void migratesSeventeenVersionsAndEnforcesCostingConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(17);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesPermissionsAndNoTransferRuntime();
        assertTenantEquationAndImmutableLedger();
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

    private void assertTablesPermissionsAndNoTransferRuntime() throws SQLException {
        Set<String> tables = Set.of("inv_cost_policy_version", "inv_cost_balance", "inv_cost_ledger",
            "inv_cost_rebuild_run", "inv_cost_audit_event", "inv_cost_event_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200540 AND 9200543")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                    "AND table_name LIKE 'trf\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() " +
                    "AND trigger_name IN ('trg_inv_cost_ledger_no_update','trg_inv_cost_ledger_no_delete'," +
                    "'trg_inv_cost_policy_no_update','trg_inv_cost_audit_no_update')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
        }
    }

    private void assertTenantEquationAndImmutableLedger() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            seedFoundationAndCatalog(statement);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_cost_policy_version(policy_version_id,tenant_id,store_id,warehouse_id,cost_scope_type,currency_code,quantity_scale,cost_scale,rounding_mode,zero_quantity_mode,effective_from,publisher_user_id,published_at) VALUES('01K2A000000000000000000090','TENANT_A',2101,'01K2A000000000000000000010','WAREHOUSE','CNY',6,6,'HALF_EVEN','ZERO_AMOUNT_KEEP_LAST_UNIT_COST',UTC_TIMESTAMP(3),101,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);

            statement.executeUpdate("INSERT INTO inv_stock_policy_version(policy_version_id,tenant_id,store_id,warehouse_id,negative_stock_mode,effective_from,publisher_user_id,published_at) VALUES('01K2A000000000000000000020','TENANT_A',1101,'01K2A000000000000000000010','ALLOW_AND_ALERT',UTC_TIMESTAMP(3),101,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_stock_command(source_event_id,tenant_id,request_sha256,source_type,source_id,warehouse_id,store_id,correlation_id,actor_user_id,status,affected_lines,negative_alert,created_at,applied_at) VALUES('01K2A000000000000000000030','TENANT_A',REPEAT('a',64),'PURCHASE_RECEIPT','01K2A000000000000000000031','01K2A000000000000000000010',1101,'trace-cost',101,'APPLIED',1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_stock_balance(tenant_id,stock_dimension_key,warehouse_id,sku_id,stock_status,on_hand_quantity,reserved_quantity,frozen_quantity,safety_stock_quantity,last_ledger_sequence,record_version,updated_at) VALUES('TENANT_A',REPEAT('b',64),'01K2A000000000000000000010',701,'SALEABLE',1,0,0,0,1,1,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_stock_ledger(ledger_id,tenant_id,stock_dimension_key,ledger_sequence,warehouse_id,sku_id,base_unit_id,stock_status,movement_type,quantity_before,quantity_delta,quantity_after,source_type,source_id,source_line_id,source_event_id,policy_version_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2A000000000000000000040','TENANT_A',REPEAT('b',64),1,'01K2A000000000000000000010',701,301,'SALEABLE','PURCHASE_RECEIPT_IN',0,1,1,'PURCHASE_RECEIPT','01K2A000000000000000000031','01K2A000000000000000000032','01K2A000000000000000000030','01K2A000000000000000000020','2026-08-17',101,'trace-cost',UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_cost_policy_version(policy_version_id,tenant_id,store_id,warehouse_id,cost_scope_type,currency_code,quantity_scale,cost_scale,rounding_mode,zero_quantity_mode,effective_from,publisher_user_id,published_at) VALUES('01K2A000000000000000000050','TENANT_A',1101,'01K2A000000000000000000010','WAREHOUSE','CNY',6,6,'HALF_EVEN','ZERO_AMOUNT_KEEP_LAST_UNIT_COST',UTC_TIMESTAMP(3),101,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_cost_balance(tenant_id,cost_dimension_key,cost_scope_id,warehouse_id,store_id,sku_id,currency_code,cost_quantity,cost_amount_minor,avg_unit_cost_minor,last_unit_cost_minor,last_cost_ledger_sequence,last_inventory_ledger_sequence,policy_version_id,record_version,updated_at) VALUES('TENANT_A',REPEAT('c',64),'01K2A000000000000000000010','01K2A000000000000000000010',1101,701,'CNY',1,100,100,100,1,1,'01K2A000000000000000000050',1,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_cost_ledger(cost_ledger_id,tenant_id,cost_dimension_key,cost_scope_id,cost_ledger_sequence,inventory_ledger_id,inventory_ledger_sequence,warehouse_id,sku_id,currency_code,movement_type,quantity_before,quantity_delta,quantity_after,cost_amount_before_minor,cost_amount_delta_minor,cost_amount_after_minor,unit_cost_minor,avg_unit_cost_after_minor,valuation_method,cost_estimated,variance_amount_minor,source_type,source_id,source_line_id,source_event_id,source_sha256,policy_version_id,reversal_of_cost_ledger_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2A000000000000000000060','TENANT_A',REPEAT('c',64),'01K2A000000000000000000010',1,'01K2A000000000000000000040',1,'01K2A000000000000000000010',701,'CNY','PURCHASE_RECEIPT_IN',0,1,1,0,100,100,100,100,'PURCHASE_FROZEN_PRICE',0,0,'PURCHASE_RECEIPT','01K2A000000000000000000031','01K2A000000000000000000032','01K2A000000000000000000030',REPEAT('d',64),'01K2A000000000000000000050',NULL,'2026-08-17',101,'trace-cost',UTC_TIMESTAMP(3))");

            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_cost_ledger SET unit_cost_minor=101 WHERE tenant_id='TENANT_A' AND cost_ledger_id='01K2A000000000000000000060'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_cost_balance(tenant_id,cost_dimension_key,cost_scope_id,warehouse_id,store_id,sku_id,currency_code,cost_quantity,cost_amount_minor,avg_unit_cost_minor,last_unit_cost_minor,last_cost_ledger_sequence,last_inventory_ledger_sequence,policy_version_id,record_version,updated_at) VALUES('TENANT_A',REPEAT('e',64),'01K2A000000000000000000011','01K2A000000000000000000011',1101,701,'CNY',0,1,0,0,0,0,'01K2A000000000000000000050',0,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void seedFoundationAndCatalog(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
        statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
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
