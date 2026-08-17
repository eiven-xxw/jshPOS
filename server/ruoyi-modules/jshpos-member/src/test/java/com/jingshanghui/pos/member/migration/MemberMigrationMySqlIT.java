package com.jingshanghui.pos.member.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gate 5C CI 在干净 MySQL 8.4 中验证全部 23 个迁移到 V31 和会员租户约束。 */
class MemberMigrationMySqlIT {
    private final String url=required("GATE5C_MYSQL_JDBC_URL");
    private final String username=required("GATE5C_MYSQL_USERNAME");
    private final String password=required("GATE5C_MYSQL_PASSWORD");

    @Test void migratesAllFilesThroughV31AndEnforcesMemberIsolation() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway=Flyway.configure().dataSource(url,username,password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(23);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("202608170031");
        assertTablesAndPermissions();
        assertTenantCompositeIdentity();
        assertPointsFactsAreImmutable();
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
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

    private void assertTablesAndPermissions() throws SQLException {
        Set<String> tables=Set.of("mbr_member","mbr_identity","mbr_consent_ledger","mbr_privacy_request",
            "mbr_privacy_history","mbr_member_link_ledger","mbr_command_result","mbr_event_outbox",
            "mbr_points_account","mbr_points_ledger","mbr_points_lot","mbr_points_allocation","mbr_level_history");
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            for(String table:tables) try(var rows=connection.getMetaData().getTables(connection.getCatalog(),null,
                table,new String[]{"TABLE"})) { assertThat(rows.next()).as("table %s",table).isTrue(); }
            try(var rows=statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9201100 AND 9201116")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(17);
            }
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name LIKE 'syn\\_%'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertTenantCompositeIdentity() throws SQLException {
        String member="01K5C000000000000000000001"; String identity="01K5C000000000000000000003";
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO mbr_member(member_id,tenant_id,state,display_alias,created_by) VALUES"
                +"('"+member+"','TENANT_A','ACTIVE','会员-000001',7),"
                +"('"+member+"','TENANT_B','ACTIVE','会员-000001',8)");
            statement.executeUpdate("INSERT INTO mbr_identity(identity_id,tenant_id,member_id,identity_type,"
                +"lookup_hmac,cipher_text,masked_value,key_version,state,bound_by,bound_at) VALUES('"+identity
                +"','TENANT_A','"+member+"','CARD',REPEAT('a',64),'synthetic-cipher','SY****01',1,'ACTIVE',7,UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO mbr_identity(identity_id,tenant_id,member_id,identity_type,"
                +"lookup_hmac,cipher_text,masked_value,key_version,state,bound_by,bound_at) VALUES('01K5C000000000000000000009',"
                +"'TENANT_A','"+member+"','CARD',REPEAT('a',64),'other','SY****01',1,'ACTIVE',7,UTC_TIMESTAMP(3))"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO mbr_identity(identity_id,tenant_id,member_id,identity_type,"
                +"lookup_hmac,cipher_text,masked_value,key_version,state,bound_by,bound_at) VALUES('"+identity
                +"','TENANT_B','"+member+"','CARD',REPEAT('a',64),'synthetic-cipher','SY****01',1,'ACTIVE',8,UTC_TIMESTAMP(3))");
        }
    }

    private void assertPointsFactsAreImmutable() throws SQLException {
        String member="01K5C000000000000000000001";
        String ledger="01K5C000000000000000000011";
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type) "
                +"VALUES(501,'TENANT_A','HQ-A','Synthetic HQ A','HEADQUARTERS')");
            statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,status) "
                +"VALUES(1001,'TENANT_A',501,'S-A','Synthetic Store A','Asia/Shanghai','ACTIVE')");
            statement.executeUpdate("INSERT INTO mbr_points_ledger(ledger_id,tenant_id,member_id,event_type,amount,"
                +"available_delta,frozen_delta,debt_delta,source_type,source_id,policy_version,idempotency_key,"
                +"request_sha256,correlation_id,store_id,business_date,reason_code,actor_user_id,occurred_at,content_sha256) VALUES('"+ledger+"','TENANT_A','"
                +member+"','EARN',1,1,0,0,'ORDER','01K5C000000000000000000012','points-v1',"
                +"'01K5C000000000000000000013',REPEAT('b',64),'01K5C000000000000000000014',1001,'2026-08-17',"
                +"'ORDER_EARN',7,UTC_TIMESTAMP(3),REPEAT('c',64))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE mbr_points_ledger SET amount=2 WHERE tenant_id='TENANT_A' AND ledger_id='"+ledger+"'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM mbr_points_ledger WHERE tenant_id='TENANT_A' AND ledger_id='"+ledger+"'"))
                .isInstanceOf(SQLException.class);
        }
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException(name+" must be provided by CI");
        return value;
    }
}
