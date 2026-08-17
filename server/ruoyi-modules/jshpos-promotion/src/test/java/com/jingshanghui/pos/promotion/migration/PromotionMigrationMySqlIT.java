package com.jingshanghui.pos.promotion.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 执行 V1—V23 并验证 PRM-001/002/003 强约束。 */
class PromotionMigrationMySqlIT {
    private final String url = required("GATE5A_MYSQL_JDBC_URL");
    private final String username = required("GATE5A_MYSQL_USERNAME");
    private final String password = required("GATE5A_MYSQL_PASSWORD");

    @Test
    void migratesTwentyThreeVersionsAndEnforcesPromotionFacts() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(23);
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
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(16);
            }
            try (var rows = statement.executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name LIKE 'prm_%' AND column_comment=''")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9200900 AND 9200914")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(15);
            }
            seed(statement);
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE prm_quote SET payable_amount_minor=99 WHERE tenant_id='TENANT_A' AND quote_id='01K5Q000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE prm_manual_price_audit SET incremental_discount_minor=9 WHERE tenant_id='TENANT_A' AND manual_event_id='01K5M000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE prm_transaction_snapshot SET payable_amount_minor=89 WHERE tenant_id='TENANT_A' AND snapshot_id='01K5S000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate(
                "UPDATE prm_refund_allocation_ledger SET payable_amount_minor=31 WHERE tenant_id='TENANT_A' AND refund_allocation_id='01K5F000000000000000000001'"))
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
        statement.executeUpdate("INSERT INTO prm_quote_line(quote_line_id,tenant_id,quote_id,source_line_id,line_no,sku_id,quantity,unit_price_minor,gross_amount_minor,discount_amount_minor,payable_amount_minor) VALUES('01K5L000000000000000000001','TENANT_A','01K5Q000000000000000000001','01K5L000000000000000000002',1,101,1.000000,100,100,10,90)");
        statement.executeUpdate("INSERT INTO prm_manual_price_audit(manual_event_id,tenant_id,authorization_id,event_sequence,state,command_id,request_sha256,quote_id,store_id,terminal_id,action_type,amount_or_rate,payment_method,before_fingerprint,preview_fingerprint,incremental_discount_minor,policy_version_id,policy_sha256,without_approval_minor,with_approval_minor,minimum_line_payable_minor,maximum_rounding_minor,rounding_multiples_json,reason_code,reason_text,operator_user_id,business_date,correlation_id,result_json,result_sha256,occurred_at) VALUES('01K5M000000000000000000001','TENANT_A','01K5M000000000000000000002',1,'APPLIED','01K5M000000000000000000003',REPEAT('a',64),'01K5Q000000000000000000001',1101,'TERM-A','ORDER_AMOUNT_OFF','1','NON_CASH',REPEAT('b',64),REPEAT('c',64),1,31,REPEAT('d',64),100,1000,20,9,JSON_ARRAY(1,10),'SYNTHETIC','synthetic reason',1,CURRENT_DATE,'01K5M000000000000000000004',JSON_OBJECT('payableAmountMinor',89),REPEAT('e',64),UTC_TIMESTAMP(3))");
        statement.executeUpdate("INSERT INTO prm_transaction_snapshot(snapshot_id,tenant_id,order_id,quote_id,store_id,terminal_id,business_date,currency,quote_fingerprint,snapshot_sha256,gross_amount_minor,discount_amount_minor,payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES('01K5S000000000000000000001','TENANT_A','01K5N000000000000000000001','01K5Q000000000000000000001',1101,'TERM-A',CURRENT_DATE,'CNY',REPEAT('a',64),REPEAT('b',64),100,10,90,1,'01K5S000000000000000000002',UTC_TIMESTAMP(3))");
        statement.executeUpdate("INSERT INTO prm_transaction_allocation(allocation_id,tenant_id,snapshot_id,line_id,line_no,sku_id,quantity,gross_amount_minor,discount_amount_minor,payable_amount_minor,source_allocations_json,source_allocations_sha256) VALUES('01K5A000000000000000000001','TENANT_A','01K5S000000000000000000001','01K5L000000000000000000002',1,101,1.000000,100,10,90,JSON_OBJECT('01K5R000000000000000000002',10),REPEAT('c',64))");
        statement.executeUpdate("INSERT INTO prm_refund_allocation_ledger(refund_allocation_id,tenant_id,snapshot_id,refund_id,line_id,command_id,request_sha256,quantity,gross_amount_minor,discount_amount_minor,payable_amount_minor,cumulative_quantity,cumulative_gross_amount_minor,cumulative_discount_amount_minor,cumulative_payable_amount_minor,actor_user_id,correlation_id,occurred_at) VALUES('01K5F000000000000000000001','TENANT_A','01K5S000000000000000000001','01K5F000000000000000000002','01K5L000000000000000000002','01K5F000000000000000000003',REPEAT('d',64),0.500000,50,5,45,0.500000,50,5,45,1,'01K5F000000000000000000004',UTC_TIMESTAMP(3))");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be provided by CI");
        return value;
    }
}
