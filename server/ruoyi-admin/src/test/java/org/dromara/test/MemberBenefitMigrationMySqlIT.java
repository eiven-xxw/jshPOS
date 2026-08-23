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
 * 在全模块正式运行时类路径上执行 V1—V86，验证既有 Owner、SaaS、订阅与服务运营迁移可共同前向安装。
 * 该测试只由带受控 MySQL 8.4 服务的专属 CI Job 显式执行。
 */
@Tag("local")
class MemberBenefitMigrationMySqlIT {
    private final String url = required("GATE8A_SVC_MYSQL_JDBC_URL", "GATE8A_SUB_MYSQL_JDBC_URL", "GATE8A_SAA_MYSQL_JDBC_URL");
    private final String username = required("GATE8A_SVC_MYSQL_USERNAME", "GATE8A_SUB_MYSQL_USERNAME", "GATE8A_SAA_MYSQL_USERNAME");
    private final String password = required("GATE8A_SVC_MYSQL_PASSWORD", "GATE8A_SUB_MYSQL_PASSWORD", "GATE8A_SAA_MYSQL_PASSWORD");

    @Test
    void migratesUnifiedRuntimeThroughV86AndEnforcesOwnerFacts() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isGreaterThan(60);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("202608240086");
        assertPermissionMenuRangesAreReconciled();
        assertOwnerTablesTenantKeysCommentsAndTriggers();
        assertPackageMetadataIsImmutable();
        assertSaasHistoryAndQuotaAreProtected();
        assertSubscriptionHistoryAndAccessAreProtected();
        assertServiceHistoryAndAttachmentBoundaryAreProtected();
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
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu "
                + "WHERE menu_id BETWEEN 9201720 AND 9201728")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(9);
            }
            try (var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu "
                + "WHERE menu_id BETWEEN 9201780 AND 9201790")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(11);
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
            "saas_command_result", "saas_quota_usage", "saas_audit_event", "saas_outbox",
            "sub_subscription", "sub_subscription_term", "sub_subscription_state_event",
            "sub_notification_intent", "sub_schedule_checkpoint", "sub_command_result",
            "sub_audit_event", "sub_outbox", "saas_subscription_access", "saas_subscription_access_event",
            "svc_catalog_version", "svc_catalog_item", "svc_implementation_project", "svc_project_check_item",
            "svc_work_order", "svc_work_order_history", "svc_attachment", "svc_command_result",
            "svc_audit_event", "svc_outbox");
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

    /** 验证期限/状态/访问历史只追加，投影使用显式状态与来源版本。 */
    private void assertSubscriptionHistoryAndAccessAreProtected() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO sub_subscription(subscription_id,tenant_id,plan_id,entitlement_version_id,"
                + "contract_ref,external_order_ref,state,state_version,current_term_version,starts_at,ends_at,grace_ends_at,"
                + "business_time_zone,degradation_policy_version,content_sha256,created_at,updated_at) VALUES("
                + "'01K80000000000000000000810','TENANT_B',801,'01K80000000000000000000801','CONTRACT-810','ORDER-810',"
                + "'ACTIVE',2,1,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,UTC_TIMESTAMP(3)+INTERVAL 30 DAY,"
                + "UTC_TIMESTAMP(3)+INTERVAL 37 DAY,'Asia/Shanghai','RECOVERY-V1',REPEAT('d',64),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO sub_subscription_term(term_id,subscription_id,term_version,starts_at,ends_at,"
                + "grace_ends_at,business_time_zone,contract_ref,external_order_ref,term_sha256,created_at) VALUES("
                + "'01K80000000000000000000811','01K80000000000000000000810',1,UTC_TIMESTAMP(3)-INTERVAL 1 DAY,"
                + "UTC_TIMESTAMP(3)+INTERVAL 30 DAY,UTC_TIMESTAMP(3)+INTERVAL 37 DAY,'Asia/Shanghai','CONTRACT-810',"
                + "'ORDER-810',REPEAT('e',64),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO sub_subscription_state_event(event_id,tenant_id,subscription_id,from_state,"
                + "to_state,state_version,term_version,action_code,reason,request_sha256,correlation_id,actor_user_id,occurred_at) VALUES("
                + "'01K80000000000000000000812','TENANT_B','01K80000000000000000000810','PENDING_ACTIVATION','ACTIVE',2,1,"
                + "'ACTIVATE_SUBSCRIPTION','合成激活',REPEAT('f',64),'trace-sub-810',2,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO saas_subscription_access(tenant_id,subscription_id,access_mode,source_version,"
                + "source_sha256,record_version,created_at,updated_at) VALUES('TENANT_B','01K80000000000000000000810','NORMAL',2,"
                + "REPEAT('f',64),0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO saas_subscription_access_event(event_id,tenant_id,subscription_id,from_mode,to_mode,"
                + "source_version,source_sha256,correlation_id,occurred_at) VALUES('01K80000000000000000000813','TENANT_B',"
                + "'01K80000000000000000000810',NULL,'NORMAL',2,REPEAT('f',64),'trace-sub-810',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE sub_subscription_term SET contract_ref='CHANGED' "
                + "WHERE term_id='01K80000000000000000000811'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM sub_subscription_state_event WHERE "
                + "event_id='01K80000000000000000000812'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE saas_subscription_access_event SET to_mode='RECOVERY_ONLY' "
                + "WHERE event_id='01K80000000000000000000813'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
        }
    }

    /** 验证服务目录项、处理历史和审计均不可改写，附件表不保存正文或永久地址。 */
    private void assertServiceHistoryAndAttachmentBoundaryAreProtected() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO svc_catalog_version(catalog_id,tenant_id,catalog_code,version_no,"
                + "industry_template,catalog_name,state,content_sha256,creator_user_id,record_version,created_at,updated_at) VALUES("
                + "'01K80000000000000000000820','TENANT_A','SYNTHETIC_SERVICE',1,'CONVENIENCE','虚构服务目录','DRAFT',"
                + "REPEAT('a',64),1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO svc_catalog_item(item_id,tenant_id,catalog_id,item_code,item_name,"
                + "mandatory,sequence_no,created_at) VALUES('01K80000000000000000000821','TENANT_A',"
                + "'01K80000000000000000000820','STORE_CONFIG','门店配置核验',1,1,UTC_TIMESTAMP(3))");
            statement.executeUpdate("INSERT INTO svc_work_order_history(history_id,tenant_id,store_id,aggregate_type,"
                + "aggregate_id,action_code,from_state,to_state,note,request_sha256,correlation_id,actor_user_id,occurred_at) VALUES("
                + "'01K80000000000000000000822','TENANT_A',1101,'CATALOG','01K80000000000000000000820',"
                + "'CREATE_CATALOG',NULL,'DRAFT','合成目录事实',REPEAT('b',64),'trace-svc-820',1,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE svc_catalog_item SET item_name='覆盖历史' "
                + "WHERE item_id='01K80000000000000000000821'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM svc_work_order_history WHERE "
                + "history_id='01K80000000000000000000822'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='svc_attachment' AND "
                + "column_name IN ('body','content','blob','public_url','download_url')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private static String required(String primary, String compatibility, String legacy) {
        String value = System.getenv(primary);
        if (value == null || value.isBlank()) value = System.getenv(compatibility);
        if (value == null || value.isBlank()) value = System.getenv(legacy);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(primary + ", " + compatibility + " or " + legacy + " must be provided by CI");
        }
        return value;
    }
}
