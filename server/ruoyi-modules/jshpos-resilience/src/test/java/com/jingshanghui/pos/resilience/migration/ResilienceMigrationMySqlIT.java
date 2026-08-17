package com.jingshanghui.pos.resilience.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gate 6A MySQL 8.4 空库迁移、权限、冻结字段与只追加流水集成验证。 */
class ResilienceMigrationMySqlIT {
    private final String url = required("GATE6A_RESILIENCE_MYSQL_JDBC_URL", "GATE6A_MYSQL_JDBC_URL");
    private final String username = required("GATE6A_MYSQL_USERNAME");
    private final String password = required("GATE6A_MYSQL_PASSWORD");

    @Test
    void migratesAllPublishedVersionsAndEnforcesBackupRecoveryGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        Set<String> expected = expectedVersions();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(expected.size());
        Set<String> applied = Arrays.stream(flyway.info().applied())
            .map(info -> info.getVersion().getVersion()).collect(Collectors.toSet());
        assertThat(applied.remove("0")).isTrue();
        assertThat(applied).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608180039");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesPermissionsAndGuards();
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

    private void assertTablesPermissionsAndGuards() throws SQLException {
        String backup = "01K2A000000000000000000201";
        String object = "01K2A000000000000000000202";
        String drill = "01K2A000000000000000000203";
        String check = "01K2A000000000000000000204";
        String audit = "01K2A000000000000000000205";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : Set.of("bak_backup_set", "bak_backup_object", "bak_restore_drill",
                "bak_restore_check", "bak_audit")) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9201300 AND 9201304")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
            statement.executeUpdate("INSERT INTO bak_backup_set(backup_id,environment_code,tenant_ids_csv,tenant_scope_sha256,point_in_time,latest_included_fact_at,schema_version,application_version,key_version,immutable_until,request_sha256,state,requested_by,correlation_id,created_at,updated_at) VALUES('" + backup + "','synthetic','TENANT_A,TENANT_B',REPEAT('a',64),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)-INTERVAL 5 MINUTE,'202608180039','1.0.0','synthetic-v1',UTC_TIMESTAMP(3)+INTERVAL 30 DAY,REPEAT('b',64),'CREATING',101,'01K2A000000000000000000206',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO bak_backup_object(object_id,backup_id,data_class,logical_name,media_type,tenant_scope_sha256,plaintext_size_bytes,plaintext_sha256,ciphertext_size_bytes,ciphertext_sha256,key_version,nonce_base64,object_key,created_at) VALUES('" + object + "','" + backup + "','MYSQL','mysql/db.sql','application/sql',REPEAT('a',64),2,REPEAT('c',64),30,REPEAT('d',64),'synthetic-v1','AAAAAAAAAAAAAAAA','backups/" + "a".repeat(64) + "/" + backup + "/" + "d".repeat(64) + ".aead',UTC_TIMESTAMP(3))");
            statement.executeUpdate("UPDATE bak_backup_set SET state='AVAILABLE',manifest_sha256=REPEAT('e',64),manifest_json='{}',last_actor_id=101,last_correlation_id='01K2A000000000000000000207',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE backup_id='" + backup + "'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE bak_backup_set SET tenant_scope_sha256=REPEAT('f',64) WHERE backup_id='" + backup + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("frozen identity");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE bak_backup_object SET plaintext_sha256=REPEAT('0',64) WHERE object_id='" + object + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            statement.executeUpdate("INSERT INTO bak_restore_drill(drill_id,backup_id,request_sha256,state,started_at,rpo_seconds,rto_seconds,requested_by,correlation_id) VALUES('" + drill + "','" + backup + "',REPEAT('1',64),'RUNNING',UTC_TIMESTAMP(3),300,0,101,'01K2A000000000000000000208')");
            statement.executeUpdate("INSERT INTO bak_restore_check(check_id,drill_id,check_code,result,evidence_sha256,checked_at) VALUES('" + check + "','" + drill + "','MANIFEST','PASS',REPEAT('2',64),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO bak_audit(audit_id,backup_id,drill_id,action_code,actor_id,correlation_id,evidence_sha256,occurred_at) VALUES('" + audit + "','" + backup + "','" + drill + "','RESTORE_STARTED',101,'01K2A000000000000000000209',REPEAT('3',64),UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM bak_restore_check WHERE check_id='" + check + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM bak_audit WHERE audit_id='" + audit + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            statement.executeUpdate("UPDATE bak_restore_drill SET state='PASS',ended_at=UTC_TIMESTAMP(3),rto_seconds=1,evidence_sha256=REPEAT('4',64),last_actor_id=101,last_correlation_id='01K2A000000000000000000210',version=version+1 WHERE drill_id='" + drill + "'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE bak_restore_drill SET state='RUNNING' WHERE drill_id='" + drill + "'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("illegal transition");
        }
    }

    private static Set<String> expectedVersions() {
        Set<String> versions = new LinkedHashSet<>();
        for (int version = 1; version <= 12; version++) versions.add("20260816" + String.format("%04d", version));
        versions.add("202608160036"); versions.add("202608160037");
        for (int version = 13; version <= 35; version++) versions.add("20260817" + String.format("%04d", version));
        versions.add("202608180038"); versions.add("202608180039");
        return versions;
    }

    private static String required(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) return value;
        }
        throw new IllegalStateException(String.join("/", names) + " must be provided by CI");
    }
}
