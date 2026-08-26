package com.jingshanghui.pos.release.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-INVENTORY v2 keyset 与批量导出的 MySQL 8.4.11 可执行验收。
 *
 * <p>本测试验证查询数、逐键完整性、租户隔离和十二项投影字段守恒，同时如实记录当前索引下的
 * 全扫/filesort。发现需要索引时只输出停止建议，不创建数据库对象或迁移。</p>
 */
class InventoryKeysetRemediationMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<Long> STORES = java.util.stream.LongStream.rangeClosed(1001L, 1050L).boxed().toList();
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
    void verifiesKeysetIntegrityAndProducesIndexDecision() throws Exception {
        Files.createDirectories(evidenceRoot);
        migrateEmptyDatabase();
        List<Map<String, Object>> tiers = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            configureSession(connection);
            SqlBaselineFixture.extendAll(connection, 0, 10_000);
            SqlBaselineFixture.analyze(connection);
            tiers.add(captureTier(connection, "SMOKE_10K", 10_000));
            SqlBaselineFixture.extendAll(connection, 10_000, 100_000);
            SqlBaselineFixture.analyze(connection);
            tiers.add(captureTier(connection, "BASELINE_100K", 100_000));
            writeIndexCatalog(connection);
        }
        writeJson(evidenceRoot.resolve("rpt-inventory-keyset-results.json"), tiers);
        assertThat(tiers).allSatisfy(row -> {
            assertThat(row.get("interactiveQueryCount")).isEqualTo(1);
            assertThat(row.get("crossTenantRows")).isEqualTo(0L);
            assertThat(row.get("duplicateKeys")).isEqualTo(0L);
            assertThat(row.get("missingRows")).isEqualTo(0L);
            assertThat(row.get("twelveFieldConservationPassed")).isEqualTo(true);
            assertThat(row.get("schemaOrIndexChanged")).isEqualTo(false);
        });
    }

    private Map<String, Object> captureTier(Connection connection, String tier, int seededRows) throws Exception {
        java.sql.Date from = java.sql.Date.valueOf("2026-08-01");
        java.sql.Date to = java.sql.Date.valueOf("2026-08-31");
        SqlBaselineQueries.ExecutableQuery first = SqlBaselineQueries.inventoryCostPage(repositoryRoot,
            SqlBaselineQueries.TENANT_A, "v1", from, to, STORES, null, 501);
        String logicalPlan = firstColumn(connection, "EXPLAIN FORMAT=JSON " + first.sql(), first.parameters());
        String actualPlan = firstColumn(connection, "EXPLAIN ANALYZE FORMAT=TREE " + first.sql(), first.parameters());
        Path planRoot = evidenceRoot.resolve("plans").resolve(tier.toLowerCase(Locale.ROOT));
        Files.createDirectories(planRoot);
        Files.writeString(planRoot.resolve("rpt-inventory-explain.json"), logicalPlan + "\n",
            StandardCharsets.UTF_8);
        Files.writeString(planRoot.resolve("rpt-inventory-analyze.txt"), actualPlan + "\n",
            StandardCharsets.UTF_8);

        QueryCounter interactive = new QueryCounter(connection);
        List<Row> firstRows = interactive.read(first);
        assertThat(firstRows).hasSize(501);
        long expected = count(connection, "SELECT COUNT(*) FROM rpt_inventory_cost_daily WHERE tenant_id=? "
            + "AND projection_version=? AND business_date BETWEEN ? AND ? AND store_id BETWEEN 1001 AND 1050",
            List.of(SqlBaselineQueries.TENANT_A, "v1", from, to));
        long crossTenantRows = read(connection, SqlBaselineQueries.inventoryCostPage(repositoryRoot,
            SqlBaselineQueries.ABSENT_TENANT, "v1", from, to, STORES, null, 501)).size();

        QueryCounter export = new QueryCounter(connection);
        Set<String> keys = new LinkedHashSet<>();
        Map<String, BigDecimal> sums = zeroSums();
        SqlBaselineQueries.InventoryCostKey cursor = null;
        long observed = 0;
        for (;;) {
            List<Row> page = export.read(SqlBaselineQueries.inventoryCostPage(repositoryRoot,
                SqlBaselineQueries.TENANT_A, "v1", from, to, STORES, cursor, 10_000));
            for (Row row : page) {
                keys.add(row.key());
                observed++;
                for (String column : SUM_COLUMNS) sums.put(column, sums.get(column).add(row.values().get(column)));
            }
            if (page.isEmpty() || page.size() < 10_000) break;
            cursor = page.get(page.size() - 1).cursor();
        }
        Map<String, BigDecimal> authoritative = authoritativeSums(connection, from, to);
        JsonNode plan = JSON.readTree(logicalPlan);
        boolean fullScan = observesAccessAll(plan);
        boolean filesort = observesFilesort(plan);
        boolean indexCrRequired = fullScan || filesort;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tier", tier);
        result.put("seededRows", seededRows);
        result.put("expectedRows", expected);
        result.put("observedRows", observed);
        result.put("interactiveRows", firstRows.size());
        result.put("interactiveQueryCount", interactive.count());
        result.put("exportQueryCount", export.count());
        result.put("duplicateKeys", observed - keys.size());
        result.put("missingRows", expected - observed);
        result.put("crossTenantRows", crossTenantRows);
        result.put("twelveFieldSums", sums);
        result.put("authoritativeSums", authoritative);
        result.put("twelveFieldConservationPassed", sums.equals(authoritative));
        result.put("fullScanObserved", fullScan);
        result.put("filesortObserved", filesort);
        result.put("indexCrRequired", indexCrRequired);
        result.put("recommendation", indexCrRequired ? "STOP_AND_REQUEST_INDEPENDENT_INDEX_CR"
            : "KEEP_EXISTING_INDEXES");
        result.put("sourceSqlSha256", SqlBaselineQueries.sha256(
            SqlBaselineQueries.inventoryCostPageSource(repositoryRoot)));
        result.put("logicalPlanSha256", SqlBaselineQueries.sha256(logicalPlan));
        result.put("actualPlanSha256", SqlBaselineQueries.sha256(actualPlan));
        result.put("schemaOrIndexChanged", false);
        result.put("syntheticOnly", true);
        result.put("productionSla", false);
        writeJson(planRoot.resolve("rpt-inventory-result.json"), result);
        return result;
    }

    private Map<String, BigDecimal> authoritativeSums(Connection connection, java.sql.Date from,
                                                       java.sql.Date to) throws SQLException {
        String expressions = String.join(",", SUM_COLUMNS.stream().map(column -> "SUM(" + sqlColumn(column)
            + ") AS " + column).toList());
        String sql = "SELECT " + expressions + " FROM rpt_inventory_cost_daily WHERE tenant_id=? "
            + "AND projection_version=? AND business_date BETWEEN ? AND ? AND store_id BETWEEN 1001 AND 1050";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, List.of(SqlBaselineQueries.TENANT_A, "v1", from, to));
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Map<String, BigDecimal> sums = zeroSums();
                for (String column : SUM_COLUMNS) sums.put(column, rows.getBigDecimal(column));
                return sums;
            }
        }
    }

    private static String sqlColumn(String alias) {
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < alias.length(); index++) {
            char character = alias.charAt(index);
            if (Character.isUpperCase(character)) value.append('_').append(Character.toLowerCase(character));
            else value.append(character);
        }
        return value.toString();
    }

    private static Map<String, BigDecimal> zeroSums() {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        SUM_COLUMNS.forEach(column -> result.put(column, BigDecimal.ZERO.setScale(6)));
        return result;
    }

    private static List<Row> read(Connection connection, SqlBaselineQueries.ExecutableQuery query)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            bind(statement, query.parameters());
            try (ResultSet rows = statement.executeQuery()) {
                List<Row> result = new ArrayList<>();
                while (rows.next()) result.add(toRow(rows));
                return result;
            }
        }
    }

    private static Row toRow(ResultSet rows) throws SQLException {
        Map<String, BigDecimal> values = zeroSums();
        for (String column : SUM_COLUMNS) values.put(column, rows.getBigDecimal(column));
        java.sql.Date day = rows.getDate("businessDate");
        long store = rows.getLong("storeId");
        String warehouse = rows.getString("warehouseId");
        long sku = rows.getLong("skuId");
        String currency = rows.getString("currency");
        return new Row(day + "|" + store + "|" + warehouse + "|" + sku + "|" + currency,
            new SqlBaselineQueries.InventoryCostKey(day, store, warehouse, sku, currency), Map.copyOf(values));
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

    private String[] migrationLocations() throws Exception {
        Path root = Path.of(required("G10A_INVENTORY_MIGRATION_ROOT")).toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<String> locations = paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src", "main", "resources", "db", "migration")))
                .sorted().map(path -> "filesystem:" + path.toString().replace('\\', '/')).toList();
            assertThat(locations).hasSize(22);
            return locations.toArray(String[]::new);
        }
    }

    private void writeIndexCatalog(Connection connection) throws Exception {
        List<Map<String, Object>> indexes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW INDEX FROM rpt_inventory_cost_daily")) {
            while (rows.next()) indexes.add(Map.of("indexName", rows.getString("Key_name"),
                "sequence", rows.getInt("Seq_in_index"), "column", rows.getString("Column_name")));
        }
        writeJson(evidenceRoot.resolve("show-index.json"), indexes);
    }

    private static long count(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        }
    }

    private static String firstColumn(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) statement.setObject(index + 1, parameters.get(index));
    }

    private static boolean observesAccessAll(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("access_type".equals(field.getKey()) && "ALL".equalsIgnoreCase(field.getValue().asText())) return true;
                if (observesAccessAll(field.getValue())) return true;
            }
        } else if (node.isArray()) for (JsonNode child : node) if (observesAccessAll(child)) return true;
        return false;
    }

    private static boolean observesFilesort(JsonNode node) {
        if (node == null) return false;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().toLowerCase(Locale.ROOT).contains("filesort")
                    && field.getValue().isBoolean() && field.getValue().booleanValue()) return true;
                if (observesFilesort(field.getValue())) return true;
            }
        } else if (node.isArray()) for (JsonNode child : node) if (observesFilesort(child)) return true;
        return false;
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

    private static void writeJson(Path path, Object value) throws Exception {
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), value);
    }

    private record Row(String key, SqlBaselineQueries.InventoryCostKey cursor,
                       Map<String, BigDecimal> values) {
    }

    private static final class QueryCounter {
        private final Connection connection;
        private int count;
        private QueryCounter(Connection connection) { this.connection = connection; }
        private List<Row> read(SqlBaselineQueries.ExecutableQuery query) throws SQLException {
            count++;
            return InventoryKeysetRemediationMySqlIT.read(connection, query);
        }
        private int count() { return count; }
    }
}
