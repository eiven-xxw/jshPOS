package com.jingshanghui.pos.release.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-INVENTORY 准备阶段 MySQL 8.4.11 可执行红基线。
 *
 * <p>只读取当前正式 Mapper，并向临时数据库写入固定合成夹具；不会创建候选索引、修改正式
 * SQL/Mapper/API 或迁移。红基线用于后续运行时准入评审，不形成生产容量或商业 SLA。</p>
 */
class InventoryRemediationPrepMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<String> SUM_COLUMNS = List.of("onHandDelta", "availableDelta", "reservedDelta",
        "ledgerQuantityDelta", "purchaseQuantityDelta", "stocktakeQuantityDelta", "transferQuantityDelta",
        "inventoryValueDeltaMinor", "cogsDeltaMinor", "purchaseCostDeltaMinor", "stocktakeCostDeltaMinor",
        "transferCostDeltaMinor");

    private final String url = required("G10A_INVENTORY_MYSQL_JDBC_URL");
    private final String username = required("G10A_INVENTORY_MYSQL_USERNAME");
    private final String password = required("G10A_INVENTORY_MYSQL_PASSWORD");
    private final Path repositoryRoot = Path.of(required("G10A_INVENTORY_REPO_ROOT")).toAbsolutePath().normalize();
    private final Path evidenceRoot = Path.of(required("G10A_INVENTORY_EVIDENCE_DIR")).toAbsolutePath().normalize();

    @Test
    void freezesCurrentInventoryPlansQueryAmplificationAndTenantBoundary() throws Exception {
        Files.createDirectories(evidenceRoot);
        migrateEmptyDatabase();
        SqlBaselineQueries.QuerySpec spec = SqlBaselineQueries.all().stream()
            .filter(item -> "RPT-INVENTORY".equals(item.queryId())).findFirst().orElseThrow();
        List<Map<String, Object>> tiers = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            configureSession(connection);
            SqlBaselineFixture.extendAll(connection, 0, 10_000);
            SqlBaselineFixture.analyze(connection);
            tiers.add(captureTier(connection, spec, "SMOKE_10K", 10_000));

            SqlBaselineFixture.extendAll(connection, 10_000, 100_000);
            SqlBaselineFixture.analyze(connection);
            tiers.add(captureTier(connection, spec, "BASELINE_100K", 100_000));
            captureLegacyExportAmplification(connection, spec);
            captureIndexes(connection);
            captureEnvironment(connection);
        }
        writeJson(evidenceRoot.resolve("rpt-inventory-red-baseline.json"), tiers);
        assertThat(tiers).allSatisfy(row -> {
            assertThat((long) row.get("rowsReturned")).isGreaterThan(500);
            assertThat(row.get("queryCount")).isEqualTo(1);
            assertThat(row.get("crossTenantRows")).isEqualTo(0L);
            assertThat((boolean) row.get("fullScanObserved") || (boolean) row.get("filesortObserved")).isTrue();
            assertThat(row.get("runtimeChangeAuthorized")).isEqualTo(false);
        });
    }

    private Map<String, Object> captureTier(Connection connection, SqlBaselineQueries.QuerySpec spec,
                                             String tier, int primaryRows) throws Exception {
        SqlBaselineQueries.ExecutableQuery query = spec.executable(repositoryRoot);
        String logicalPlan = firstColumn(connection, "EXPLAIN FORMAT=JSON " + query.sql(), query.parameters());
        String actualPlan = firstColumn(connection, "EXPLAIN ANALYZE FORMAT=TREE " + query.sql(), query.parameters());
        QueryResult result = execute(connection, query.sql(), query.parameters());
        List<Object> attack = new ArrayList<>(query.parameters());
        for (int index = 0; index < attack.size(); index++) {
            if (SqlBaselineQueries.TENANT_A.equals(attack.get(index))) {
                attack.set(index, SqlBaselineQueries.ABSENT_TENANT);
            }
        }
        long crossTenantRows = execute(connection, query.sql(), attack).rows();
        JsonNode plan = JSON.readTree(logicalPlan);
        Path planRoot = evidenceRoot.resolve("plans").resolve(tier.toLowerCase(Locale.ROOT));
        Files.createDirectories(planRoot);
        Files.writeString(planRoot.resolve("rpt-inventory-explain.json"), logicalPlan + "\n", StandardCharsets.UTF_8);
        Files.writeString(planRoot.resolve("rpt-inventory-analyze.txt"), actualPlan + "\n", StandardCharsets.UTF_8);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tier", tier);
        row.put("primaryRows", primaryRows);
        row.put("rowsReturned", result.rows());
        row.put("queryCount", 1);
        row.put("crossTenantRows", crossTenantRows);
        row.put("fullScanObserved", observesAccessAll(plan));
        row.put("filesortObserved", observesFilesort(plan));
        row.put("sourceSqlSha256", SqlBaselineQueries.sha256(spec.sourceStatement(repositoryRoot)));
        row.put("executableSqlSha256", SqlBaselineQueries.sha256(query.sql()));
        row.put("logicalPlanSha256", SqlBaselineQueries.sha256(logicalPlan));
        row.put("actualPlanSha256", SqlBaselineQueries.sha256(actualPlan));
        row.put("quantityAndCostSums", result.sums());
        row.put("expectedRed", "UNBOUNDED_RESULT_OR_PLAN_SORT_SCAN");
        row.put("runtimeChangeAuthorized", false);
        row.put("syntheticOnly", true);
        row.put("productionSla", false);
        return row;
    }

    private void captureLegacyExportAmplification(Connection connection, SqlBaselineQueries.QuerySpec spec)
        throws Exception {
        int queries = 0;
        long rows = 0;
        for (long storeId = 1001; storeId <= 1050; storeId++) {
            SqlBaselineQueries.ExecutableQuery query = spec.executable(repositoryRoot);
            List<Object> parameters = new ArrayList<>(query.parameters());
            parameters.set(parameters.size() - 1, storeId);
            rows += execute(connection, query.sql(), parameters).rows();
            queries++;
        }
        assertThat(queries).isEqualTo(50);
        writeJson(evidenceRoot.resolve("legacy-export-query-count.json"), Map.of(
            "stores", 50, "reportType", "INVENTORY_COST_DAILY", "jdbcQueryCount", queries,
            "rowsObserved", rows, "expectedRed", "ONE_QUERY_PER_STORE", "runtimeChangeAuthorized", false));
    }

    private QueryResult execute(Connection connection, String sql, List<Object> parameters) throws SQLException {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        SUM_COLUMNS.forEach(column -> sums.put(column, BigDecimal.ZERO));
        long rows = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows++;
                    for (String column : SUM_COLUMNS) {
                        BigDecimal value = result.getBigDecimal(column);
                        sums.put(column, sums.get(column).add(value == null ? BigDecimal.ZERO : value));
                    }
                }
            }
        }
        return new QueryResult(rows, Map.copyOf(sums));
    }

    private void captureIndexes(Connection connection) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW INDEX FROM rpt_inventory_cost_daily")) {
            while (result.next()) {
                rows.add(Map.of("keyName", result.getString("Key_name"), "sequence", result.getInt("Seq_in_index"),
                    "column", result.getString("Column_name"), "nonUnique", result.getInt("Non_unique")));
            }
        }
        writeJson(evidenceRoot.resolve("show-index.json"), rows);
    }

    private void captureEnvironment(Connection connection) throws Exception {
        writeJson(evidenceRoot.resolve("environment.json"), Map.of(
            "mysqlVersion", firstColumn(connection, "SELECT VERSION()", List.of()),
            "schemaVersion", firstColumn(connection,
                "SELECT version FROM jshpos_flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
                List.of()), "runner", System.getenv().getOrDefault("RUNNER_OS", "UNKNOWN"),
            "commit", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL"), "capturedAt", Instant.now().toString(),
            "syntheticOnly", true, "productionSla", false));
    }

    private void migrateEmptyDatabase() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password).locations(migrationLocations())
            .table("jshpos_flyway_schema_history").baselineOnMigrate(true).baselineVersion("0")
            .cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608260088");
    }

    private void createFrameworkMenuFixture() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sys_menu (menu_id BIGINT NOT NULL PRIMARY KEY,"
                + "menu_name VARCHAR(50) NOT NULL,parent_id BIGINT DEFAULT 0,order_num INT DEFAULT 0,"
                + "path VARCHAR(200) DEFAULT '',component VARCHAR(255),query_param VARCHAR(255),route_name VARCHAR(100),"
                + "is_frame INT DEFAULT 1,is_cache INT DEFAULT 0,menu_type CHAR(1) DEFAULT '',visible CHAR(1) DEFAULT '0',"
                + "status CHAR(1) DEFAULT '0',perms VARCHAR(100),icon VARCHAR(100) DEFAULT '#',create_dept BIGINT,"
                + "create_by BIGINT,create_time DATETIME,update_by BIGINT,update_time DATETIME,remark VARCHAR(500) DEFAULT '') ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS sys_role_menu (role_id BIGINT NOT NULL,"
                + "menu_id BIGINT NOT NULL,PRIMARY KEY (role_id,menu_id)) ENGINE=InnoDB");
        }
    }

    private String[] migrationLocations() throws IOException {
        Path root = Path.of(required("G10A_INVENTORY_MIGRATION_ROOT")).toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<String> locations = paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src", "main", "resources", "db", "migration")))
                .sorted().map(path -> "filesystem:" + path.toString().replace('\\', '/')).toList();
            assertThat(locations).hasSize(22);
            return locations.toArray(String[]::new);
        }
    }

    private static boolean observesAccessAll(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if ("access_type".equals(entry.getKey()) && "ALL".equalsIgnoreCase(entry.getValue().asText())) return true;
                if (observesAccessAll(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (observesAccessAll(child)) return true;
        }
        return false;
    }

    private static boolean observesFilesort(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (entry.getKey().toLowerCase(Locale.ROOT).contains("filesort")
                    && entry.getValue().isBoolean() && entry.getValue().booleanValue()) return true;
                if (observesFilesort(entry.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (observesFilesort(child)) return true;
        }
        return false;
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
        for (int index = 0; index < parameters.size(); index++) statement.setObject(index + 1, parameters.get(index));
    }

    private static void configureSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION max_execution_time=60000");
            statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("缺少环境变量 " + name);
        return value;
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), value);
    }

    private record QueryResult(long rows, Map<String, BigDecimal> sums) {
    }
}
