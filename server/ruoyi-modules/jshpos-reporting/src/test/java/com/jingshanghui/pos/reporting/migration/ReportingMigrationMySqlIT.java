package com.jingshanghui.pos.reporting.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gate 5D CI 在干净 MySQL 8.4 中验证全量迁移、租户复合身份和来源事件不可变性。 */
class ReportingMigrationMySqlIT {
    private final String url=required("GATE5D_MYSQL_JDBC_URL");
    private final String username=required("GATE5D_MYSQL_USERNAME");
    private final String password=required("GATE5D_MYSQL_PASSWORD");

    @Test void migratesAllFilesThroughV35AndEnforcesReportingIsolation() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway=Flyway.configure().dataSource(url,username,password)
            .locations("classpath:db/migration").table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        // 当前模块 classpath 明确包含 Gate 0 两个基线迁移与 Reporting V32-V35，
        // 版本号不是连续文件数量，必须同时校验实际文件数和最高版本。
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("202608170035");
        assertTablesAndPermissions();
        assertTenantCompositeIdentityAndImmutableSourceContent();
        assertMoneyConservationConstraint();
        assertMillionRowProjectionCapacity();
        assertReconciliationTenantIdentityAndImmutability();
        assertHundredThousandReconciliationCapacity();
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
        Set<String> tables=Set.of("rpt_source_event_inbox","rpt_projection_checkpoint",
            "rpt_projection_registry","rpt_sales_daily","rpt_inventory_cost_daily","rpt_difference",
            "rpt_projection_lineage","rpt_projection_rebuild","rpt_export_request","rpt_export_artifact",
            "rpt_payment_fact_inbox","rpt_internal_bill_inbox","rpt_payment_reconciliation",
            "rpt_reconciliation_audit");
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            for(String table:tables) try(var rows=connection.getMetaData().getTables(connection.getCatalog(),null,
                table,new String[]{"TABLE"})) { assertThat(rows.next()).as("table %s",table).isTrue(); }
            try(var rows=statement.executeQuery(
                "SELECT COUNT(*) FROM sys_menu WHERE menu_id BETWEEN 9201117 AND 9201129")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(13);
            }
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                +"WHERE table_schema=DATABASE() AND table_name LIKE 'syn\\_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isZero();
            }
        }
    }

    private void assertTenantCompositeIdentityAndImmutableSourceContent() throws SQLException {
        String sourceEventId="01K5D000000000000000000001";
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            statement.executeUpdate(sourceInsert("TENANT_A",sourceEventId,100,10,0,90));
            statement.executeUpdate(sourceInsert("TENANT_B",sourceEventId,100,10,0,90));
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM rpt_source_event_inbox WHERE source_event_id='"
                +sourceEventId+"'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            statement.executeUpdate("UPDATE rpt_source_event_inbox SET status='APPLIED',applied_at=UTC_TIMESTAMP(3) "
                +"WHERE tenant_id='TENANT_A' AND source_event_id='"+sourceEventId+"'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE rpt_source_event_inbox SET gross_minor=101 "
                +"WHERE tenant_id='TENANT_A' AND source_event_id='"+sourceEventId+"'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM rpt_source_event_inbox "
                +"WHERE tenant_id='TENANT_A' AND source_event_id='"+sourceEventId+"'"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void assertMoneyConservationConstraint() throws SQLException {
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sourceInsert("TENANT_A",
                "01K5D000000000000000000002",100,10,0,91))).isInstanceOf(SQLException.class);
        }
    }

    private void assertMillionRowProjectionCapacity() throws SQLException {
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
            +"UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String sequence="(SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+100000*f.n n FROM "
            +digits+" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits
            +" d CROSS JOIN "+digits+" e CROSS JOIN "+digits+" f) seq";
        long started=System.nanoTime();
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            int inserted=statement.executeUpdate("INSERT INTO rpt_sales_daily(tenant_id,projection_version,"
                +"business_date,org_id,store_id,terminal_id,cashier_id,currency,order_count,gross_minor,"
                +"discount_minor,surcharge_minor,receivable_minor,projection_status) SELECT 'TENANT_PERF','g5d-v1',"
                +"'2026-08-17',1,1+MOD(seq.n,10),CONCAT('TP-',seq.n),1+MOD(seq.n,1000),'CNY',1,100,10,0,90,"
                +"'CURRENT' FROM "+sequence);
            assertThat(inserted).isEqualTo(1_000_000);
            try(var rows=statement.executeQuery("SELECT COUNT(*),SUM(order_count),SUM(receivable_minor) "
                +"FROM rpt_sales_daily WHERE tenant_id='TENANT_PERF' AND projection_version='g5d-v1' "
                +"AND business_date='2026-08-17'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(1_000_000);
                assertThat(rows.getLong(2)).isEqualTo(1_000_000);
                assertThat(rows.getLong(3)).isEqualTo(90_000_000);
            }
            statement.executeUpdate("DELETE FROM rpt_sales_daily WHERE tenant_id='TENANT_PERF'");
        }
        long elapsedMillis=(System.nanoTime()-started)/1_000_000;
        assertThat(elapsedMillis).as("one-million-row insert/query/delete baseline ms").isLessThan(180_000);
    }

    private void assertReconciliationTenantIdentityAndImmutability() throws SQLException {
        String key="01K5D000000000000000000010";
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            statement.executeUpdate(paymentFactInsert("TENANT_A","01K5D000000000000000000011",key,100));
            statement.executeUpdate(paymentFactInsert("TENANT_B","01K5D000000000000000000011",key,100));
            statement.executeUpdate(billInsert("TENANT_A","01K5D000000000000000000012",key,100));
            statement.executeUpdate(billInsert("TENANT_B","01K5D000000000000000000012",key,100));
            try(var rows=statement.executeQuery("SELECT COUNT(*) FROM rpt_payment_fact_inbox WHERE reconciliation_key='"
                +key+"'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE rpt_payment_fact_inbox SET amount_minor=101 "
                +"WHERE tenant_id='TENANT_A' AND reconciliation_key='"+key+"'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM rpt_internal_bill_inbox "
                +"WHERE tenant_id='TENANT_A' AND reconciliation_key='"+key+"'"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO rpt_reconciliation_audit(audit_id,tenant_id,reconciliation_id,"
                +"action_type,to_difference_type,to_handling_state,operator_id,reason_sha256,correlation_id,occurred_at) "
                +"VALUES('01K5D000000000000000000013','TENANT_A','"+key+"','SYSTEM_CLASSIFIED','MATCHED',"
                +"'MATCHED',0,REPEAT('c',64),'01K5D000000000000000000014',UTC_TIMESTAMP(3))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE rpt_reconciliation_audit SET operator_id=7 "
                +"WHERE tenant_id='TENANT_A' AND audit_id='01K5D000000000000000000013'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM rpt_reconciliation_audit "
                +"WHERE tenant_id='TENANT_A' AND audit_id='01K5D000000000000000000013'"))
                .isInstanceOf(SQLException.class);
        }
    }

    private void assertHundredThousandReconciliationCapacity() throws SQLException {
        String digits="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
            +"UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String sequence="(SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n n FROM "+digits
            +" a CROSS JOIN "+digits+" b CROSS JOIN "+digits+" c CROSS JOIN "+digits
            +" d CROSS JOIN "+digits+" e) seq";
        long started=System.nanoTime();
        try(Connection connection=DriverManager.getConnection(url,username,password);
            Statement statement=connection.createStatement()) {
            int facts=statement.executeUpdate("INSERT INTO rpt_payment_fact_inbox(source_event_id,tenant_id,"
                +"source_owner,source_sequence,partition_key,schema_version,content_sha256,occurred_at,business_date,"
                +"org_id,store_id,terminal_id,fact_type,reconciliation_key,order_id,amount_minor,currency,"
                +"lifecycle_status,correlation_id,applied_at) SELECT CONCAT('F',LPAD(seq.n,25,'0')),'TENANT_RECON',"
                +"'PAYMENT',seq.n,CONCAT('store:',MOD(seq.n,10)),'1.0',SHA2(CONCAT('fact:',seq.n),256),"
                +"UTC_TIMESTAMP(3),'2026-08-17',1,1+MOD(seq.n,10),CONCAT('T-',MOD(seq.n,100)),'PAYMENT',"
                +"CONCAT('R',LPAD(seq.n,25,'0')),CONCAT('O',LPAD(seq.n,25,'0')),100+MOD(seq.n,100),'CNY',"
                +"'SUCCEEDED',CONCAT('C',LPAD(seq.n,25,'0')),UTC_TIMESTAMP(3) FROM "+sequence);
            int bills=statement.executeUpdate("INSERT INTO rpt_internal_bill_inbox(bill_entry_id,tenant_id,batch_id,"
                +"source_type,synthetic,schema_version,content_sha256,business_date,org_id,store_id,terminal_id,"
                +"fact_type,reconciliation_key,amount_minor,currency,lifecycle_status,correlation_id,imported_by,"
                +"imported_at) SELECT CONCAT('B',LPAD(seq.n,25,'0')),'TENANT_RECON',"
                +"'01K5D000000000000000000015','INTERNAL_SYNTHETIC',1,'1.0',SHA2(CONCAT('bill:',seq.n),256),"
                +"'2026-08-17',1,1+MOD(seq.n,10),CONCAT('T-',MOD(seq.n,100)),'PAYMENT',"
                +"CONCAT('R',LPAD(seq.n,25,'0')),100+MOD(seq.n,100),'CNY','SUCCEEDED',"
                +"CONCAT('C',LPAD(seq.n,25,'0')),7,UTC_TIMESTAMP(3) FROM "+sequence);
            int projections=statement.executeUpdate("INSERT INTO rpt_payment_reconciliation(reconciliation_id,"
                +"tenant_id,reconciliation_key,fact_type,source_event_id,bill_entry_id,business_date,org_id,store_id,"
                +"terminal_id,currency,internal_amount_minor,bill_amount_minor,internal_status,bill_status,"
                +"internal_business_date,bill_business_date,difference_type,handling_state,source_content_sha256,"
                +"bill_content_sha256,detected_at,updated_at) SELECT f.reconciliation_key,f.tenant_id,"
                +"f.reconciliation_key,f.fact_type,f.source_event_id,b.bill_entry_id,f.business_date,f.org_id,"
                +"f.store_id,f.terminal_id,f.currency,f.amount_minor,b.amount_minor,f.lifecycle_status,"
                +"b.lifecycle_status,f.business_date,b.business_date,'MATCHED','MATCHED',f.content_sha256,"
                +"b.content_sha256,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3) FROM rpt_payment_fact_inbox f "
                +"JOIN rpt_internal_bill_inbox b ON b.tenant_id=f.tenant_id AND b.reconciliation_key=f.reconciliation_key "
                +"WHERE f.tenant_id='TENANT_RECON'");
            assertThat(facts).isEqualTo(100_000);
            assertThat(bills).isEqualTo(100_000);
            assertThat(projections).isEqualTo(100_000);
            try(var rows=statement.executeQuery("SELECT COUNT(*),SUM(internal_amount_minor),SUM(bill_amount_minor) "
                +"FROM rpt_payment_reconciliation WHERE tenant_id='TENANT_RECON' AND store_id=5 "
                +"AND business_date='2026-08-17' AND difference_type='MATCHED'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(10_000);
                assertThat(rows.getLong(2)).isEqualTo(rows.getLong(3));
            }
        }
        long elapsedMillis=(System.nanoTime()-started)/1_000_000;
        assertThat(elapsedMillis).as("100k facts/bills/reconciliation baseline ms").isLessThan(180_000);
    }

    private String sourceInsert(String tenant,String eventId,long gross,long discount,long surcharge,long receivable) {
        return "INSERT INTO rpt_source_event_inbox(source_event_id,tenant_id,source_owner,source_aggregate_id,"
            +"source_sequence,partition_key,schema_version,projection_version,content_sha256,occurred_at,business_date,"
            +"org_id,store_id,terminal_id,cashier_id,currency,metric_family,gross_minor,discount_minor,surcharge_minor,"
            +"receivable_minor,correlation_id) VALUES('"+eventId+"','"+tenant+"','ORDER','ORDER-1',1,"
            +"'store:11:order','1.0','g5d-v1',REPEAT('a',64),UTC_TIMESTAMP(3),'2026-08-17',1,11,'T1',7,"
            +"'CNY','SALES',"+gross+","+discount+","+surcharge+","+receivable+","
            +"'01K5D000000000000000000009')";
    }

    private String paymentFactInsert(String tenant,String eventId,String key,long amount) {
        return "INSERT INTO rpt_payment_fact_inbox(source_event_id,tenant_id,source_owner,source_sequence,"
            +"partition_key,schema_version,content_sha256,occurred_at,business_date,org_id,store_id,terminal_id,"
            +"fact_type,reconciliation_key,order_id,amount_minor,currency,lifecycle_status,correlation_id,applied_at) "
            +"VALUES('"+eventId+"','"+tenant+"','PAYMENT',1,'store:11','1.0',REPEAT('a',64),UTC_TIMESTAMP(3),"
            +"'2026-08-17',1,11,'T1','PAYMENT','"+key+"','01K5D000000000000000000099',"+amount
            +",'CNY','SUCCEEDED','01K5D000000000000000000098',UTC_TIMESTAMP(3))";
    }

    private String billInsert(String tenant,String billId,String key,long amount) {
        return "INSERT INTO rpt_internal_bill_inbox(bill_entry_id,tenant_id,batch_id,source_type,synthetic,"
            +"schema_version,content_sha256,business_date,org_id,store_id,terminal_id,fact_type,reconciliation_key,"
            +"amount_minor,currency,lifecycle_status,correlation_id,imported_by,imported_at) VALUES('"+billId+"','"
            +tenant+"','01K5D000000000000000000097','INTERNAL_SYNTHETIC',1,'1.0',REPEAT('b',64),'2026-08-17',"
            +"1,11,'T1','PAYMENT','"+key+"',"+amount+",'CNY','SUCCEEDED',"
            +"'01K5D000000000000000000096',7,UTC_TIMESTAMP(3))";
    }

    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank()) throw new IllegalStateException(name+" must be provided by CI");
        return value;
    }
}
