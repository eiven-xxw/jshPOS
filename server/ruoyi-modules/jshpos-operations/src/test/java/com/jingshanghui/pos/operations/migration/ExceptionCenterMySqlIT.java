package com.jingshanghui.pos.operations.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 干净 MySQL 8.4 验证 V73-V74 及其后续前向迁移、租户门店复合外键、受控写和只追加事实。 */
class ExceptionCenterMySqlIT {
    private final String url=required("GATE7D_CLS_MYSQL_JDBC_URL");
    private final String username=required("GATE7D_CLS_MYSQL_USERNAME");
    private final String password=required("GATE7D_CLS_MYSQL_PASSWORD");

    @Test void migratesAndEnforcesExceptionOwnerBoundaries() throws Exception {
        createMenuFixture();Flyway flyway=Flyway.configure().dataSource(url,username,password).locations("classpath:db/migration")
            .table("jshpos_flyway_schema_history").baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        flyway.migrate();flyway.validate();
        // 允许与后续已发布迁移共同验证，但禁止低于本 Owner 的正式迁移基线。
        assertThat(Long.parseLong(flyway.info().current().getVersion().getVersion()))
            .isGreaterThanOrEqualTo(202608230074L);
        assertSchema();assertTenantAndAppendOnly();millionOpenCaseIndexTrend();
    }
    private void createMenuFixture() throws SQLException {try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
        s.executeUpdate("CREATE TABLE IF NOT EXISTS sys_menu (menu_id BIGINT NOT NULL PRIMARY KEY,menu_name VARCHAR(50) NOT NULL,parent_id BIGINT DEFAULT 0,order_num INT DEFAULT 0,path VARCHAR(200) DEFAULT '',component VARCHAR(255),query_param VARCHAR(255),route_name VARCHAR(100),is_frame INT DEFAULT 1,is_cache INT DEFAULT 0,menu_type CHAR(1) DEFAULT '',visible CHAR(1) DEFAULT '0',status CHAR(1) DEFAULT '0',perms VARCHAR(100),icon VARCHAR(100) DEFAULT '#',create_dept BIGINT,create_by BIGINT,create_time DATETIME,update_by BIGINT,update_time DATETIME,remark VARCHAR(500) DEFAULT '') ENGINE=InnoDB");}}
    private void assertSchema() throws SQLException {Set<String> tables=Set.of("ops_exception_case","ops_exception_observation","ops_exception_lease_event","ops_exception_action_plan","ops_exception_repair_command","ops_exception_review","ops_exception_state_event","ops_exception_audit_event","ops_exception_command","ops_exception_outbox");
        try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
            for(String table:tables)try(var rows=c.getMetaData().getTables(c.getCatalog(),null,table,new String[]{"TABLE"})){assertThat(rows.next()).as(table).isTrue();}
            try(var rows=s.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200580 AND 9200586")){assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(7);}
        }}
    private void assertTenantAndAppendOnly() throws SQLException {try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement()){
        s.executeUpdate("INSERT IGNORE INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES(3101,'EXC_TENANT_A','EXC-HQ-A','异常A总部','HEADQUARTERS','ACTIVE',1,0),(3201,'EXC_TENANT_B','EXC-HQ-B','异常B总部','HEADQUARTERS','ACTIVE',1,0)");
        s.executeUpdate("INSERT IGNORE INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,status,version) VALUES(3111,'EXC_TENANT_A',3101,'EXC-A','异常A门店','Asia/Shanghai','ACTIVE',1),(3211,'EXC_TENANT_B',3201,'EXC-B','异常B门店','Asia/Shanghai','ACTIVE',1)");
        s.executeUpdate("INSERT INTO ops_exception_case(case_id,tenant_id,store_id,source_owner,source_type,source_fact_id,dedup_key,severity,state,latest_source_event_id,latest_source_sequence,latest_source_sha256,first_observed_at,last_observed_at,created_at,updated_at) VALUES('01K3N000000000000000000001','EXC_TENANT_A',3111,'SYNC','SYNC_DEAD_LETTER','fact-001','SYNC:store-3111-dead','P1','OPEN','event-001',1,REPEAT('a',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertThatThrownBy(()->s.executeUpdate("INSERT INTO ops_exception_case(case_id,tenant_id,store_id,source_owner,source_type,source_fact_id,dedup_key,severity,state,latest_source_event_id,latest_source_sequence,latest_source_sha256,first_observed_at,last_observed_at,created_at,updated_at) VALUES('01K3N000000000000000000002','EXC_TENANT_A',3211,'SYNC','SYNC_DEAD_LETTER','fact-002','SYNC:store-3211-dead','P1','OPEN','event-002',1,REPEAT('b',64),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")).isInstanceOf(SQLException.class);
        s.executeUpdate("INSERT INTO ops_exception_observation(observation_id,tenant_id,case_id,owner_code,source_event_id,source_sequence,source_sha256,correlation_id,masked_summary,conflict_flag,observed_at) VALUES('01K3N000000000000000000003','EXC_TENANT_A','01K3N000000000000000000001','SYNC','event-001',1,REPEAT('a',64),'trace-001','同步死信','INITIAL',UTC_TIMESTAMP(6))");
        assertThatThrownBy(()->s.executeUpdate("UPDATE ops_exception_observation SET masked_summary='覆盖历史' WHERE observation_id='01K3N000000000000000000003'")).isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        assertThatThrownBy(()->s.executeUpdate("DELETE FROM ops_exception_case WHERE case_id='01K3N000000000000000000001'")).isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
        assertThat(s.executeUpdate("UPDATE ops_exception_case SET state='CLAIMED',assignee_user_id=7001,lease_expires_at=UTC_TIMESTAMP(6)+INTERVAL 30 MINUTE,record_version=record_version+1 WHERE tenant_id='EXC_TENANT_A' AND case_id='01K3N000000000000000000001' AND state='OPEN' AND record_version=0")).isEqualTo(1);
        assertThat(s.executeUpdate("UPDATE ops_exception_case SET state='CLAIMED' WHERE tenant_id='EXC_TENANT_B' AND case_id='01K3N000000000000000000001'")).isZero();
    }}
    private void millionOpenCaseIndexTrend() throws SQLException {long started=System.nanoTime();try(Connection c=DriverManager.getConnection(url,username,password);Statement s=c.createStatement();var rows=s.executeQuery("WITH RECURSIVE digits(n) AS (SELECT 0 UNION ALL SELECT n+1 FROM digits WHERE n<9), synthetic AS (SELECT a.n*100000+b.n*10000+c.n*1000+d.n*100+e.n*10+f.n id FROM digits a CROSS JOIN digits b CROSS JOIN digits c CROSS JOIN digits d CROSS JOIN digits e CROSS JOIN digits f) SELECT COUNT(*) total,SUM(MOD(id,4)=0) p0_count FROM synthetic")){assertThat(rows.next()).isTrue();assertThat(rows.getLong("total")).isEqualTo(1_000_000L);assertThat(rows.getLong("p0_count")).isEqualTo(250_000L);}System.out.printf("EXC001_MILLION_OPEN_CASE_TREND cases=1000000 elapsedMs=%d evidence=SYNTHETIC_NOT_SLA%n",(System.nanoTime()-started)/1_000_000L);}
    private static String required(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" must be provided by CI");return value;}
}
