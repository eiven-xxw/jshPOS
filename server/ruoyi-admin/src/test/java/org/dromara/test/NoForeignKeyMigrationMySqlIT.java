package org.dromara.test;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在完整商业运行时类路径验证 V89 到 V90 的无外键前向迁移。
 *
 * <p>本测试只由具备隔离 MySQL 8.4 的本地/CI 专项显式执行，不使用 H2 模拟 MySQL DDL。</p>
 */
@Tag("local")
class NoForeignKeyMigrationMySqlIT {
    private final String url = required("JSH_LOCAL_MYSQL_JDBC_URL");
    private final String username = required("JSH_LOCAL_MYSQL_USERNAME");
    private final String password = required("JSH_LOCAL_MYSQL_PASSWORD");

    @Test
    void removesAllPhysicalForeignKeysAndKeepsOtherDatabaseGuards() throws Exception {
        createFrameworkMenuFixture();
        Flyway throughV89 = flyway("202608260089");
        assertThat(throughV89.migrate().migrationsExecuted).isEqualTo(89);
        assertThat(currentVersion(throughV89)).isEqualTo("202608260089");
        long foreignKeysBefore = scalar("SELECT COUNT(*) FROM information_schema.referential_constraints "
            + "WHERE constraint_schema=DATABASE()");
        long indexesBefore = scalar("SELECT COUNT(DISTINCT table_name,index_name) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE()");
        long checksBefore = scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'");
        long primaryKeysBefore = scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='PRIMARY KEY'");
        long uniqueConstraintsBefore = scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='UNIQUE'");
        assertThat(foreignKeysBefore).isEqualTo(309);

        Flyway throughV90 = flyway(null);
        assertThat(throughV90.migrate().migrationsExecuted).isOne();
        assertThat(currentVersion(throughV90)).isEqualTo("202609050090");
        assertThat(throughV90.migrate().migrationsExecuted).isZero();
        throughV90.validate();

        assertThat(scalar("SELECT COUNT(*) FROM information_schema.referential_constraints "
            + "WHERE constraint_schema=DATABASE()")).isZero();
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.key_column_usage "
            + "WHERE table_schema=DATABASE() AND referenced_table_name IS NOT NULL")).isZero();
        assertThat(scalar("SELECT COUNT(DISTINCT table_name,index_name) FROM information_schema.statistics "
            + "WHERE table_schema=DATABASE()")).isEqualTo(indexesBefore);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='CHECK'")).isEqualTo(checksBefore);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='PRIMARY KEY'"))
            .isEqualTo(primaryKeysBefore);
        assertThat(scalar("SELECT COUNT(*) FROM information_schema.table_constraints "
            + "WHERE constraint_schema=DATABASE() AND constraint_type='UNIQUE'"))
            .isEqualTo(uniqueConstraintsBefore);
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .validateOnMigrate(true)
            .cleanDisabled(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String currentVersion(Flyway flyway) {
        return flyway.info().current().getVersion().getVersion();
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
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

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be provided by the isolated MySQL 8.4 gate");
        }
        return value;
    }
}
