package com.jingshanghui.pos.onboarding.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 验证 V66-V67 前向迁移、租户外键和不可变历史。 */
class OnboardingMySqlIT {
    private final String url = required("GATE7C_ONB_MYSQL_JDBC_URL");
    private final String username = required("GATE7C_ONB_MYSQL_USERNAME");
    private final String password = required("GATE7C_ONB_MYSQL_PASSWORD");

    @Test
    void migratesRepeatablyAndEnforcesOnboardingOwnerGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608230067");
        assertSchema();
        assertTenantAndAppendOnlyGuards();
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

    private void assertSchema() throws SQLException {
        Set<String> tables = Set.of("onb_plan", "onb_config_snapshot", "onb_approval", "onb_step_checkpoint",
            "onb_check_result", "onb_command_result", "onb_state_event", "onb_audit_event", "onb_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200550 AND 9200557")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(8);
            }
            try (var rows = statement.executeQuery("""
                SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()
                  AND trigger_name LIKE 'trg_onb_%'
                """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(15);
            }
        }
    }

    private void assertTenantAndAppendOnlyGuards() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version)
                VALUES(1001,'TENANT_A','HQ-A','A总部','HEADQUARTERS','ACTIVE',1,0),
                      (2001,'TENANT_B','HQ-B','B总部','HEADQUARTERS','ACTIVE',1,0)
                """);
            statement.executeUpdate("""
                INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,status,version)
                VALUES(1101,'TENANT_A',1001,'SRC-A','A来源店','Asia/Shanghai','ACTIVE',1),
                      (1102,'TENANT_A',1001,'DST-A','A目标店','Asia/Shanghai','PREPARING',1),
                      (2102,'TENANT_B',2001,'DST-B','B目标店','Asia/Shanghai','PREPARING',1)
                """);
            statement.executeUpdate("""
                INSERT INTO jsh_config_template(template_id,tenant_id,template_code,template_name,industry,status,version)
                VALUES(1201,'TENANT_A','CV-A','便利店模板','CONVENIENCE','ACTIVE',1)
                """);
            statement.executeUpdate("""
                INSERT INTO jsh_config_template_version(config_version_id,tenant_id,template_id,version_no,
                  schema_version,state,content_json,content_sha256,published_by,published_at)
                VALUES(1202,'TENANT_A',1201,1,'1.0','PUBLISHED',JSON_OBJECT('ui.layout','compact'),
                  REPEAT('a',64),101,UTC_TIMESTAMP(6))
                """);
            statement.executeUpdate("""
                INSERT INTO onb_plan(plan_id,tenant_id,source_store_id,target_store_id,template_id,template_version_id,
                  source_store_version,target_store_version,template_version_no,template_sha256,industry,snapshot_sha256,
                  state,idempotency_key,request_sha256,creator_user_id,created_at,updated_at)
                VALUES('01K3M000000000000000000001','TENANT_A',1101,1102,1201,1202,1,1,1,REPEAT('a',64),
                  'CONVENIENCE',REPEAT('b',64),'DRAFT','onb-create-001',REPEAT('c',64),101,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO onb_plan(plan_id,tenant_id,source_store_id,target_store_id,template_id,template_version_id,
                  source_store_version,target_store_version,template_version_no,template_sha256,industry,snapshot_sha256,
                  state,idempotency_key,request_sha256,creator_user_id,created_at,updated_at)
                VALUES('01K3M000000000000000000002','TENANT_A',1101,2102,1201,1202,1,1,1,REPEAT('a',64),
                  'CONVENIENCE',REPEAT('b',64),'DRAFT','onb-create-002',REPEAT('d',64),101,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """)).isInstanceOf(SQLException.class);
            statement.executeUpdate("""
                INSERT INTO onb_approval(approval_id,tenant_id,plan_id,approver_user_id,reason,idempotency_key,
                  request_sha256,approved_at) VALUES('01K3M000000000000000000003','TENANT_A',
                  '01K3M000000000000000000001',102,'独立审批通过','onb-approve-001',REPEAT('e',64),UTC_TIMESTAMP(6))
                """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE onb_plan SET source_store_id=NULL WHERE plan_id='01K3M000000000000000000001'
                """)).isInstanceOf(SQLException.class).hasMessageContaining("immutable identity");
            assertThatThrownBy(() -> statement.executeUpdate("""
                DELETE FROM onb_approval WHERE approval_id='01K3M000000000000000000003'
                """)).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
