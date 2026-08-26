package com.jingshanghui.pos.release.performance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Gate 10A-R2-R2-R1 MySQL 8.4 可执行计划、JDBC查询数和租户权限红基线。
 *
 * <p>该测试只读正式 Mapper，并向临时数据库写入确定性合成夹具；不会修改生产 SQL、Mapper、
 * 索引或迁移。结果用于判断后续是否需要 CR，不形成生产容量或商业 SLA。</p>
 */
class SqlExecutableBaselineMySqlIT {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern SOURCE_DIGEST = Pattern.compile("^([^,]+),(?:[^,]*,){4}([a-f0-9]{64}),.*$");
    private static final Pattern ESTIMATED_ROWS = Pattern.compile("\\\"rows_examined_per_scan\\\"\\s*:\\s*([0-9.]+)");
    private static final Pattern ACTUAL_ROWS = Pattern.compile("actual time=[^)]*? rows=([0-9]+)");

    private final String url = required("G10A_SQL_MYSQL_JDBC_URL");
    private final String username = required("G10A_SQL_MYSQL_USERNAME");
    private final String password = required("G10A_SQL_MYSQL_PASSWORD");
    private final Path repositoryRoot = Path.of(required("G10A_SQL_REPO_ROOT")).toAbsolutePath().normalize();
    private final Path evidenceRoot = Path.of(required("G10A_SQL_EVIDENCE_DIR")).toAbsolutePath().normalize();

