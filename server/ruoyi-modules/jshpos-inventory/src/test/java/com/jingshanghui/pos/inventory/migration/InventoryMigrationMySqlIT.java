package com.jingshanghui.pos.inventory.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gate 7C 在干净 MySQL 8.4 上执行完整前向迁移并验证库存、批次与效期不变量。 */
class InventoryMigrationMySqlIT {

    private final String url = required("GATE4A_MYSQL_JDBC_URL");
    private final String username = required("GATE4A_MYSQL_USERNAME");
    private final String password = required("GATE4A_MYSQL_PASSWORD");

    @Test
    void migratesAllThirtyFiveVersionsAndEnforcesTenantLedgerLotAndPolicyInvariants() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(35);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesAndPermissions();
        assertTwoTenantInventoryConstraints();
        assertLotTenantQuantityAndImmutabilityConstraints();
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
        Set<String> tables = Set.of("inv_stock_policy_version", "inv_stock_command", "inv_stock_balance",
            "inv_stock_ledger", "inv_stock_anomaly", "inv_audit_event", "inv_event_outbox",
            "inv_stocktake", "inv_stocktake_line", "inv_stocktake_count", "inv_stocktake_adjustment",
            "cat_lot_policy_version", "inv_lot_identity", "inv_lot_command", "inv_lot_balance",
            "inv_lot_ledger", "inv_lot_allocation", "inv_lot_expiry_projection", "inv_lot_audit_event",
            "inv_lot_outbox", "inv_lot_package_release");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200500 AND 9200505")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(6);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200560 AND 9200565")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(6);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() " +
                    "AND trigger_name IN ('trg_inv_stocktake_count_no_update','trg_inv_stocktake_posted_immutable')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                    "AND (table_name LIKE 'pur\\_%' OR table_name LIKE 'cst\\_%' OR table_name LIKE 'trf\\_%' " +
                    "OR table_name LIKE 'stocktake\\_%')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertLotTenantQuantityAndImmutabilityConstraints() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_config_template(template_id,tenant_id,template_code,template_name,industry,status) VALUES(19001,'TENANT_A','COMMUNITY','Community','COMMUNITY_SUPERMARKET','ACTIVE')");
            statement.executeUpdate("INSERT INTO jsh_config_template_version(config_version_id,tenant_id,template_id,version_no,schema_version,state,content_json,content_sha256,published_by,published_at) VALUES(19101,'TENANT_A',19001,1,'1.0','PUBLISHED',JSON_OBJECT(),REPEAT('a',64),101,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO cat_lot_policy_version(tenant_id,policy_version_id,store_id,sku_id,enabled,expiry_basis,shelf_life_days,near_expiry_days,industry,template_version_id,effective_from,content_sha256,state,published_by,published_at) VALUES('TENANT_A','01K2A000000000000000000181',1101,701,1,'EXPLICIT_EXPIRY_DATE',NULL,3,'COMMUNITY_SUPERMARKET',19101,UTC_TIMESTAMP(6),REPEAT('b',64),'PUBLISHED',101,UTC_TIMESTAMP(6))");
            statement.executeUpdate("INSERT INTO inv_lot_identity(lot_id,tenant_id,store_id,warehouse_id,sku_id,base_unit_id,internal_lot_code,received_date,expiry_date,policy_version_id,near_expiry_days,content_sha256,created_at) VALUES('01K2A000000000000000000182','TENANT_A',1101,'01K2A000000000000000000010',701,301,'LOT-A','2026-08-23','2026-09-23','01K2A000000000000000000181',3,REPEAT('c',64),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_lot_balance(tenant_id,lot_id,on_hand_quantity,last_ledger_sequence,record_version,updated_at) VALUES('TENANT_A','01K2A000000000000000000182',0,0,0,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_lot_identity SET expiry_date='2026-10-01' WHERE tenant_id='TENANT_A' AND lot_id='01K2A000000000000000000182'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_lot_identity(lot_id,tenant_id,store_id,warehouse_id,sku_id,base_unit_id,internal_lot_code,received_date,expiry_date,policy_version_id,near_expiry_days,content_sha256,created_at) VALUES('01K2B000000000000000000182','TENANT_B',2101,'01K2B000000000000000000010',801,401,'LOT-B','2026-08-23','2026-09-23','01K2A000000000000000000181',3,REPEAT('d',64),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_lot_identity(lot_id,tenant_id,store_id,warehouse_id,sku_id,base_unit_id,internal_lot_code,received_date,expiry_date,policy_version_id,near_expiry_days,content_sha256,created_at) VALUES('01K2A000000000000000000183','TENANT_A',1101,'01K2A000000000000000000010',701,301,'LOT-EXPIRED','2026-08-23','2026-08-22','01K2A000000000000000000181',3,REPEAT('e',64),UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO inv_lot_package_release(release_id,tenant_id,store_id,warehouse_id,package_version,previous_version,source_sha256,payload_sha256,payload_bytes,signing_key_id,signature_bytes,record_count,generated_at) VALUES('01K2A000000000000000000184','TENANT_A',1101,'01K2A000000000000000000010',1,0,REPEAT('a',64),REPEAT('b',64),X'7B7D','kms-test',REPEAT(X'01',64),1,UTC_TIMESTAMP(6))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_lot_package_release SET record_count=2 WHERE tenant_id='TENANT_A' AND release_id='01K2A000000000000000000184'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_lot_package_release(release_id,tenant_id,store_id,warehouse_id,package_version,previous_version,source_sha256,payload_sha256,payload_bytes,signing_key_id,signature_bytes,record_count,generated_at) VALUES('01K2A000000000000000000185','TENANT_A',1101,'01K2A000000000000000000010',3,1,REPEAT('c',64),REPEAT('d',64),X'7B7D','kms-test',REPEAT(X'02',64),1,UTC_TIMESTAMP(6))"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void assertTwoTenantInventoryConstraints() throws SQLException {
        String warehouseA = "01K2A000000000000000000010";
        String warehouseB = "01K2B000000000000000000010";
        String policyA = "01K2A000000000000000000020";
        String policyB = "01K2B000000000000000000020";
        String eventA = "01K2A000000000000000000030";
        String eventB = "01K2B000000000000000000030";
        String orderA = "01K2A000000000000000000040";
        String orderB = "01K2B000000000000000000040";
        String lineA = "01K2A000000000000000000050";
        String lineB = "01K2B000000000000000000050";
        String dimensionA = "a".repeat(64);
        String dimensionB = "b".repeat(64);
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            seedFoundationAndCatalog(statement);
            statement.executeUpdate("INSERT INTO inv_stock_policy_version(policy_version_id,tenant_id,store_id,warehouse_id,negative_stock_mode,effective_from,publisher_user_id,published_at) VALUES('" + policyA + "','TENANT_A',1101,'" + warehouseA + "','DENY',UTC_TIMESTAMP(3),101,UTC_TIMESTAMP(3)),('" + policyB + "','TENANT_B',2101,'" + warehouseB + "','ALLOW_AND_ALERT',UTC_TIMESTAMP(3),201,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_stock_command(source_event_id,tenant_id,request_sha256,source_type,source_id,warehouse_id,store_id,correlation_id,actor_user_id,status,affected_lines,negative_alert,created_at) VALUES('" + eventA + "','TENANT_A',REPEAT('c',64),'ORDER','" + orderA + "','" + warehouseA + "',1101,'trace-a',101,'PROCESSING',0,0,UTC_TIMESTAMP(3)),('" + eventB + "','TENANT_B',REPEAT('d',64),'ORDER','" + orderB + "','" + warehouseB + "',2101,'trace-b',201,'PROCESSING',0,0,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO inv_stock_balance(tenant_id,stock_dimension_key,warehouse_id,sku_id,stock_status,on_hand_quantity,reserved_quantity,frozen_quantity,safety_stock_quantity,last_ledger_sequence,record_version,updated_at) VALUES('TENANT_A','" + dimensionA + "','" + warehouseA + "',701,'SALEABLE',10,0,0,0,0,0,UTC_TIMESTAMP(3)),('TENANT_B','" + dimensionB + "','" + warehouseB + "',801,'SALEABLE',5,0,0,0,0,0,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_stock_balance(tenant_id,stock_dimension_key,warehouse_id,sku_id,stock_status,on_hand_quantity,reserved_quantity,frozen_quantity,safety_stock_quantity,last_ledger_sequence,record_version,updated_at) VALUES('TENANT_A',REPEAT('e',64),'" + warehouseA + "',801,'SALEABLE',0,0,0,0,0,0,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);

            String ledgerA = "INSERT INTO inv_stock_ledger(ledger_id,tenant_id,stock_dimension_key,ledger_sequence,warehouse_id,sku_id,base_unit_id,stock_status,movement_type,quantity_before,quantity_delta,quantity_after,source_type,source_id,source_line_id,source_event_id,policy_version_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2A000000000000000000060','TENANT_A','" + dimensionA + "',1,'" + warehouseA + "',701,301,'SALEABLE','SALE_OUT',10,-2,8,'ORDER','" + orderA + "','" + lineA + "','" + eventA + "','" + policyA + "','2026-08-16',101,'trace-a',UTC_TIMESTAMP(3))";
            statement.executeUpdate(ledgerA);
            statement.executeUpdate("UPDATE inv_stock_balance SET on_hand_quantity=8,last_ledger_sequence=1,record_version=1 WHERE tenant_id='TENANT_A' AND stock_dimension_key='" + dimensionA + "'");
            statement.executeUpdate("INSERT INTO inv_stock_ledger(ledger_id,tenant_id,stock_dimension_key,ledger_sequence,warehouse_id,sku_id,base_unit_id,stock_status,movement_type,quantity_before,quantity_delta,quantity_after,source_type,source_id,source_line_id,source_event_id,policy_version_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2B000000000000000000060','TENANT_B','" + dimensionB + "',1,'" + warehouseB + "',801,401,'SALEABLE','SALE_OUT',5,-2,3,'ORDER','" + orderB + "','" + lineB + "','" + eventB + "','" + policyB + "','2026-08-16',201,'trace-b',UTC_TIMESTAMP(3))");

            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_stock_ledger SET quantity_after=9 WHERE tenant_id='TENANT_A' AND ledger_id='01K2A000000000000000000060'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM inv_stock_ledger WHERE tenant_id='TENANT_A' AND ledger_id='01K2A000000000000000000060'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE inv_stock_policy_version SET negative_stock_mode='ALLOW_AND_ALERT' WHERE tenant_id='TENANT_A' AND policy_version_id='" + policyA + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_stock_ledger(ledger_id,tenant_id,stock_dimension_key,ledger_sequence,warehouse_id,sku_id,base_unit_id,stock_status,movement_type,quantity_before,quantity_delta,quantity_after,source_type,source_id,source_line_id,source_event_id,policy_version_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2A000000000000000000061','TENANT_A','" + dimensionA + "',2,'" + warehouseA + "',701,301,'SALEABLE','SALE_OUT',8,-1,8,'ORDER','" + orderA + "','01K2A000000000000000000051','" + eventA + "','" + policyA + "','2026-08-16',101,'trace-a',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO inv_stock_ledger(ledger_id,tenant_id,stock_dimension_key,ledger_sequence,warehouse_id,sku_id,base_unit_id,stock_status,movement_type,quantity_before,quantity_delta,quantity_after,source_type,source_id,source_line_id,source_event_id,policy_version_id,business_date,actor_user_id,correlation_id,occurred_at) VALUES('01K2A000000000000000000062','TENANT_A','" + dimensionA + "',2,'" + warehouseA + "',701,301,'SALEABLE','SALE_OUT',8,-1,7,'ORDER','" + orderA + "','" + lineA + "','" + eventA + "','" + policyA + "','2026-08-16',101,'trace-a',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
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
