package com.jingshanghui.pos.operations.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在干净 MySQL 8.4 验证 V71-V72、租户复合外键、只追加事实和关闭后不可变。 */
class DailyCloseMySqlIT {
    private final String url=required("GATE7D_CLS_MYSQL_JDBC_URL");
    private final String username=required("GATE7D_CLS_MYSQL_USERNAME");
    private final String password=required("GATE7D_CLS_MYSQL_PASSWORD");

    @Test
    void migratesRepeatablyAndEnforcesDailyCloseOwnerGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway=Flyway.configure().dataSource(url,username,password).locations("classpath:db/migration")
            .table("jshpos_flyway_schema_history").baselineOnMigrate(true).baselineVersion("0")
            .cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608230072");
        assertSchema();
        assertTenantAndAppendOnlyGuards();
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
            s.executeUpdate("""
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
        Set<String> tables=Set.of("ops_daily_close","ops_daily_close_snapshot","ops_daily_close_checkpoint",
            "ops_daily_close_preflight","ops_daily_close_difference","ops_daily_close_approval",
            "ops_daily_close_signature","ops_daily_close_command_result","ops_daily_close_state_event",
            "ops_daily_close_audit","ops_daily_close_outbox");
        try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
            for(String table:tables)try(var rows=c.getMetaData().getTables(c.getCatalog(),null,table,new String[]{"TABLE"})){
                assertThat(rows.next()).as("table %s",table).isTrue();
            }
            try(var rows=s.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200570 AND 9200575")){
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(6);
            }
            try(var rows=s.executeQuery("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 'trg_ops_%'")){
                assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isGreaterThanOrEqualTo(19);
            }
        }
    }

    private void assertTenantAndAppendOnlyGuards() throws SQLException {
        try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
            s.executeUpdate("""
              INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version)
              VALUES(1001,'TENANT_A','HQ-A','A总部','HEADQUARTERS','ACTIVE',1,0),
                    (2001,'TENANT_B','HQ-B','B总部','HEADQUARTERS','ACTIVE',1,0)
              """);
            s.executeUpdate("""
              INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,status,version)
              VALUES(1101,'TENANT_A',1001,'STORE-A','A门店','Asia/Shanghai','ACTIVE',1),
                    (2101,'TENANT_B',2001,'STORE-B','B门店','Asia/Shanghai','ACTIVE',1)
              """);
            s.executeUpdate("""
              INSERT INTO ops_daily_close(close_id,tenant_id,store_id,business_date,zone_id,business_day_start,
                close_version,correction_of_close_id,correction_reason_sha256,state,snapshot_sha256,manifest_sha256,
                idempotency_key,request_sha256,creator_user_id,created_at,updated_at)
              VALUES('01K3M000000000000000000001','TENANT_A',1101,'2026-08-23','Asia/Shanghai','03:00:00',1,
                NULL,NULL,'DRAFT',REPEAT('0',64),REPEAT('0',64),'cls-create-001',REPEAT('a',64),101,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
              """);
            assertThatThrownBy(() -> s.executeUpdate("""
              INSERT INTO ops_daily_close(close_id,tenant_id,store_id,business_date,zone_id,business_day_start,
                close_version,state,snapshot_sha256,manifest_sha256,idempotency_key,request_sha256,creator_user_id,created_at,updated_at)
              VALUES('01K3M000000000000000000002','TENANT_A',2101,'2026-08-23','Asia/Shanghai','03:00:00',1,
                'DRAFT',REPEAT('0',64),REPEAT('0',64),'cls-create-002',REPEAT('b',64),101,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
              """)).isInstanceOf(SQLException.class);
            s.executeUpdate("""
              INSERT INTO ops_daily_close_snapshot(snapshot_id,tenant_id,close_id,run_no,currency,order_count,
                cancelled_order_count,return_count,gross_minor,discount_minor,surcharge_minor,receivable_minor,
                refund_minor,cash_received_minor,cash_refunded_minor,electronic_received_minor,electronic_refunded_minor,
                unknown_payment_count,unknown_refund_count,shift_difference_minor,content_sha256,created_at)
              VALUES('01K3M000000000000000000003','TENANT_A','01K3M000000000000000000001',1,'CNY',1,0,0,
                1000,100,20,920,0,920,0,0,0,0,0,0,REPEAT('c',64),UTC_TIMESTAMP(6))
              """);
            assertThatThrownBy(() -> s.executeUpdate("UPDATE ops_daily_close SET store_id=2101 WHERE close_id='01K3M000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable identity");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM ops_daily_close_snapshot WHERE snapshot_id='01K3M000000000000000000003'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            s.executeUpdate("UPDATE ops_daily_close SET state='CLOSED',record_version=1 WHERE close_id='01K3M000000000000000000001'");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE ops_daily_close SET state='FAILED',record_version=2 WHERE close_id='01K3M000000000000000000001'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("closed fact");
        }
    }

    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" must be provided by CI");return value;}
}
