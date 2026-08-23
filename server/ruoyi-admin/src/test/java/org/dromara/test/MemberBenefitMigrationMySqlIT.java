package org.dromara.test;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 在全模块正式运行时类路径上执行 V1—V82，验证既有 Owner 与 Gate 8A SaaS 迁移可共同前向安装。
 * 该测试只由带受控 MySQL 8.4 服务的专属 CI Job 显式执行。
 */
@Tag("local")
class MemberBenefitMigrationMySqlIT {
    private final String url = required("GATE8A_SAA_MYSQL_JDBC_URL", "GATE7D_MEM003_MYSQL_JDBC_URL");
    private final String username = required("GATE8A_SAA_MYSQL_USERNAME", "GATE7D_MEM003_MYSQL_USERNAME");
    private final String password = required("GATE8A_SAA_MYSQL_PASSWORD", "GATE7D_MEM003_MYSQL_PASSWORD");

    @Test
    void migratesUnifiedRuntimeThroughV82AndEnforcesMemberBenefitFacts() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(60);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("202608230082");
        assertPermissionMenuRangesAreReconciled();
        assertOwnerTablesTenantKeysCommentsAndTriggers();
        assertPackageMetadataIsImmutable();
        assertSaasHistoryAndQuotaAreProtected();
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
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sys_role_menu (
                  role_id BIGINT NOT NULL,menu_id BIGINT NOT NULL,PRIMARY KEY(role_id,menu_id)
                ) ENGINE=InnoDB
                """);
        }
    }

    private void assertPermissionMenuRangesAreReconciled() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE "
                + "(menu_id=9201540 AND perms='inventory:cost-balance:read') OR "
                + "(menu_id=9201541 AND perms='inventory:cost-ledger:read') OR "
                + "(menu_id=9201542 AND perms='inventory:cost-policy:publish') OR "
                + "(menu_id=9201543 AND perms='inventory:cost-rebuild')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE "
                + "(menu_id=9200540 AND perms='migration:read') OR "
                + "(menu_id=9200541 AND perms='migration:upload') OR "
                + "(menu_id=9200542 AND perms='migration:approve') OR "
                + "(menu_id=9200543 AND perms='migration:execute') OR "
                + "(menu_id=9200544 AND perms='migration:activate')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(5);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu "
                + "WHERE menu_id BETWEEN 9201700 AND 9201712")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(13);
            }
        }
    }

    private void assertOwnerTablesTenantKeysCommentsAndTriggers() throws SQLException {
        Set<String> tables = Set.of(
            "mbr_benefit_policy", "mbr_benefit_version", "mbr_benefit_scope",
            "mbr_benefit_level_mapping", "mbr_entitlement_snapshot", "mbr_benefit_state_event",
            "mbr_benefit_command", "mbr_benefit_audit_event", "mbr_benefit_outbox",
            "prc_member_price_version", "prc_member_price_item", "prc_member_price_command",
            "prc_member_price_outbox", "prm_quote_member_benefit", "prm_member_benefit_package",
            "ord_member_benefit_binding", "saas_plan", "saas_merchant_application",
            "saas_application_state_event", "saas_entitlement_version", "saas_entitlement_item",
            "saas_tenant_entitlement", "saas_tenant_lifecycle_event", "saas_initialization_checkpoint",
            "saas_command_result", "saas_quota_usage", "saas_audit_event", "saas_outbox");
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table,
                    new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name IN ('" + String.join("','", tables)
                + "') AND column_comment=''")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name IN ('mbr_entitlement_snapshot',"
                + "'prc_member_price_item','prm_quote_member_benefit','ord_member_benefit_binding') "
                + "AND column_name='tenant_id' AND is_nullable='NO'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.triggers "
                + "WHERE trigger_schema=DATABASE() AND event_object_table IN ('mbr_benefit_scope',"
                + "'mbr_benefit_level_mapping','mbr_entitlement_snapshot','mbr_benefit_state_event',"
                + "'mbr_benefit_audit_event','prc_member_price_item','prm_quote_member_benefit',"
                + "'prm_member_benefit_package','ord_member_benefit_binding')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(8);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name IN ('prm_member_benefit_package',"
                + "'ord_member_benefit_binding') AND column_name IN ('phone','mobile','name','cipher_text','token')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertPackageMetadataIsImmutable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO prm_member_benefit_package(package_id,tenant_id,store_id,"
                + "package_version,previous_version,schema_version,engine_version,payload_sha256,signature_algorithm,"
                + "signing_key_id,object_key,benefit_count,member_price_count,generated_at,expires_at,state) VALUES("
                + "'01K7V000000000000000000090','TENANT_A',1101,1,0,'1.0','member-benefit-engine-1.0.0',"
                + "REPEAT('a',64),'Ed25519','synthetic-key-v1','tenant/TENANT_A/member/package/1',1,1,"
                + "UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 1 DAY,'AVAILABLE')");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE prm_member_benefit_package SET state='RETIRED' "
                + "WHERE package_id='01K7V000000000000000000090'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM prm_member_benefit_package "
                + "WHERE package_id='01K7V000000000000000000090'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        }
    }

    /** 验证开户历史不可覆盖、不可删除，并由数据库条件更新兜住并发配额上限。 */
    private void assertSaasHistoryAndQuotaAreProtected() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO saas_plan(plan_id,plan_code,plan_name,platform_package_id,"
                + "account_limit,status,created_at,updated_at) VALUES(801,'SYNTHETIC_V1','虚构套餐',1,50,"
                + "'ACTIVE',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO saas_entitlement_version(version_id,plan_id,version_no,state,"
                + "effective_at,content_sha256,creator_user_id,record_version,created_at,updated_at) VALUES("
                + "'01K80000000000000000000801',801,1,'EFFECTIVE',UTC_TIMESTAMP(3)-INTERVAL 1 DAY,"
                + "REPEAT('a',64),1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO saas_merchant_application(application_id,application_code,"
                + "tenant_id,technical_tenant_id,company_name,industry,plan_id,state,submitter_user_id,"
                + "approver_user_id,record_version,content_sha256,created_at,updated_at) VALUES("
                + "'01K80000000000000000000802','SYNTHETIC-APP-801','TENANT_A',9,'虚构商户','CONVENIENCE',"
                + "801,'ACTIVE',1,2,4,REPEAT('b',64),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO saas_application_state_event(event_id,application_id,tenant_id,"
                + "from_state,to_state,request_sha256,correlation_id,actor_user_id,occurred_at) VALUES("
                + "'01K80000000000000000000803','01K80000000000000000000802','TENANT_A','INITIALIZING',"
                + "'ACTIVE',REPEAT('c',64),'trace-saa-801',2,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE saas_application_state_event SET "
                + "to_state='FAILED' WHERE event_id='01K80000000000000000000803'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM saas_application_state_event WHERE "
                + "event_id='01K80000000000000000000803'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            statement.executeUpdate("INSERT INTO saas_quota_usage(tenant_id,feature_code,used_count,quota_limit,"
                + "updated_at) VALUES('TENANT_A','STORE_COUNT',1,2,UTC_TIMESTAMP(3))");
            assertThat(statement.executeUpdate("UPDATE saas_quota_usage SET used_count=used_count+1 WHERE "
                + "tenant_id='TENANT_A' AND feature_code='STORE_COUNT' AND used_count+1<=quota_limit")).isEqualTo(1);
            assertThat(statement.executeUpdate("UPDATE saas_quota_usage SET used_count=used_count+1 WHERE "
                + "tenant_id='TENANT_A' AND feature_code='STORE_COUNT' AND used_count+1<=quota_limit")).isZero();
        }
    }

    private static String required(String primary, String compatibility) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) value = System.getenv(compatibility);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(primary + " or " + compatibility + " must be provided by CI");
        }
        return value;
    }
}
