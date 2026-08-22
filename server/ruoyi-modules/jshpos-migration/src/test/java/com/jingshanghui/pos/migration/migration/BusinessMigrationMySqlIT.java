package com.jingshanghui.pos.migration.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 验证 V63-V65 前向迁移、租户复合外键和只追加/清理约束。 */
class BusinessMigrationMySqlIT {
    private final String url = required("GATE7C_DMT_MYSQL_JDBC_URL");
    private final String username = required("GATE7C_DMT_MYSQL_USERNAME");
    private final String password = required("GATE7C_DMT_MYSQL_PASSWORD");

    @Test
    void migratesToV65RepeatablyAndEnforcesMigrationOwnerGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608220065");
        assertTablesPermissionsAndTriggers();
        assertTenantFileAndAuditedCleanupGuards();
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

    private void assertTablesPermissionsAndTriggers() throws SQLException {
        Set<String> tables = Set.of("cat_migration_product", "mig_batch", "mig_file", "mig_staging_row",
            "mig_preflight_error", "mig_approval", "mig_owner_checkpoint", "mig_reconciliation",
            "mig_state_event", "mig_audit_event", "mig_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200540 AND 9200544")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
            try (var rows = statement.executeQuery("""
                SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
                AND trigger_name IN ('trg_cat_migration_product_no_update','trg_mig_file_no_update',
                'trg_mig_error_no_update','trg_mig_checkpoint_no_update','trg_mig_stage_guard')
                """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
        }
    }

    private void assertTenantFileAndAuditedCleanupGuards() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO mig_batch(batch_id,tenant_id,requested_types,state,idempotency_key,request_sha256,
                  correlation_id,creator_user_id,version,created_at)
                VALUES('01K2A000000000000000000001','TENANT_A',JSON_ARRAY('MEMBER'),'UPLOADED','idem-a',
                  REPEAT('a',64),'trace-a',101,0,UTC_TIMESTAMP(6)),
                  ('01K2B000000000000000000001','TENANT_B',JSON_ARRAY('MEMBER'),'UPLOADED','idem-b',
                  REPEAT('b',64),'trace-b',201,0,UTC_TIMESTAMP(6))
                """);
            statement.executeUpdate("""
                INSERT INTO mig_file(file_id,tenant_id,batch_id,data_type,mapping_version,source_sha256,
                  safe_filename,charset_name,row_count,error_count,state,source_system,custody_reference,file_bytes,
                  uploader_user_id,created_at)
                VALUES('01K2A000000000000000000002','TENANT_A','01K2A000000000000000000001','MEMBER','1.0',
                  REPEAT('c',64),'members.csv','UTF-8',1,0,'PREFLIGHT_PASSED','SYNTHETIC','CUSTODY:SYN-1',128,101,UTC_TIMESTAMP(6))
                """);
            // 空文件也必须先落脱敏失败事实，不能被字节数约束阻断后留在 PREFLIGHTING。
            statement.executeUpdate("""
                INSERT INTO mig_file(file_id,tenant_id,batch_id,data_type,mapping_version,source_sha256,
                  safe_filename,charset_name,row_count,error_count,state,source_system,custody_reference,file_bytes,
                  uploader_user_id,created_at)
                VALUES('01K2A000000000000000000005','TENANT_A','01K2A000000000000000000001','CATALOG','1.0',
                  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855','rejected.csv','REJECTED',
                  0,1,'PREFLIGHT_FAILED','SYNTHETIC','CUSTODY:SYN-EMPTY',0,101,UTC_TIMESTAMP(6))
                """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO mig_staging_row(row_id,tenant_id,batch_id,file_id,data_type,source_row_number,row_sha256,
                  cipher_text,key_version,content_hmac,state,expires_at,created_at)
                VALUES('01K2B000000000000000000003','TENANT_B','01K2B000000000000000000001',
                  '01K2A000000000000000000002','MEMBER',2,REPEAT('d',64),'cipher','kms-v1',REPEAT('e',64),
                  'READY',UTC_TIMESTAMP(6)+INTERVAL 30 DAY,UTC_TIMESTAMP(6))
                """))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("""
                INSERT INTO mig_staging_row(row_id,tenant_id,batch_id,file_id,data_type,source_row_number,row_sha256,
                  cipher_text,key_version,content_hmac,state,expires_at,created_at)
                VALUES('01K2A000000000000000000003','TENANT_A','01K2A000000000000000000001',
                  '01K2A000000000000000000002','MEMBER',2,REPEAT('d',64),'cipher','kms-v1',REPEAT('e',64),
                  'READY',UTC_TIMESTAMP(6)+INTERVAL 30 DAY,UTC_TIMESTAMP(6))
                """);
            statement.executeUpdate("""
                INSERT INTO mig_preflight_error(error_id,tenant_id,batch_id,file_id,data_type,source_row_number,field_name,
                  error_code,masked_message,created_at)
                VALUES('01K2A000000000000000000004','TENANT_A','01K2A000000000000000000001',
                  '01K2A000000000000000000002','MEMBER',0,NULL,'DMT-SYN-001','masked',UTC_TIMESTAMP(6))
                """);
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE mig_file SET safe_filename='changed.csv' WHERE file_id='01K2A000000000000000000002'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE mig_preflight_error SET masked_message='changed' WHERE error_id='01K2A000000000000000000004'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE mig_staging_row SET row_sha256=REPEAT('f',64) WHERE row_id='01K2A000000000000000000003'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("audited cleanup");
            statement.executeUpdate("""
                UPDATE mig_staging_row SET state='CLEANED',cipher_text='',content_hmac='',cleaned_at=UTC_TIMESTAMP(6)
                WHERE row_id='01K2A000000000000000000003'
                """);
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