    @Test
    void capturesExecutablePlansCountsAndTenantPermissionBoundaries() throws Exception {
        Files.createDirectories(evidenceRoot);
        migrateEmptyDatabase();
        Map<String, String> frozenDigests = frozenSourceDigests();
        List<Map<String, Object>> queryResults = new ArrayList<>();
        List<Map<String, Object>> decisions = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            configureSession(connection);
            SqlBaselineFixture.extendAll(connection, 0, 10_000);
            SqlBaselineFixture.analyze(connection);
            captureTier(connection, "SMOKE_10K", 10_000, SqlBaselineQueries.all(), frozenDigests,
                queryResults, decisions);

            SqlBaselineFixture.extendAll(connection, 10_000, 100_000);
            SqlBaselineFixture.analyze(connection);
            captureTier(connection, "BASELINE_100K", 100_000, SqlBaselineQueries.all(), frozenDigests,
                queryResults, decisions);
            captureJourneyCounts(connection);
            captureTableAndIndexEvidence(connection, "BASELINE_100K");

            SqlBaselineFixture.extendSalesTrend(connection, 100_000, 1_000_000);
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE TABLE rpt_sales_daily");
            }
            List<SqlBaselineQueries.QuerySpec> trend = SqlBaselineQueries.all().stream()
                .filter(SqlBaselineQueries.QuerySpec::approvedMillionTrend).toList();
            assertThat(trend).extracting(SqlBaselineQueries.QuerySpec::queryId).containsExactly("RPT-SALES");
            captureTier(connection, "TREND_1M", 1_000_000, trend, frozenDigests, queryResults, decisions);
            captureTableAndIndexEvidence(connection, "TREND_1M_APPROVED_ONLY");
        }

        captureReadOnlyPrivilegeBoundary();
        writeJson(evidenceRoot.resolve("query-results.json"), queryResults);
        writeJson(evidenceRoot.resolve("decision-candidates.json"), decisions);
        writeCsv(queryResults);
        writeEnvironment();

        long requiredTiers = queryResults.stream()
            .filter(row -> !"TREND_1M".equals(row.get("tier"))).count();
        assertThat(requiredTiers).isEqualTo(24);
        assertThat(queryResults).allSatisfy(row -> {
            assertThat(row.get("queryCount")).isEqualTo(1);
            assertThat(row.get("crossTenantRows")).isEqualTo(0L);
            assertThat(row.get("executed")).isEqualTo(true);
        });
    }

    private void captureTier(Connection connection, String tier, int primaryRows,
                             List<SqlBaselineQueries.QuerySpec> specs, Map<String, String> frozenDigests,
                             List<Map<String, Object>> results, List<Map<String, Object>> decisions) throws Exception {
        Path tierRoot = evidenceRoot.resolve("plans").resolve(tier.toLowerCase(Locale.ROOT));
        Files.createDirectories(tierRoot);
        for (SqlBaselineQueries.QuerySpec spec : specs) {
            String sourceStatement = spec.sourceStatement(repositoryRoot);
            String sourceDigest = SqlBaselineQueries.sha256(sourceStatement);
            assertThat(sourceDigest).as(spec.queryId()).isEqualTo(frozenDigests.get(spec.queryId()));
            SqlBaselineQueries.ExecutableQuery query = spec.executable(repositoryRoot);
            assertThat(query.sql()).doesNotContainIgnoringCase("SELECT *");

            String logicalPlan = firstColumn(connection, "EXPLAIN FORMAT=JSON " + query.sql(), query.parameters());
            String actualPlan = firstColumn(connection, "EXPLAIN ANALYZE FORMAT=TREE " + query.sql(), query.parameters());
            JdbcQueryCounter counter = new JdbcQueryCounter(connection);
            long returnedRows = counter.execute(query.sql(), query.parameters(), ignored -> { });
            List<Object> attackParameters = new ArrayList<>(query.parameters());
            assertThat(attackParameters.get(0)).isIn(SqlBaselineQueries.TENANT_A, SqlBaselineQueries.TENANT_B);
            attackParameters.set(0, SqlBaselineQueries.ABSENT_TENANT);
            long crossTenantRows = executeRows(connection, query.sql(), attackParameters, ignored -> { });

            String fileStem = spec.queryId().toLowerCase(Locale.ROOT);
            Files.writeString(tierRoot.resolve(fileStem + "-explain.json"), logicalPlan + "\n", StandardCharsets.UTF_8);
            Files.writeString(tierRoot.resolve(fileStem + "-analyze.txt"), actualPlan + "\n", StandardCharsets.UTF_8);
            writeJson(tierRoot.resolve(fileStem + "-parameters.json"), safeParameters(spec, query));

            boolean fullScan = logicalPlan.contains("\"access_type\": \"ALL\"")
                || logicalPlan.contains("\"access_type\":\"ALL\"");
            boolean filesort = logicalPlan.contains("using_filesort") || logicalPlan.contains("filesort");
            double estimatedRows = maxNumber(ESTIMATED_ROWS, logicalPlan);
            long actualRows = Math.round(maxNumber(ACTUAL_ROWS, actualPlan));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("queryId", spec.queryId());
            result.put("owner", spec.owner());
            result.put("tier", tier);
            result.put("primaryRows", primaryRows);
            result.put("sourceSqlSha256", sourceDigest);
            result.put("executableSqlSha256", SqlBaselineQueries.sha256(query.sql()));
            result.put("logicalPlanSha256", SqlBaselineQueries.sha256(logicalPlan));
            result.put("actualPlanSha256", SqlBaselineQueries.sha256(actualPlan));
            result.put("queryCount", counter.count());
            result.put("rowsReturned", returnedRows);
            result.put("estimatedRowsMaximum", estimatedRows);
            result.put("actualPlanRowsMaximum", actualRows);
            result.put("fullScanObserved", fullScan);
            result.put("filesortObserved", filesort);
            result.put("crossTenantRows", crossTenantRows);
            result.put("budgetClass", spec.budgetClass());
            result.put("approvedMillionTrend", spec.approvedMillionTrend());
            result.put("executed", true);
            results.add(result);

            if ("BASELINE_100K".equals(tier)) {
                decisions.add(decision(spec, fullScan, filesort, returnedRows));
            }
        }
    }

    private Map<String, Object> decision(SqlBaselineQueries.QuerySpec spec, boolean fullScan,
                                         boolean filesort, long returnedRows) {
        boolean paginationCr = Set.of("RPT-SALES", "RPT-INVENTORY", "RPT-PAY-REC").contains(spec.queryId());
        String recommendation;
        if (paginationCr) {
            recommendation = "CR_REQUIRED_BEFORE_PAGINATION_OR_RESPONSE_CHANGE";
        } else if (fullScan || filesort) {
            recommendation = "NO_GO_RUNTIME_PLAN_REVIEW_REQUIRED";
        } else {
            recommendation = "GO_CANDIDATE_KEEP_SQL_AND_INDEX_UNCHANGED";
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("queryId", spec.queryId());
        decision.put("recommendation", recommendation);
        decision.put("fullScanObservedAt100K", fullScan);
        decision.put("filesortObservedAt100K", filesort);
        decision.put("rowsReturnedAt100K", returnedRows);
        decision.put("runtimeChangeAuthorized", false);
        decision.put("requiresSponsorConfirmation", true);
        return decision;
    }

    private void captureJourneyCounts(Connection connection) throws Exception {
        List<Map<String, Object>> journeys = new ArrayList<>();
        Map<String, SqlBaselineQueries.QuerySpec> byId = SqlBaselineQueries.all().stream()
            .collect(Collectors.toMap(SqlBaselineQueries.QuerySpec::queryId, spec -> spec));

        JdbcQueryCounter reports = new JdbcQueryCounter(connection);
        long reportRows = 0;
        for (long store = 1001; store <= 1050; store++) {
            for (String queryId : List.of("RPT-SALES", "RPT-INVENTORY", "RPT-PAY-REC")) {
                SqlBaselineQueries.ExecutableQuery query = byId.get(queryId).executable(repositoryRoot);
                List<Object> parameters = new ArrayList<>(query.parameters());
                parameters.set(parameters.size() - 1, store);
                reportRows += reports.execute(query.sql(), parameters, ignored -> { });
            }
        }
        assertThat(reports.count()).isEqualTo(150);
        journeys.add(journey("REPORT_EXPORT_50_STORES_3_TYPES", reports.count(), reportRows,
            "NO_GO_LINEAR_QUERY_AMPLIFICATION"));

        JdbcQueryCounter payments = new JdbcQueryCounter(connection);
        SqlBaselineQueries.ExecutableQuery paymentRange = byId.get("PAY-FACTS").executable(repositoryRoot);
        long paymentRows = payments.execute(paymentRange.sql(), paymentRange.parameters(), ignored -> { });
        String referenceSql = """
            SELECT a.provider_transaction_no AS reference,p.payment_id AS aggregateId,'PAYMENT' AS businessType,
              CASE WHEN p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') THEN 'SUCCEEDED' ELSE p.status END AS status,
              p.amount_minor AS amountMinor,p.currency,p.occurred_at AS occurredAt
            FROM pay_payment_attempt a JOIN pay_payment_intent p
              ON p.tenant_id=a.tenant_id AND p.payment_id=a.payment_id
            WHERE a.tenant_id=? AND a.provider_code=? AND a.provider_transaction_no=?
            UNION ALL
            SELECT r.provider_refund_no AS reference,r.refund_id AS aggregateId,'REFUND' AS businessType,
              r.status,r.amount_minor AS amountMinor,r.currency,r.occurred_at AS occurredAt
            FROM pay_refund r WHERE r.tenant_id=? AND r.provider_code=? AND r.provider_refund_no=?
            ORDER BY businessType,aggregateId
            """.replaceAll("\\s+", " ").trim();
        int paymentReference = 0;
        for (int row = 0; paymentReference < 500; row++) {
            if (row % 5 == 4) {
                continue;
            }
            String reference = "TXN-" + String.format("%010d", row);
            paymentRows += payments.execute(referenceSql, List.of(SqlBaselineQueries.TENANT_A, "LAKALA", reference,
                SqlBaselineQueries.TENANT_A, "LAKALA", reference), ignored -> { });
            paymentReference++;
        }
        assertThat(payments.count()).isEqualTo(501);
        journeys.add(journey("PAYMENT_RECONCILIATION_500_REFERENCES", payments.count(), paymentRows,
            "NO_GO_LINEAR_QUERY_AMPLIFICATION"));

        SqlBaselineQueries.ExecutableQuery expiry = byId.get("INV-EXPIRY").executable(repositoryRoot);
        List<Long> skuIds = new ArrayList<>();
        executeRows(connection, expiry.sql(), expiry.parameters(), rows -> {
            try {
                skuIds.add(rows.getLong("skuId"));
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(skuIds).hasSize(500);
        SqlBaselineFixture.seedLotPolicies(connection, skuIds);
        JdbcQueryCounter lotPolicies = new JdbcQueryCounter(connection);
        long lotRows = lotPolicies.execute(expiry.sql(), expiry.parameters(), ignored -> { });
        String policySql = """
            SELECT policy_version_id AS policyVersionId,store_id AS storeId,sku_id AS skuId,enabled,
              expiry_basis AS expiryBasis,shelf_life_days AS shelfLifeDays,near_expiry_days AS nearExpiryDays,
              industry,template_version_id AS templateVersionId,effective_from AS effectiveFrom,
              content_sha256 AS contentSha256,state
            FROM cat_lot_policy_version
            WHERE tenant_id=? AND store_id=? AND sku_id=? AND state='PUBLISHED' AND effective_from<=?
            ORDER BY effective_from DESC,policy_version_id DESC LIMIT 1
            """.replaceAll("\\s+", " ").trim();
        for (Long skuId : skuIds) {
            lotRows += lotPolicies.execute(policySql, List.of(SqlBaselineQueries.TENANT_B,
                SqlBaselineQueries.HOT_STORE, skuId, Timestamp.from(Instant.parse("2026-08-26T12:00:00Z"))), ignored -> { });
        }
        assertThat(lotPolicies.count()).isEqualTo(501);
        journeys.add(journey("LOT_EXPIRY_500_CANDIDATES", lotPolicies.count(), lotRows,
            "NO_GO_LINEAR_QUERY_AMPLIFICATION"));
        writeJson(evidenceRoot.resolve("jdbc-query-counts.json"), journeys);
    }

    private Map<String, Object> journey(String name, int queryCount, long rows, String recommendation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("journey", name);
        result.put("jdbcQueryCount", queryCount);
        result.put("rowsObserved", rows);
        result.put("recommendation", recommendation);
        result.put("runtimeChangeAuthorized", false);
        return result;
    }

    private void captureTableAndIndexEvidence(Connection connection, String tier) throws Exception {
        Path directory = evidenceRoot.resolve("database").resolve(tier.toLowerCase(Locale.ROOT));
        Files.createDirectories(directory);
        List<Map<String, Object>> tables = new ArrayList<>();
        for (String table : SqlBaselineFixture.PRIMARY_TABLES) {
            long actual = singleLong(connection, "SELECT COUNT(*) FROM " + table);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("table", table);
            item.put("actualRows", actual);
            try (PreparedStatement query = connection.prepareStatement("""
                SELECT table_rows,data_length,index_length FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name=?
                """)) {
                query.setString(1, table);
                try (ResultSet rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    item.put("estimatedRows", rows.getLong(1));
                    item.put("dataBytes", rows.getLong(2));
                    item.put("indexBytes", rows.getLong(3));
                }
            }
            tables.add(item);
        }
        writeJson(directory.resolve("table-statistics.json"), tables);

        List<Map<String, Object>> indexes = new ArrayList<>();
        for (String table : SqlBaselineFixture.PRIMARY_TABLES) {
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SHOW INDEX FROM " + table)) {
                while (rows.next()) {
                    Map<String, Object> index = new LinkedHashMap<>();
                    index.put("table", table);
                    index.put("indexName", rows.getString("Key_name"));
                    index.put("nonUnique", rows.getInt("Non_unique"));
                    index.put("sequence", rows.getInt("Seq_in_index"));
                    index.put("column", rows.getString("Column_name"));
                    index.put("cardinality", rows.getLong("Cardinality"));
                    indexes.add(index);
                }
            }
        }
        writeJson(directory.resolve("show-index.json"), indexes);
        Files.writeString(directory.resolve("optimizer-switch.txt"),
            firstColumn(connection, "SELECT @@optimizer_switch", List.of()) + "\n", StandardCharsets.UTF_8);
    }

    private void captureReadOnlyPrivilegeBoundary() throws Exception {
        String probeUser = "g10a_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String probePassword = UUID.randomUUID() + "Aa1!";
        String database;
        try (Connection admin = DriverManager.getConnection(url, username, password)) {
            database = admin.getCatalog();
            try (Statement statement = admin.createStatement()) {
                statement.execute("CREATE USER '" + probeUser + "'@'%' IDENTIFIED BY '" + probePassword + "'");
                statement.execute("GRANT SELECT ON `" + database + "`.* TO '" + probeUser + "'@'%'");
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        try (Connection readOnly = DriverManager.getConnection(url, probeUser, probePassword)) {
            assertThat(singleLong(readOnly, "SELECT COUNT(*) FROM rpt_sales_daily WHERE tenant_id='" +
                SqlBaselineQueries.TENANT_A + "'")).isPositive();
            assertThatThrownBy(() -> {
                try (Statement statement = readOnly.createStatement()) {
                    statement.executeUpdate("UPDATE rpt_sales_daily SET projection_status='CURRENT' WHERE 1=0");
                }
            }).isInstanceOf(SQLException.class);
            evidence.put("selectGranted", true);
            evidence.put("writeDenied", true);
            evidence.put("credentialPersisted", false);
            evidence.put("tenantAttackRows", 0);
        } finally {
            try (Connection admin = DriverManager.getConnection(url, username, password);
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP USER IF EXISTS '" + probeUser + "'@'%'");
            }
        }
        writeJson(evidenceRoot.resolve("permission-boundary.json"), evidence);
    }

    private void migrateEmptyDatabase() throws Exception {
        createFrameworkMenuFixture();
        Flyway flyway = Flyway.configure().dataSource(url, username, password)
            .locations(migrationLocations()).table("jshpos_flyway_schema_history")
            .baselineOnMigrate(true).baselineVersion("0").cleanDisabled(true).load();
        flyway.migrate();
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("202608260087");
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
                  role_id BIGINT NOT NULL,menu_id BIGINT NOT NULL,PRIMARY KEY (role_id,menu_id)
                ) ENGINE=InnoDB
                """);
        }
    }

    private String[] migrationLocations() throws IOException {
        Path root = Path.of(required("G10A_SQL_MIGRATION_ROOT")).toAbsolutePath().normalize();
        try (var paths = Files.walk(root)) {
            List<String> locations = paths.filter(Files::isDirectory)
                .filter(path -> path.endsWith(Path.of("src", "main", "resources", "db", "migration")))
                .sorted().map(path -> "filesystem:" + path.toString().replace('\\', '/')).toList();
            assertThat(locations).hasSize(22);
            return locations.toArray(String[]::new);
        }
    }

    private Map<String, String> frozenSourceDigests() throws IOException {
        Path catalog = repositoryRoot.resolve("contracts/t2/gate10a-r2-r2-sql-prep/query-catalog-v1.csv");
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(catalog, StandardCharsets.UTF_8).stream().skip(1).toList()) {
            Matcher matcher = SOURCE_DIGEST.matcher(line);
            assertThat(matcher.matches()).as(line).isTrue();
            result.put(matcher.group(1), matcher.group(2));
        }
        assertThat(result).hasSize(12);
        return result;
    }

    private Map<String, Object> safeParameters(SqlBaselineQueries.QuerySpec spec,
                                               SqlBaselineQueries.ExecutableQuery query) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queryId", spec.queryId());
        result.put("parameterVariant", "BASE_OPTIONAL_FILTERS_ABSENT");
        result.put("values", query.parameters().stream().map(value -> value == null ? null : value.toString()).toList());
        result.put("syntheticOnly", true);
        result.put("containsCredential", false);
        return result;
    }

    private void writeEnvironment() throws Exception {
        Map<String, Object> environment = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            environment.put("mysqlVersion", firstColumn(connection, "SELECT VERSION()", List.of()));
            environment.put("timeZone", firstColumn(connection, "SELECT @@time_zone", List.of()));
            environment.put("characterSet", firstColumn(connection, "SELECT @@character_set_server", List.of()));
            environment.put("collation", firstColumn(connection, "SELECT @@collation_server", List.of()));
            environment.put("sqlMode", firstColumn(connection, "SELECT @@sql_mode", List.of()));
            environment.put("schemaVersion", firstColumn(connection,
                "SELECT version FROM jshpos_flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1",
                List.of()));
        }
        environment.put("runner", System.getenv().getOrDefault("RUNNER_OS", "UNKNOWN"));
        environment.put("commit", System.getenv().getOrDefault("GITHUB_SHA", "LOCAL"));
        environment.put("capturedAt", Instant.now().toString());
        environment.put("syntheticOnly", true);
        environment.put("productionSla", false);
        writeJson(evidenceRoot.resolve("environment.json"), environment);
    }

    private void writeCsv(List<Map<String, Object>> rows) throws IOException {
        String header = "query_id,tier,primary_rows,query_count,rows_returned,estimated_rows_max,actual_rows_max,full_scan,filesort,cross_tenant_rows,source_sha256,executable_sha256\n";
        StringBuilder csv = new StringBuilder(header);
        for (Map<String, Object> row : rows) {
            csv.append(row.get("queryId")).append(',').append(row.get("tier")).append(',')
                .append(row.get("primaryRows")).append(',').append(row.get("queryCount")).append(',')
                .append(row.get("rowsReturned")).append(',').append(row.get("estimatedRowsMaximum")).append(',')
                .append(row.get("actualPlanRowsMaximum")).append(',').append(row.get("fullScanObserved")).append(',')
                .append(row.get("filesortObserved")).append(',').append(row.get("crossTenantRows")).append(',')
                .append(row.get("sourceSqlSha256")).append(',').append(row.get("executableSqlSha256")).append('\n');
        }
        Files.writeString(evidenceRoot.resolve("query-results.csv"), csv, StandardCharsets.UTF_8);
    }

    private static void configureSession(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION max_execution_time=60000");
            statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
        }
    }

    private static String firstColumn(Connection connection, String sql, List<Object> parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).as(sql).isTrue();
                return rows.getString(1);
            }
        }
    }

    private static long executeRows(Connection connection, String sql, List<Object> parameters,
                                    Consumer<ResultSet> consumer) throws SQLException {
        long count = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    consumer.accept(rows);
                    count++;
                }
            }
        }
        return count;
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private static long singleLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static double maxNumber(Pattern pattern, String value) {
        double maximum = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            maximum = Math.max(maximum, Double.parseDouble(matcher.group(1)));
        }
        return maximum;
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        JSON.writeValue(path.toFile(), value);
    }

    private static Map<String, Object> journey(String name, Object value) {
        return Map.of("name", name, "value", value);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be provided by CI");
        }
        return value;
    }

    /** 只统计当前夹具通过 JDBC 实际发出的查询，不依据墙钟时间推导。 */
    private static final class JdbcQueryCounter {
        private final Connection connection;
        private int count;

        private JdbcQueryCounter(Connection connection) {
            this.connection = connection;
        }

        long execute(String sql, List<Object> parameters, Consumer<ResultSet> consumer) throws SQLException {
            count++;
            return executeRows(connection, sql, parameters, consumer);
        }

        int count() {
            return count;
        }
    }
}
