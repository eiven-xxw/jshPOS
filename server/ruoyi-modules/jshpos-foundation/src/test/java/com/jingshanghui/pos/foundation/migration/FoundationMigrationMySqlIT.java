package com.jingshanghui.pos.foundation.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仅由 Gate 0 mysql-migration Job 显式运行；普通单元测试不伪造 MySQL 语义。
 */
class FoundationMigrationMySqlIT {

    private final String url = required("GATE0_MYSQL_JDBC_URL");
    private final String username = required("GATE0_MYSQL_USERNAME");
    private final String password = required("GATE0_MYSQL_PASSWORD");

    @Test
    void migratesRepeatablyAndEnforcesTenantAndAppendOnlyConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .cleanDisabled(true)
            .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTables();
        assertPermissionSeed();
        assertTenantForeignKeysAndAuditImmutability();
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sys_menu (
                    menu_id BIGINT NOT NULL PRIMARY KEY,
                    menu_name VARCHAR(50) NOT NULL,
                    parent_id BIGINT DEFAULT 0,
                    order_num INT DEFAULT 0,
                    path VARCHAR(200) DEFAULT '',
                    component VARCHAR(255),
                    query_param VARCHAR(255),
                    is_frame INT DEFAULT 1,
                    is_cache INT DEFAULT 0,
                    menu_type CHAR(1) DEFAULT '',
                    visible CHAR(1) DEFAULT '0',
                    status CHAR(1) DEFAULT '0',
                    perms VARCHAR(100),
                    icon VARCHAR(100) DEFAULT '#',
                    create_dept BIGINT,
                    create_by BIGINT,
                    create_time DATETIME,
                    remark VARCHAR(500) DEFAULT ''
                ) ENGINE=InnoDB
                """);
        }
    }

    private void assertTables() throws SQLException {
        Set<String> expected = Set.of(
            "jsh_org_unit", "jsh_store", "jsh_staff_scope", "jsh_config_template",
            "jsh_config_template_version", "jsh_config_binding", "jsh_audit_event"
        );
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            for (String table : expected) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
        }
    }

    private void assertPermissionSeed() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200000 AND 9200011")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isEqualTo(11);
        }
    }

    private void assertTenantForeignKeysAndAuditImmutability() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES (1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0)");
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES (2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES (1101,'TENANT_A',2001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO jsh_audit_event(audit_id,tenant_id,actor_user_id,actor_name,correlation_id,action_code,target_type,target_id,result,occurred_at) VALUES (9001,'TENANT_A',1,'synthetic','integration-correlation-0001','TEST','ORG','1001','SUCCESS',UTC_TIMESTAMP(6))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE jsh_audit_event SET result='FAILURE' WHERE audit_id=9001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM jsh_audit_event WHERE audit_id=9001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be provided by the mysql-migration gate");
        }
        return value;
    }
}
