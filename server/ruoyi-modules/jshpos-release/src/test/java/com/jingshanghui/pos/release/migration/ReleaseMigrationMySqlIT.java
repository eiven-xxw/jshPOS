package com.jingshanghui.pos.release.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.*;

/** Gate 8C MySQL 8.4 全 Owner 空库迁移、元数据、权限、冻结身份、状态机和只追加保护集成测试。 */
class ReleaseMigrationMySqlIT {
    private final String url = required("GATE6B_MYSQL_JDBC_URL");
    private final String username = required("GATE6B_MYSQL_USERNAME");
    private final String password = required("GATE6B_MYSQL_PASSWORD");

    @Test void migratesAllVersionsAndEnforcesReleaseGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url,username,password).locations(migrationLocations())
            .table("jshpos_flyway_schema_history").baselineOnMigrate(true).baselineVersion("0")
            .cleanDisabled(true).load();
        Set<String> expected = expectedVersions();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(expected.size());
        Set<String> applied = Arrays.stream(flyway.info().applied()).map(info -> info.getVersion().getVersion())
            .collect(Collectors.toSet());
        assertThat(applied.remove("0")).isTrue();
        assertThat(applied).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608240086");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertGate6GDataMetadata();
        assertSchemaAndGuards();
        assertHundredThousandAppendOnlyEventsRemainQueryable();
    }

    /** 验证所有正式表的主键、中文表说明和租户隔离列；备份控制面五表使用冻结租户集合。 */
    private void assertGate6GDataMetadata() throws SQLException {
        Set<String> controlPlane = Set.of("bak_backup_set", "bak_backup_object", "bak_restore_drill",
            "bak_restore_check", "bak_audit");
        Set<String> tables = new LinkedHashSet<>();
        try (Connection c = DriverManager.getConnection(url, username, password);
             PreparedStatement query = c.prepareStatement("SELECT table_name,table_comment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name NOT IN ('sys_menu','jshpos_flyway_schema_history') ORDER BY table_name");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String table = rows.getString(1);
                String comment = rows.getString(2);
                tables.add(table);
                assertThat(comment.codePoints().anyMatch(code -> code >= 0x4E00 && code <= 0x9FFF))
                    .as("Chinese table comment for %s", table).isTrue();
                try (ResultSet primary = c.getMetaData().getPrimaryKeys(c.getCatalog(), null, table)) {
                    assertThat(primary.next()).as("primary key for %s", table).isTrue();
                }
            }
        }
        assertThat(tables).hasSize(287);
        try (Connection c = DriverManager.getConnection(url, username, password);
             PreparedStatement query = c.prepareStatement("SELECT t.table_name FROM information_schema.tables t LEFT JOIN information_schema.columns c ON c.table_schema=t.table_schema AND c.table_name=t.table_name AND c.column_name='tenant_id' WHERE t.table_schema=DATABASE() AND t.table_type='BASE TABLE' AND t.table_name NOT IN ('sys_menu','jshpos_flyway_schema_history') AND c.column_name IS NULL ORDER BY t.table_name");
             ResultSet rows = query.executeQuery()) {
            Set<String> withoutTenant = new LinkedHashSet<>();
            while (rows.next()) withoutTenant.add(rows.getString(1));
            assertThat(withoutTenant).containsExactlyInAnyOrderElementsOf(controlPlane);
        }
    }

    private void assertHundredThousandAppendOnlyEventsRemainQueryable() throws SQLException {
        long started=System.nanoTime();
        try(Connection c=DriverManager.getConnection(url,username,password);
            PreparedStatement insert=c.prepareStatement("INSERT INTO upg_release_event(event_id,tenant_id,aggregate_type,aggregate_id,event_type,from_state,to_state,evidence_sha256,correlation_id,occurred_at) VALUES(?,'TENANT_A','RELEASE','01K6A000000000000000000001','CAPACITY_EVENT','STAGED','STAGED',REPEAT('8',64),'01K6A000000000000000000019',UTC_TIMESTAMP(3))")) {
            c.setAutoCommit(false);
            for(int index=0;index<100_000;index++) {
                insert.setString(1,"01K6B"+String.format("%021d",index)); insert.addBatch();
                if((index+1)%1000==0) insert.executeBatch();
            }
            c.commit();
        }
        try(Connection c=DriverManager.getConnection(url,username,password); PreparedStatement query=c.prepareStatement(
            "SELECT COUNT(*) FROM upg_release_event WHERE tenant_id='TENANT_A' AND aggregate_type='RELEASE' AND aggregate_id='01K6A000000000000000000001'")) {
            try(ResultSet rows=query.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(100_000); }
        }
        assertThat((System.nanoTime()-started)/1_000_000_000L).isLessThan(60);
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try(Connection c=DriverManager.getConnection(url,username,password); Statement s=c.createStatement()) {
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

    private void assertSchemaAndGuards() throws SQLException {
        String release="01K6A000000000000000000001", rollout="01K6A000000000000000000002";
        String task="01K6A000000000000000000003", device="01K6A000000000000000000004";
        try(Connection c=DriverManager.getConnection(url,username,password); Statement s=c.createStatement()) {
            for(String table:Set.of("upg_release","upg_target_scope","upg_rollout","upg_terminal_task",
                "upg_command_result","upg_release_event","upg_audit")) {
                try(ResultSet rows=c.getMetaData().getTables(c.getCatalog(),null,table,new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s",table).isTrue();
                }
            }
            try(ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9201400 AND 9201406")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(7);
            }
            try(ResultSet rows=s.executeQuery("SELECT COUNT(*) FROM sys_menu WHERE menu_id=9201500 AND component='operations/advanced/index' AND perms='operations:advanced:read'")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
            }
            s.executeUpdate("INSERT INTO upg_release(release_id,tenant_id,artifact_type,release_version,channel_code,object_key,artifact_sha256,signature_base64,key_version,build_commit,sbom_sha256,manifest_sha256,min_app_version,max_app_version,min_protocol_version,max_protocol_version,min_schema_version,max_schema_version,min_system_version,max_system_version,state,request_sha256,created_by,correlation_id,created_at,updated_at) VALUES('"+release+"','TENANT_A','APK','1.2.3','CANARY','releases/TENANT_A/app.apk',REPEAT('a',64),REPEAT('e',64),'synthetic-v1',REPEAT('c',40),REPEAT('b',64),REPEAT('d',64),'1.0','2.0','1.0','2.0','1.0','3.0','10.0','14.0','DRAFT',REPEAT('f',64),101,'01K6A000000000000000000005',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            s.executeUpdate("INSERT INTO upg_target_scope(scope_id,tenant_id,aggregate_type,aggregate_id,store_id,created_at) VALUES('01K6A000000000000000000006','TENANT_A','RELEASE','"+release+"',101,UTC_TIMESTAMP(3))");
            s.executeUpdate("UPDATE upg_release SET state='SIGNED',version_no=version_no+1,last_actor_id=101,last_correlation_id='01K6A000000000000000000007',updated_at=UTC_TIMESTAMP(3) WHERE release_id='"+release+"'");
            s.executeUpdate("UPDATE upg_release SET state='STAGED',version_no=version_no+1,last_actor_id=101,last_correlation_id='01K6A000000000000000000008',updated_at=UTC_TIMESTAMP(3) WHERE release_id='"+release+"'");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE upg_release SET artifact_sha256=REPEAT('0',64),version_no=version_no+1 WHERE release_id='"+release+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("frozen identity");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM upg_target_scope WHERE aggregate_id='"+release+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");

            s.executeUpdate("INSERT INTO upg_rollout(rollout_id,tenant_id,release_id,canary_percent,state,request_sha256,created_by,correlation_id,created_at,updated_at) VALUES('"+rollout+"','TENANT_A','"+release+"',10,'PLANNED',REPEAT('1',64),101,'01K6A000000000000000000009',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            s.executeUpdate("INSERT INTO upg_target_scope(scope_id,tenant_id,aggregate_type,aggregate_id,store_id,created_at) VALUES('01K6A000000000000000000010','TENANT_A','ROLLOUT','"+rollout+"',101,UTC_TIMESTAMP(3))");
            s.executeUpdate("UPDATE upg_rollout SET state='CANARY',version_no=version_no+1,last_actor_id=101,last_correlation_id='01K6A000000000000000000011',updated_at=UTC_TIMESTAMP(3) WHERE rollout_id='"+rollout+"'");
            s.executeUpdate("INSERT INTO upg_terminal_task(task_id,tenant_id,rollout_id,release_id,device_id,store_id,state,request_sha256,last_evidence_sha256,created_by,correlation_id,created_at,updated_at) VALUES('"+task+"','TENANT_A','"+rollout+"','"+release+"','"+device+"',101,'PLANNED',REPEAT('2',64),REPEAT('3',64),101,'01K6A000000000000000000012',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
            s.executeUpdate("UPDATE upg_terminal_task SET state='DOWNLOADING',version_no=version_no+1,last_actor_id=101,last_correlation_id='01K6A000000000000000000013',last_evidence_sha256=REPEAT('4',64),updated_at=UTC_TIMESTAMP(3) WHERE task_id='"+task+"'");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE upg_terminal_task SET state='SUCCEEDED',version_no=version_no+1 WHERE task_id='"+task+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("illegal transition");

            s.executeUpdate("INSERT INTO upg_command_result(command_id,tenant_id,command_type,idempotency_key,request_sha256,aggregate_id,result_code,actor_id,occurred_at) VALUES('01K6A000000000000000000014','TENANT_A','ASSIGN_TERMINAL','task:assign:0001',REPEAT('5',64),'"+task+"','PLANNED',101,UTC_TIMESTAMP(3))");
            s.executeUpdate("INSERT INTO upg_release_event(event_id,tenant_id,aggregate_type,aggregate_id,event_type,to_state,evidence_sha256,correlation_id,occurred_at) VALUES('01K6A000000000000000000015','TENANT_A','TASK','"+task+"','TASK_CREATED','PLANNED',REPEAT('6',64),'01K6A000000000000000000016',UTC_TIMESTAMP(3))");
            s.executeUpdate("INSERT INTO upg_audit(audit_id,tenant_id,aggregate_type,aggregate_id,action_code,after_state,evidence_sha256,actor_id,correlation_id,occurred_at) VALUES('01K6A000000000000000000017','TENANT_A','TASK','"+task+"','TASK_CREATED','PLANNED',REPEAT('7',64),101,'01K6A000000000000000000018',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE upg_command_result SET result_code='SUCCEEDED' WHERE aggregate_id='"+task+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM upg_release_event WHERE aggregate_id='"+task+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("cannot be deleted");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE upg_audit SET after_state='FAILED' WHERE aggregate_id='"+task+"'"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    private static Set<String> expectedVersions() {
        Set<String> versions=new LinkedHashSet<>();
        for(int v=1;v<=12;v++) versions.add("20260816"+String.format("%04d",v));
        versions.add("202608160036"); versions.add("202608160037");
        for(int v=13;v<=35;v++) versions.add("20260817"+String.format("%04d",v));
        versions.add("202608180038"); versions.add("202608180039");
        versions.add("202608200040"); versions.add("202608200041"); versions.add("202608200042");
        for(int v=43;v<=54;v++) versions.add("20260821"+String.format("%04d",v));
        for(int v=55;v<=65;v++) versions.add("20260822"+String.format("%04d",v));
        for(int v=66;v<=84;v++) versions.add("20260823"+String.format("%04d",v));
        versions.add("202608240085"); versions.add("202608240086");
        return versions;
    }

    /**
     * 从仓库正式 Owner 迁移目录聚合资源，避免仅由某个模块依赖闭包决定发布验收范围。
     * 该路径只用于 CI 空库验收，不改变应用运行时 Flyway 装配或任何已发布迁移。
     */
    private static String[] migrationLocations() throws Exception {
        Path root=Path.of(required("GATE8C_MIGRATION_ROOT")).toAbsolutePath().normalize();
        assertThat(root).isDirectory();
        try(var paths=Files.walk(root)) {
            List<String> locations=paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src","main","resources","db","migration")))
                .sorted().map(path -> "filesystem:"+path.toString().replace('\\','/')).toList();
            assertThat(locations).hasSize(22);
            return locations.toArray(String[]::new);
        }
    }
    private static String required(String name) { String value=System.getenv(name); if(value==null||value.isBlank()) throw new IllegalStateException(name+" must be provided by CI"); return value; }
}
