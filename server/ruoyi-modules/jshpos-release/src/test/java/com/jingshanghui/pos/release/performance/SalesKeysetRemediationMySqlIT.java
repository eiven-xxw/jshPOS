package com.jingshanghui.pos.release.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 10A-R2-R2-R2-R1-INDEX RPT-SALES keyset 索引 MySQL 8.4 可执行验收。
 *
 * <p>测试分别验证空库到 V88 与 V87 到 V88，只在临时 MySQL 中写入确定性合成数据；
 * 先保存 V87 的全扫/filesort 红基线，再验证 V88 计划、查询数、完整性和租户边界。</p>
 */
class SalesKeysetRemediationMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern FULL_SCAN = Pattern.compile("\\\"access_type\\\"\\s*:\\s*\\\"ALL\\\"");
    private static final List<Long> AUTHORIZED_STORES = java.util.stream.LongStream.rangeClosed(1001L, 1050L)
        .boxed().toList();

    private final String url = required("G10A_RPT_SALES_MYSQL_JDBC_URL");
    private final String emptyUrl = required("G10A_RPT_SALES_EMPTY_MYSQL_JDBC_URL");
    private final String username = required("G10A_RPT_SALES_MYSQL_USERNAME");
    private final String password = required("G10A_RPT_SALES_MYSQL_PASSWORD");
    private final Path repositoryRoot = Path.of(required("G10A_RPT_SALES_REPO_ROOT")).toAbsolutePath().normalize();
    private final Path evidenceRoot = Path.of(required("G10A_RPT_SALES_EVIDENCE_DIR")).toAbsolutePath().normalize();

    @Test
    void provesV88MigrationAndEliminatesFullScanAndFilesort() throws Exception {
        Files.createDirectories(evidenceRoot);
        verifyEmptyDatabaseToV88();
        migrateV87ToV88WithRedBaseline();
        List<Map<String, Object>> tiers = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            configureSession(connection);
            analyze(connection);
            tiers.add(captureTier(connection, "SMOKE_10K", 10_000));

            SqlBaselineFixture.extendSalesTrend(connection, 10_000, 100_000);
            analyze(connection);
            tiers.add(captureTier(connection, "BASELINE_100K", 100_000));
            writeIndexCatalog(connection);
            writeEnvironment(connection);
        }
        writeJson(evidenceRoot.resolve("rpt-sales-keyset-results.json"), tiers);

        assertThat(tiers).allSatisfy(row -> {
            assertThat(row.get("crossTenantRows")).isEqualTo(0L);
            assertThat(row.get("duplicateKeys")).isEqualTo(0L);
            assertThat(row.get("missingRows")).isEqualTo(0L);
            assertThat(row.get("interactiveQueryCount")).isEqualTo(1);
            assertThat((Integer) row.get("exportQueryCount")).isLessThanOrEqualTo((Integer) row.get("exportQueryBudget"));
            assertThat(row.get("amountInvariantPassed")).isEqualTo(true);
            assertThat(row.get("fullScanObserved")).isEqualTo(false);
            assertThat(row.get("filesortObserved")).isEqualTo(false);
            assertThat(row.get("approvedIndexObserved")).isEqualTo(true);
            assertThat(row.get("indexCrRequired")).isEqualTo(false);
        });
    }

    private Map<String, Object> captureTier(Connection connection, String tier, int seededRows) throws Exception {
        Path planRoot = evidenceRoot.resolve("plans").resolve(tier.toLowerCase(Locale.ROOT));
        Files.createDirectories(planRoot);
        java.sql.Date from = java.sql.Date.valueOf("2026-08-01");
        java.sql.Date to = java.sql.Date.valueOf("2026-08-31");
        SqlBaselineQueries.ExecutableQuery first = SqlBaselineQueries.salesPage(repositoryRoot,
            SqlBaselineQueries.TENANT_A, "v1", from, to, AUTHORIZED_STORES, null, 501);

        String explainJson = firstColumn(connection, "EXPLAIN FORMAT=JSON " + first.sql(), first.parameters());
        String explainTree = firstColumn(connection, "EXPLAIN ANALYZE FORMAT=TREE " + first.sql(), first.parameters());
        Files.writeString(planRoot.resolve("rpt-sales-first-explain.json"), explainJson + "\n", StandardCharsets.UTF_8);
        Files.writeString(planRoot.resolve("rpt-sales-first-analyze.txt"), explainTree + "\n", StandardCharsets.UTF_8);

        QueryCounter interactive = new QueryCounter(connection);
        List<SalesRow> firstRows = interactive.read(first);
        assertThat(firstRows).hasSize(501);

        long expectedRows = count(connection, "SELECT COUNT(*) FROM rpt_sales_daily WHERE tenant_id=? "
            + "AND projection_version=? AND business_date BETWEEN ? AND ? AND store_id BETWEEN 1001 AND 1050",
            List.of(SqlBaselineQueries.TENANT_A, "v1", from, to));
        long crossTenantRows = countPage(connection, SqlBaselineQueries.ABSENT_TENANT, from, to, 501);

        QueryCounter export = new QueryCounter(connection);
        Set<String> keys = new LinkedHashSet<>();
        SqlBaselineQueries.SalesKey cursor = null;
        long observedRows = 0;
        long gross = 0;
        long discount = 0;
        long surcharge = 0;
        long receivable = 0;
        for (;;) {
            SqlBaselineQueries.ExecutableQuery query = SqlBaselineQueries.salesPage(repositoryRoot,
                SqlBaselineQueries.TENANT_A, "v1", from, to, AUTHORIZED_STORES, cursor, 10_000);
            List<SalesRow> page = export.read(query);
            for (SalesRow row : page) {
                keys.add(row.keyText());
                observedRows++;
                gross += row.grossMinor();
                discount += row.discountMinor();
                surcharge += row.surchargeMinor();
                receivable += row.receivableMinor();
            }
            if (page.isEmpty() || page.size() < 10_000) {
                break;
            }
            SalesRow last = page.get(page.size() - 1);
            cursor = last.cursor();
        }
        int queryBudget = (int) Math.ceil(expectedRows / 10_000.0d)
            + (expectedRows > 0 && expectedRows % 10_000 == 0 ? 1 : 0);
        boolean fullScan = FULL_SCAN.matcher(explainJson).find();
        boolean filesort = explainJson.toLowerCase(Locale.ROOT).contains("filesort");
        boolean indexCrRequired = fullScan || filesort;
        boolean approvedIndexObserved = explainJson.contains("idx_rpt_sales_keyset");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", tier);
        result.put("seededRows", seededRows);
        result.put("expectedRows", expectedRows);
        result.put("observedRows", observedRows);
        result.put("interactiveRows", firstRows.size());
        result.put("interactiveQueryCount", interactive.count());
        result.put("exportQueryCount", export.count());
        result.put("exportQueryBudget", queryBudget);
        result.put("legacySalesQueriesFor50Stores", 50);
        result.put("duplicateKeys", observedRows - keys.size());
        result.put("missingRows", expectedRows - observedRows);
        result.put("crossTenantRows", crossTenantRows);
        result.put("grossMinor", gross);
        result.put("discountMinor", discount);
        result.put("surchargeMinor", surcharge);
        result.put("receivableMinor", receivable);
        result.put("amountInvariantPassed", gross - discount + surcharge == receivable);
        result.put("fullScanObserved", fullScan);
        result.put("filesortObserved", filesort);
        result.put("approvedIndexObserved", approvedIndexObserved);
        result.put("indexCrRequired", indexCrRequired);
        result.put("recommendation", indexCrRequired
            ? "STOP_AND_REQUEST_INDEPENDENT_INDEX_CR"
            : "KEEP_EXISTING_INDEXES");
        result.put("sourceSqlSha256", SqlBaselineQueries.sha256(
            SqlBaselineQueries.salesPageSource(repositoryRoot)));
        result.put("executableSqlSha256", SqlBaselineQueries.sha256(first.sql()));
        result.put("logicalPlanSha256", SqlBaselineQueries.sha256(explainJson));
        result.put("actualPlanSha256", SqlBaselineQueries.sha256(explainTree));
        result.put("syntheticOnly", true);
        result.put("productionSla", false);
        writeJson(planRoot.resolve("rpt-sales-result.json"), result);
        return result;
    }

    private long countPage(Connection connection, String tenantId, java.sql.Date from,
                           java.sql.Date to, int limit) throws Exception {
        QueryCounter counter = new QueryCounter(connection);
        List<SalesRow> rows = counter.read(SqlBaselineQueries.salesPage(repositoryRoot, tenantId, "v1",
            from, to, AUTHORIZED_STORES, null, limit));
        assertThat(counter.count()).isEqualTo(1);
        return rows.size();
    }

    /** 验证全新数据库一次安装至 V88、validate 与重复执行零迁移。 */
    private void verifyEmptyDatabaseToV88() throws Exception {
        createFrameworkMenuFixture(emptyUrl);
        Flyway flyway = flyway(emptyUrl);
        assertThat(flyway.migrate().migrationsExecuted).isPositive();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608260088");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (Connection connection = DriverManager.getConnection(emptyUrl, username, password)) {
            assertApprovedIndex(connection);
        }
    }

    /** 在 V87 固化失败计划后只执行 V88，并验证重复执行与 Flyway validate。 */
    private void migrateV87ToV88WithRedBaseline() throws Exception {
        createFrameworkMenuFixture(url);
        Flyway v87 = Flyway.configure().dataSource(url, username, password)
            .locations(migrationLocations()).table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").target("202608260087").cleanDisabled(true).load();
        v87.migrate();
        v87.validate();
        assertThat(v87.info().current().getVersion().getVersion()).isEqualTo("202608260087");
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            configureSession(connection);
            SqlBaselineFixture.extendSalesTrend(connection, 0, 10_000);
            analyze(connection);
            captureV87RedPlan(connection);
        }

        Flyway v88 = flyway(url);
        assertThat(v88.migrate().migrationsExecuted).isEqualTo(1);
        v88.validate();
        assertThat(v88.info().current().getVersion().getVersion()).isEqualTo("202608260088");
        assertThat(v88.migrate().migrationsExecuted).isZero();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            assertApprovedIndex(connection);
        }
    }

    private Flyway flyway(String jdbcUrl) throws Exception {
        return Flyway.configure().dataSource(jdbcUrl, username, password)
            .locations(migrationLocations()).table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
    }

    /** V87 必须稳定重现获批 CR 所针对的全扫和 filesort，防止测试伪绿。 */
    private void captureV87RedPlan(Connection connection) throws Exception {
        SqlBaselineQueries.ExecutableQuery query = SqlBaselineQueries.salesPage(repositoryRoot,
            SqlBaselineQueries.TENANT_A, "v1", java.sql.Date.valueOf("2026-08-01"),
            java.sql.Date.valueOf("2026-08-31"), AUTHORIZED_STORES, null, 501);
        String explainJson = firstColumn(connection, "EXPLAIN FORMAT=JSON " + query.sql(), query.parameters());
        boolean fullScan = FULL_SCAN.matcher(explainJson).find();
        boolean filesort = explainJson.toLowerCase(Locale.ROOT).contains("filesort");
        Map<String, Object> red = new LinkedHashMap<>();
        red.put("schemaVersion", "202608260087");
        red.put("fullScanObserved", fullScan);
        red.put("filesortObserved", filesort);
        red.put("approvedIndexObserved", explainJson.contains("idx_rpt_sales_keyset"));
        red.put("expectedFailure", true);
        writeJson(evidenceRoot.resolve("v87-red-plan.json"), red);
        Path redPlan = evidenceRoot.resolve("plans/v87-red-explain.json");
        Files.createDirectories(redPlan.getParent());
        Files.writeString(redPlan, explainJson + "\n",
            StandardCharsets.UTF_8);
        assertThat(fullScan).isTrue();
        assertThat(filesort).isTrue();
        assertThat(explainJson).doesNotContain("idx_rpt_sales_keyset");
    }

    /** 核验索引名、唯一性属性与七列顺序；不得以同名但错序索引冒充。 */
    private void assertApprovedIndex(Connection connection) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT column_name FROM information_schema.statistics
             WHERE table_schema=DATABASE() AND table_name='rpt_sales_daily'
               AND index_name='idx_rpt_sales_keyset'
             ORDER BY seq_in_index
            """); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
        }
        assertThat(columns).containsExactly("tenant_id", "projection_version", "business_date", "store_id",
            "terminal_id", "cashier_id", "currency");
    }

    private void createFrameworkMenuFixture(String jdbcUrl) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
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
                  role_id BIGINT NOT NULL,menu_id BIGINT NOT NULL,PRIMARY KEY (role_id,menu_id)
                ) ENGINE=InnoDB
                """);
        }
    }

    private String[] migrationLocations() throws Exception {
        Path root = Path.of(required("G10A_RPT_SALES_MIGRATION_ROOT")).toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<String> locations = paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src", "main", "resources", "db", "migration")))
                .sorted().map(path -> "filesystem:" + path.toString().replace('\\', '/')).toList();
            assertThat(locations).hasSize(22);
            return locations.toArray(String[]::new);
        }
    }

    private void analyze(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ANALYZE TABLE rpt_sales_daily");
        }
    }

    private void configureSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION time_zone='+00:00'");
            statement.execute("SET SESSION transaction_isolation='READ-COMMITTED'");
        }
    }

    private void writeIndexCatalog(Connection connection) throws Exception {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW INDEX FROM rpt_sales_daily")) {
            while (rows.next()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("indexName", rows.getString("Key_name"));
                item.put("sequence", rows.getInt("Seq_in_index"));
                item.put("column", rows.getString("Column_name"));
                item.put("cardinality", rows.getLong("Cardinality"));
                indexes.add(item);
            }
        }
        writeJson(evidenceRoot.resolve("show-index.json"), indexes);
    }

    private void writeEnvironment(Connection connection) throws Exception {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("mysqlVersion", firstColumn(connection, "SELECT VERSION()", List.of()));
        environment.put("schemaVersion", firstColumn(connection,
            "SELECT version FROM jshpos_flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
            List.of()));
        environment.put("commit", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL"));
        environment.put("runner", System.getenv().getOrDefault("RUNNER_OS", "UNKNOWN"));
        environment.put("syntheticOnly", true);
        environment.put("productionSla", false);
        writeJson(evidenceRoot.resolve("environment.json"), environment);
    }

    private static long count(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static String firstColumn(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少环境变量: " + name);
        }
        return value;
    }

    private record SalesRow(SqlBaselineQueries.SalesKey cursor, long grossMinor, long discountMinor,
                            long surchargeMinor, long receivableMinor) {
        String keyText() {
            return cursor.businessDate() + "|" + cursor.storeId() + "|" + cursor.terminalId()
                + "|" + cursor.cashierId() + "|" + cursor.currency();
        }
    }

    private static final class QueryCounter {
        private final Connection connection;
        private int count;

        private QueryCounter(Connection connection) {
            this.connection = connection;
        }

        List<SalesRow> read(SqlBaselineQueries.ExecutableQuery query) throws SQLException {
            count++;
            try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
                bind(statement, query.parameters());
                try (ResultSet rows = statement.executeQuery()) {
                    List<SalesRow> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(new SalesRow(new SqlBaselineQueries.SalesKey(
                            rows.getDate("businessDate"), rows.getLong("storeId"), rows.getString("terminalId"),
                            rows.getLong("cashierId"), rows.getString("currency")), rows.getLong("grossMinor"),
                            rows.getLong("discountMinor"), rows.getLong("surchargeMinor"),
                            rows.getLong("receivableMinor")));
                    }
                    return result;
                }
            }
        }

        int count() {
            return count;
        }
    }
}
