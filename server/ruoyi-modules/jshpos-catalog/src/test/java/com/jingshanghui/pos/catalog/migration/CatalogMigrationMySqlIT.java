package com.jingshanghui.pos.catalog.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仅由 Gate 1 mysql-migration Job 在真实 MySQL 8.4 服务中显式执行。 */
class CatalogMigrationMySqlIT {

    private final String url = required("GATE1_MYSQL_JDBC_URL");
    private final String username = required("GATE1_MYSQL_USERNAME");
    private final String password = required("GATE1_MYSQL_PASSWORD");

    @Test
    void migratesAllNineVersionsRepeatablyAndEnforcesCatalogTenantConstraints() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .cleanDisabled(true)
            .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        flyway.validate();
        assertTablesAndPermissions();
        assertCrossTenantBarcodeUnitAndPriceGuards();
        assertWeightedBarcodeTenantAndAppendOnlyGuards();
        assertShelfLabelTenantSnapshotAndAppendOnlyGuards();
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS sys_menu (
                    menu_id BIGINT NOT NULL PRIMARY KEY, menu_name VARCHAR(50) NOT NULL,
                    parent_id BIGINT DEFAULT 0, order_num INT DEFAULT 0, path VARCHAR(200) DEFAULT '',
                    component VARCHAR(255), query_param VARCHAR(255), route_name VARCHAR(100),
                    is_frame INT DEFAULT 1, is_cache INT DEFAULT 0, menu_type CHAR(1) DEFAULT '',
                    visible CHAR(1) DEFAULT '0', status CHAR(1) DEFAULT '0', perms VARCHAR(100),
                    icon VARCHAR(100) DEFAULT '#', create_dept BIGINT, create_by BIGINT,
                    create_time DATETIME, update_by BIGINT, update_time DATETIME,
                    remark VARCHAR(500) DEFAULT ''
                ) ENGINE=InnoDB
                """);
        }
    }

    private void assertTablesAndPermissions() throws SQLException {
        Set<String> tables = Set.of("cat_category", "cat_brand", "cat_unit", "cat_spu", "cat_sku",
            "cat_sku_unit", "cat_barcode", "cat_import_batch", "cat_import_record", "cat_import_error",
            "cat_catalog_binding", "prc_price_book", "prc_price_item", "dpk_catalog_package", "cat_event_outbox",
            "cat_weighted_barcode_template", "cat_weighted_barcode_history", "lbl_template",
            "lbl_label_task", "lbl_label_task_item", "lbl_task_event", "lbl_task_exception");
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            for (String table : tables) {
                try (var rows = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
                    assertThat(rows.next()).as("table %s", table).isTrue();
                }
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200100 AND 9200110")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(10);
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200111 AND 9200114")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (Statement statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(DISTINCT perms) FROM sys_menu WHERE menu_id BETWEEN 9200115 AND 9200120")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(6);
            }
        }
    }

    private void assertCrossTenantBarcodeUnitAndPriceGuards() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO jsh_org_unit(org_unit_id,tenant_id,unit_code,unit_name,unit_type,status,tree_depth,version) VALUES (1001,'TENANT_A','A-HQ','A HQ','HEADQUARTERS','ACTIVE',1,0),(2001,'TENANT_B','B-HQ','B HQ','HEADQUARTERS','ACTIVE',1,0)");
            statement.executeUpdate("INSERT INTO jsh_store(store_id,tenant_id,org_unit_id,store_code,store_name,zone_id,business_day_start,status,version) VALUES (1101,'TENANT_A',1001,'A101','A Store','Asia/Shanghai','06:00:00','ACTIVE',0),(2101,'TENANT_B',2001,'B101','B Store','Asia/Shanghai','06:00:00','ACTIVE',0)");
            statement.executeUpdate("INSERT INTO cat_category(category_id,tenant_id,category_code,category_name) VALUES (101,'TENANT_A','FOOD','Food'),(201,'TENANT_B','FOOD','Food')");
            statement.executeUpdate("INSERT INTO cat_unit(unit_id,tenant_id,unit_code,unit_name) VALUES (301,'TENANT_A','PCS','Piece'),(401,'TENANT_B','PCS','Piece')");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES (501,'TENANT_A','A-SPU','A',201,'DRAFT')"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO cat_spu(spu_id,tenant_id,spu_code,spu_name,category_id,status) VALUES (501,'TENANT_A','A-SPU','A',101,'DRAFT'),(601,'TENANT_B','B-SPU','B',201,'DRAFT')");
            statement.executeUpdate("INSERT INTO cat_sku(sku_id,tenant_id,spu_id,sku_code,sku_name,product_type,status) VALUES (701,'TENANT_A',501,'A-SKU','A','STANDARD','ACTIVE'),(801,'TENANT_B',601,'B-SKU','B','STANDARD','ACTIVE')");
            statement.executeUpdate("INSERT INTO cat_sku_unit(sku_unit_id,tenant_id,sku_id,unit_id,ratio_numerator,ratio_denominator,primary_unit) VALUES (901,'TENANT_A',701,301,1,1,TRUE)");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO cat_sku_unit(sku_unit_id,tenant_id,sku_id,unit_id,ratio_numerator,ratio_denominator,primary_unit) VALUES (902,'TENANT_A',701,301,1,1,TRUE)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO cat_barcode(barcode_id,tenant_id,sku_id,sku_unit_id,barcode_value) VALUES (10001,'TENANT_A',701,901,'001234')");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO cat_barcode(barcode_id,tenant_id,sku_id,sku_unit_id,barcode_value) VALUES (10002,'TENANT_A',701,901,'001234')"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO prc_price_book(price_book_id,tenant_id,book_code,book_name,version_no,scope_type,store_id) VALUES (11001,'TENANT_A','STORE','Bad',1,'STORE',2101)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO prc_price_book(price_book_id,tenant_id,book_code,book_name,version_no,scope_type,store_id,state,content_sha256) VALUES (11001,'TENANT_A','BASE','Base',1,'TENANT_BASE',NULL,'PUBLISHED',REPEAT('a',64))");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE prc_price_book SET book_code='MUTATED' WHERE price_book_id=11001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
        }
    }

    /** 在真实 MySQL 上证明模板租户外键、发布冻结和历史只追加约束会失败关闭。 */
    private void assertWeightedBarcodeTenantAndAppendOnlyGuards() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("""
                INSERT INTO cat_weighted_barcode_template(
                  template_id,tenant_id,template_code,version_no,scope_type,store_id,barcode_kind,prefix_value,
                  sku_start_pos,sku_length,value_start_pos,value_length,value_scale,effective_from)
                VALUES (12000,'TENANT_A','CROSS_STORE',1,'STORE',2101,'WEIGHT','23',3,5,8,5,3,CURRENT_TIMESTAMP)
                """))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("""
                INSERT INTO cat_weighted_barcode_template(
                  template_id,tenant_id,template_code,version_no,scope_type,store_id,barcode_kind,prefix_value,
                  sku_start_pos,sku_length,value_start_pos,value_length,value_scale,effective_from)
                VALUES (12001,'TENANT_A','STORE_WEIGHT',1,'STORE',1101,'WEIGHT','23',3,5,8,5,3,CURRENT_TIMESTAMP)
                """);
            statement.executeUpdate("""
                UPDATE cat_weighted_barcode_template
                SET state='PUBLISHED',content_sha256=REPEAT('a',64),published_at=CURRENT_TIMESTAMP,version=version+1
                WHERE tenant_id='TENANT_A' AND template_id=12001 AND state='DRAFT' AND version=0
                """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE cat_weighted_barcode_template SET prefix_value='24'
                WHERE tenant_id='TENANT_A' AND template_id=12001
                """))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("""
                INSERT INTO cat_weighted_barcode_history(
                  history_id,tenant_id,template_id,event_type,template_version,content_sha256,payload_json,occurred_at)
                VALUES (12002,'TENANT_A',12001,'PUBLISHED',1,REPEAT('a',64),JSON_OBJECT('templateId','12001'),CURRENT_TIMESTAMP)
                """);
            assertThatThrownBy(() -> statement.executeUpdate("""
                UPDATE cat_weighted_barcode_history SET event_type='RETIRED'
                WHERE tenant_id='TENANT_A' AND history_id=12002
                """))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
            assertThatThrownBy(() -> statement.executeUpdate("""
                DELETE FROM cat_weighted_barcode_history
                WHERE tenant_id='TENANT_A' AND history_id=12002
                """))
                .isInstanceOf(SQLException.class).hasMessageContaining("append only");
        }
    }

    /** 在 MySQL 8.4 证明价签模板、快照、租户外键和只追加事实均由数据库失败关闭。 */
    private void assertShelfLabelTenantSnapshotAndAppendOnlyGuards() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO prc_price_book(price_book_id,tenant_id,book_code,book_name,version_no,scope_type,store_id,state) VALUES(13001,'TENANT_A','LBL-BASE','Label Base',2,'TENANT_BASE',NULL,'DRAFT')");
            statement.executeUpdate("INSERT INTO prc_price_item(price_item_id,tenant_id,price_book_id,sku_id,unit_id,amount_minor,currency,effective_from) VALUES(13002,'TENANT_A',13001,701,301,990,'CNY','2026-08-22 08:00:00')");
            statement.executeUpdate("UPDATE prc_price_book SET state='PUBLISHED',content_sha256=REPEAT('b',64),published_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id='TENANT_A' AND price_book_id=13001 AND state='DRAFT'");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO lbl_template(template_id,tenant_id,template_code,template_name,version_no,scope_type,store_id,body_template,create_idempotency_key,create_request_sha256,state,created_at) VALUES(14000,'TENANT_A','CROSS','Cross',1,'STORE',2101,'{{productName}}','lbl-template-cross',REPEAT('a',64),'DRAFT',CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO lbl_template(template_id,tenant_id,template_code,template_name,version_no,scope_type,store_id,body_template,create_idempotency_key,create_request_sha256,state,created_at) VALUES(14001,'TENANT_A','DEFAULT','Default',1,'STORE',1101,'{{productName}} {{newPrice}}','lbl-template-default',REPEAT('a',64),'DRAFT',CURRENT_TIMESTAMP)");
            statement.executeUpdate("UPDATE lbl_template SET state='PUBLISHED',content_sha256=REPEAT('c',64),published_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id='TENANT_A' AND template_id=14001 AND state='DRAFT'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE lbl_template SET body_template='{{skuCode}}' WHERE tenant_id='TENANT_A' AND template_id=14001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO lbl_label_task(task_id,tenant_id,source_event_key,source_event_type,source_event_sha256,source_price_book_id,source_price_version,store_id,store_name,effective_at,state,created_at,updated_at) VALUES(15000,'TENANT_A','price-book.published.v1:13001:2:2101','PRICE_BOOK_PUBLISHED',REPEAT('d',64),13001,2,2101,'Cross Store','2026-08-22 08:00:00','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
            statement.executeUpdate("INSERT INTO lbl_label_task(task_id,tenant_id,source_event_key,source_event_type,source_event_sha256,source_price_book_id,source_price_version,store_id,store_name,effective_at,state,created_at,updated_at) VALUES(15001,'TENANT_A','price-book.published.v1:13001:2:1101','PRICE_BOOK_PUBLISHED',REPEAT('d',64),13001,2,1101,'A Store','2026-08-22 08:00:00','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.executeUpdate("INSERT INTO lbl_label_task_item(item_id,tenant_id,task_id,source_price_book_id,source_price_item_id,source_price_version,scope_priority,store_id,store_name,sku_id,sku_code,product_name,unit_id,unit_name,barcode,old_price_minor,new_price_minor,currency,effective_at,state,snapshot_sha256,created_at,updated_at) VALUES(16001,'TENANT_A',15001,13001,13002,2,1,1101,'A Store',701,'A-SKU','A',301,'Piece','001234',890,990,'CNY','2026-08-22 08:00:00','PENDING',REPEAT('e',64),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
            statement.executeUpdate("UPDATE lbl_label_task_item SET state='PREVIEW_READY',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE tenant_id='TENANT_A' AND item_id=16001");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE lbl_label_task_item SET new_price_minor=1 WHERE tenant_id='TENANT_A' AND item_id=16001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("immutable");
            statement.executeUpdate("INSERT INTO lbl_task_event(event_id,tenant_id,task_id,item_id,event_type,idempotency_key,command_sha256,payload_sha256,payload_json,actor_user_id,correlation_id,occurred_at) VALUES(17001,'TENANT_A',15001,16001,'SHELF_LABEL_PREVIEWED','lbl-preview-mysql-001',REPEAT('f',64),REPEAT('a',64),JSON_OBJECT('preview','synthetic'),101,'corr-lbl-mysql-001',CURRENT_TIMESTAMP)");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM lbl_task_event WHERE tenant_id='TENANT_A' AND event_id=17001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            statement.executeUpdate("INSERT INTO lbl_task_exception(exception_id,tenant_id,task_id,item_id,exception_code,reason,resolution_type,actor_user_id,correlation_id,occurred_at) VALUES(18001,'TENANT_A',15001,16001,'PRINTER_UNAVAILABLE','Synthetic blocked','BLOCKED_EXTERNAL',101,'corr-lbl-mysql-002',CURRENT_TIMESTAMP)");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE lbl_task_exception SET reason='tampered' WHERE tenant_id='TENANT_A' AND exception_id=18001"))
                .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be provided by the mysql-migration gate");
        }
        return value;
    }
}
