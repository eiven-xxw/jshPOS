package com.jingshanghui.pos.sync.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Executed by the Sprint S3 MySQL 8.4 quality gate with synthetic tenants only. */
class SyncMigrationMySqlIT {

    private final String url = required("SPRINT3_MYSQL_JDBC_URL");
    private final String username = required("SPRINT3_MYSQL_USERNAME");
    private final String password = required("SPRINT3_MYSQL_PASSWORD");

    @Test
    void migratesAllEightVersionsAndEnforcesDeviceTenantAndFactUniqueness() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesAndPermissions();
        assertTwoTenantDeviceAndBusinessFactConstraints();
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
        Set<String> tables = Set.of("pos_sync_device", "pos_sync_inbox", "pos_sync_business_fact",
            "pos_sync_change_feed", "pos_sync_pull_page", "pos_sync_cursor", "pos_sync_dead_letter",
            "pos_sync_security_event");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200300 AND 9200303")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'syn\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertTwoTenantDeviceAndBusinessFactConstraints() throws SQLException {
        String deviceA = "01K2A000000000000000000011";
        String deviceB = "01K2B000000000000000000011";
        String eventA = "01K2A000000000000000000081";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
            statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
            statement.executeUpdate("INSERT INTO pos_sync_device(device_id,tenant_id,store_id,terminal_id,bound_user_id,status) VALUES('" + deviceA + "','TENANT_A',1101,'" + deviceA + "',101,'ACTIVE'),('" + deviceB + "','TENANT_B',2101,'" + deviceB + "',201,'ACTIVE')");
            statement.executeUpdate("INSERT INTO pos_sync_inbox(event_id,tenant_id,device_id,batch_id,device_sequence,stream_code,event_type,event_version,aggregate_id,aggregate_version,idempotency_key,correlation_id,occurred_at,payload_json,payload_sha256,processing_status,received_at) VALUES('" + eventA + "','TENANT_A','" + deviceA + "','01K2A000000000000000000091',1,'order.command','order.completed.v1',1,'01K2A000000000000000000031',4,'" + eventA + "','01K2A000000000000000000071',UTC_TIMESTAMP(3),JSON_OBJECT('orderId','01K2A000000000000000000031'),REPEAT('a',64),'RECEIVED',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pos_sync_inbox(event_id,tenant_id,device_id,batch_id,device_sequence,stream_code,event_type,event_version,aggregate_id,aggregate_version,idempotency_key,correlation_id,occurred_at,payload_json,payload_sha256,processing_status,received_at) VALUES('01K2A000000000000000000082','TENANT_A','" + deviceB + "','01K2A000000000000000000092',2,'order.command','order.completed.v1',1,'01K2A000000000000000000032',4,'01K2A000000000000000000082','01K2A000000000000000000072',UTC_TIMESTAMP(3),JSON_OBJECT(),REPEAT('b',64),'RECEIVED',UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO pos_sync_business_fact(fact_id,tenant_id,source_event_id,stream_code,event_type,aggregate_id,aggregate_version,payload_json,payload_sha256,applied_at) VALUES('01K2A000000000000000000061','TENANT_A','" + eventA + "','order.command','order.completed.v1','01K2A000000000000000000031',4,JSON_OBJECT('orderId','01K2A000000000000000000031'),REPEAT('a',64),UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE pos_sync_business_fact SET payload_sha256=REPEAT('c',64) WHERE fact_id='01K2A000000000000000000061'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            statement.executeUpdate("INSERT INTO pos_sync_change_feed(change_id,tenant_id,stream_code,event_type,aggregate_id,aggregate_version,payload_json,payload_sha256,published_at) VALUES('01K2A000000000000000000051','TENANT_A','sync.control','sync.device-policy.changed.v1','" + deviceA + "',1,JSON_OBJECT('deviceStatus','ACTIVE'),REPEAT('d',64),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO pos_sync_pull_page(cursor_token,tenant_id,device_id,stream_code,from_sequence,to_sequence,change_ids_json,page_sha256,status,offered_at) VALUES('01K2A000000000000000000041','TENANT_A','" + deviceA + "','sync.control',0,1,JSON_ARRAY('01K2A000000000000000000051'),REPEAT('e',64),'OFFERED',UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO pos_sync_cursor(tenant_id,device_id,stream_code,acked_sequence,acked_cursor_token,page_sha256) VALUES('TENANT_A','" + deviceA + "','sync.control',1,'01K2A000000000000000000041',REPEAT('e',64))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO pos_sync_cursor(tenant_id,device_id,stream_code,acked_sequence) VALUES('TENANT_A','" + deviceA + "','bad.stream',-1)"))
                .isInstanceOf(SQLException.class);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
