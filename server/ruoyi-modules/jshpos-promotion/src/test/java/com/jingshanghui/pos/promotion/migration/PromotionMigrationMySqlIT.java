package com.jingshanghui.pos.promotion.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 执行 V1—V21 并验证 Gate 5A 强约束。 */
class PromotionMigrationMySqlIT {
    private final String url = required("GATE5A_MYSQL_JDBC_URL");
    private final String username = required("GATE5A_MYSQL_USERNAME");
    private final String password = required("GATE5A_MYSQL_PASSWORD");

    @Test
    void migratesTwentyOneVersionsAndEnforcesPromotionFacts() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(21);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesColumnsPermissionsAndImmutability();
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

    private void assertTablesColumnsPermissionsAndImmutability() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'prm_%'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(12);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'prm_%' AND column_comment=''")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200900 AND 9200914")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(9);
            }
            seed(statement);
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE prm_quote SET payable_amount_minor=99 WHERE tenant_id='TENANT_A' AND quote_id='01K5Q000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate(
                "INSERT INTO prm_rule_version(rule_version_id,tenant_id,rule_id,version_no,rule_type,priority,stack_mode,effective_from,state,content_sha256,engine_version,created_by) VALUES('01K5R000000000000000000099','TENANT_B','01K5R000000000000000000001',2,'AMOUNT_OFF',1,'EXCLUSIVE',UTC_TIMESTAMP(3),'DRAFT',REPEAT('b',64),'promotion-engine-1.0.0',1)"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void seed(Statement statement) throws SQLException {
        statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0)");
        statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES(1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
        statement.executeUpdate("INSERT INTO prm_rule(rule_id,tenant_id,rule_code,rule_name,status,created_by) VALUES('01K5R000000000000000000001','TENANT_A','A_RULE','A Rule','ACTIVE',1)");
        statement.executeUpdate("INSERT INTO prm_rule_version(rule_version_id,tenant_id,rule_id,version_no,rule_type,priority,stack_mode,effective_from,state,content_sha256,engine_version,created_by) VALUES('01K5R000000000000000000002','TENANT_A','01K5R000000000000000000001',1,'AMOUNT_OFF',1,'EXCLUSIVE',UTC_TIMESTAMP(3),'PUBLISHED',REPEAT('a',64),'promotion-engine-1.0.0',1)");
        statement.executeUpdate("INSERT INTO prm_rule_package(package_id,tenant_id,store_id,package_version,previous_version,schema_version,engine_version,payload_sha256,signature_algorithm,signing_key_id,object_key,record_count,generated_at,expires_at,state) VALUES('01K5P000000000000000000001','TENANT_A',1101,1,0,'1.0','promotion-engine-1.0.0',REPEAT('e',64),'Ed25519','synthetic-key-v1','tenant/TENANT_A/object/package',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 1 DAY,'AVAILABLE')");
        statement.executeUpdate("INSERT INTO prm_quote(quote_id,tenant_id,store_id,terminal_id,idempotency_key,request_sha256,engine_version,package_version,business_time,gross_amount_minor,discount_amount_minor,payable_amount_minor,currency,result_sha256) VALUES('01K5Q000000000000000000001','TENANT_A',1101,'TERM-A','KEY-A',REPEAT('c',64),'promotion-engine-1.0.0',1,UTC_TIMESTAMP(3),100,10,90,'CNY',REPEAT('d',64))");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
