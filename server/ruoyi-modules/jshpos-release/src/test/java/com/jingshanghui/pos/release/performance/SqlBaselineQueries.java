package com.jingshanghui.pos.release.performance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从正式 Mapper XML 生成 Gate 10A SQL 基线的只读可执行适配。
 *
 * <p>适配器不复制生产 SQL：每次执行都从当前工作树读取已冻结 statement，解析 include，
 * 丢弃本批明确冻结为空的可选筛选分支，再将 MyBatis 参数替换为 JDBC 占位符。这样既保留
 * 正式 SQL 身份，又确保任何 Mapper 漂移都会先由摘要门禁失败。</p>
 */
final class SqlBaselineQueries {
    static final String TENANT_A = "9000000000000000001";
    static final String TENANT_B = "9000000000000000002";
    static final String ABSENT_TENANT = "9000000000000000999";
    static final long HOT_STORE = 1001L;
    static final long HOT_SKU = 10001L;
    static final String HOT_WAREHOUSE = id(2_100_001L);
    static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 8, 26);
    static final Timestamp BUSINESS_INSTANT = Timestamp.from(Instant.parse("2026-08-26T12:00:00Z"));

    private static final Pattern SELECT = Pattern.compile("<select\\s+id=\"%s\"[^>]*>(.*?)</select>", Pattern.DOTALL);
    private static final Pattern SQL_FRAGMENT = Pattern.compile("<sql\\s+id=\"%s\"[^>]*>(.*?)</sql>", Pattern.DOTALL);
    private static final Pattern INCLUDE = Pattern.compile("<include\\s+refid=\"([^\"]+)\"\\s*/>");
    private static final Pattern IF_BLOCK = Pattern.compile("<if\\s+test=\"[^\"]*\">(.*?)</if>", Pattern.DOTALL);
    private static final Pattern PARAMETER = Pattern.compile("#\\{([^},]+)[^}]*}");

    private SqlBaselineQueries() {
    }

    /** 单条正式查询的测试执行身份、参数和证据分类。 */
    record QuerySpec(
        String queryId,
        String owner,
        String mapperPath,
        String statementId,
        LinkedHashMap<String, Object> parameters,
        List<String> tables,
        String budgetClass,
        boolean approvedMillionTrend
    ) {
        String sourceStatement(Path repositoryRoot) throws IOException {
            String xml = Files.readString(repositoryRoot.resolve(mapperPath), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(SELECT.pattern().formatted(Pattern.quote(statementId)), Pattern.DOTALL)
                .matcher(xml);
            if (!matcher.find()) {
                throw new IllegalStateException("缺少正式 Mapper statement: " + queryId + " " + statementId);
            }
            return normalize(stripComments(matcher.group(1)));
        }

        ExecutableQuery executable(Path repositoryRoot) throws IOException {
            String xml = Files.readString(repositoryRoot.resolve(mapperPath), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile(SELECT.pattern().formatted(Pattern.quote(statementId)), Pattern.DOTALL)
                .matcher(xml);
            if (!matcher.find()) {
                throw new IllegalStateException("缺少正式 Mapper statement: " + queryId + " " + statementId);
            }
            String body = stripComments(matcher.group(1));
            for (;;) {
                Matcher include = INCLUDE.matcher(body);
                if (!include.find()) {
                    break;
                }
                String fragmentId = include.group(1);
                Matcher fragment = Pattern.compile(SQL_FRAGMENT.pattern().formatted(Pattern.quote(fragmentId)), Pattern.DOTALL)
                    .matcher(xml);
                if (!fragment.find()) {
                    throw new IllegalStateException("缺少 include: " + mapperPath + "#" + fragmentId);
                }
                body = body.substring(0, include.start()) + stripComments(fragment.group(1)) + body.substring(include.end());
            }
            // 三条报表查询的 terminal/cashier/warehouse/sku 可选条件在本基线冻结为未提供。
            body = IF_BLOCK.matcher(body).replaceAll("");
            body = unescapeXml(body);
            Matcher parameter = PARAMETER.matcher(body);
            List<Object> ordered = new ArrayList<>();
            StringBuffer sql = new StringBuffer();
            while (parameter.find()) {
                String name = parameter.group(1).trim();
                if (!parameters.containsKey(name)) {
                    throw new IllegalStateException(queryId + " 缺少参数: " + name);
                }
                ordered.add(parameters.get(name));
                parameter.appendReplacement(sql, "?");
            }
            parameter.appendTail(sql);
            return new ExecutableQuery(normalize(sql.toString()), List.copyOf(ordered));
        }
    }

    /** JDBC 可执行 SQL 及严格按 Mapper 占位符顺序冻结的参数。 */
    record ExecutableQuery(String sql, List<Object> parameters) {
    }

    static List<QuerySpec> all() {
        return List.of(
            spec("INV-FEFO", "Inventory", "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/LotInventoryMapper.xml",
                "lockFefoCandidates", params("tenantId", TENANT_B, "warehouseId", HOT_WAREHOUSE, "skuId", HOT_SKU,
                    "businessDate", Date.valueOf(BUSINESS_DATE), "limit", 100),
                List.of("inv_lot_identity", "inv_lot_balance", "inv_lot_expiry_projection"), "LOCKING", false),
            spec("INV-EXPIRY", "Inventory", "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/LotInventoryMapper.xml",
                "findNearExpiry", params("tenantId", TENANT_B, "storeId", HOT_STORE, "warehouseId", HOT_WAREHOUSE),
                List.of("inv_lot_identity", "inv_lot_balance", "inv_lot_expiry_projection"), "INTERACTIVE", false),
            spec("INV-PACKAGE", "Inventory", "server/ruoyi-modules/jshpos-inventory/src/main/resources/mapper/inventory/LotInventoryMapper.xml",
                "findPackageLots", params("tenantId", TENANT_B, "storeId", HOT_STORE, "warehouseId", HOT_WAREHOUSE, "limit", 100001),
                List.of("inv_lot_identity", "inv_lot_balance", "inv_lot_expiry_projection"), "PACKAGE_OR_EXPORT", false),
            spec("PRM-RULES", "Promotion", "server/ruoyi-modules/jshpos-promotion/src/main/resources/mapper/promotion/PromotionPersistenceMapper.xml",
                "listPublishedRules", params("tenantId", TENANT_A, "at", BUSINESS_INSTANT),
                List.of("prm_rule_version", "prm_rule_scope", "prm_rule_benefit"), "INTERACTIVE", false),
            spec("PRM-QUOTE-LINES", "Promotion", "server/ruoyi-modules/jshpos-promotion/src/main/resources/mapper/promotion/PromotionPersistenceMapper.xml",
                "listQuoteLines", params("tenantId", TENANT_A, "quoteId", id(4_000_000_000L)),
                List.of("prm_quote_line"), "DOCUMENT_LINES", false),
            spec("RPT-SALES", "Reporting", "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml",
                "querySales", params("tenantId", TENANT_A, "projectionVersion", "v1", "fromDate", Date.valueOf("2026-08-01"),
                    "toDate", Date.valueOf("2026-08-31"), "storeId", HOT_STORE),
                List.of("rpt_sales_daily"), "INTERACTIVE", true),
            spec("RPT-INVENTORY", "Reporting", "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/ReportingPersistenceMapper.xml",
                "queryInventoryCost", params("tenantId", TENANT_A, "projectionVersion", "v1", "fromDate", Date.valueOf("2026-08-01"),
                    "toDate", Date.valueOf("2026-08-31"), "storeId", HOT_STORE),
                List.of("rpt_inventory_cost_daily"), "INTERACTIVE", false),
            spec("RPT-PAY-REC", "Reporting", "server/ruoyi-modules/jshpos-reporting/src/main/resources/mapper/reporting/PaymentReconciliationMapper.xml",
                "query", params("tenantId", TENANT_A, "fromDate", Date.valueOf("2026-08-01"),
                    "toDate", Date.valueOf("2026-08-31"), "storeId", HOT_STORE),
                List.of("rpt_payment_reconciliation"), "INTERACTIVE", false),
            spec("PAY-FACTS", "Payment", "server/ruoyi-modules/jshpos-payment/src/main/resources/mapper/payment/PaymentMapper.xml",
                "findInternalFacts", params("tenantId", TENANT_A, "providerCode", "LAKALA",
                    "from", Timestamp.valueOf("2026-08-26 00:00:00"), "to", Timestamp.valueOf("2026-08-27 00:00:00")),
                List.of("pay_payment_attempt", "pay_payment_intent", "pay_refund"), "INTERACTIVE", false),
            spec("PUR-LINES", "Procurement", "server/ruoyi-modules/jshpos-procurement/src/main/resources/mapper/procurement/ProcurementMapper.xml",
                "findOrderLines", params("tenantId", TENANT_A, "orderId", id(10_000_000_000L)),
                List.of("pur_purchase_order_line"), "DOCUMENT_LINES", false),
            spec("TRF-LINES", "Transfer", "server/ruoyi-modules/jshpos-transfer/src/main/resources/mapper/transfer/TransferMapper.xml",
                "findLines", params("tenantId", TENANT_A, "transferId", id(12_000_000_000L)),
                List.of("inv_transfer_line"), "DOCUMENT_LINES", false),
            spec("MBR-POINTS-FEFO", "Member", "server/ruoyi-modules/jshpos-member/src/main/resources/mapper/member/PointsPersistenceMapper.xml",
                "listFefoAvailableLots", params("tenantId", TENANT_A, "memberId", id(14_000_000_000L),
                    "occurredAt", BUSINESS_INSTANT), List.of("mbr_points_lot"), "LOCKING", false)
        );
    }

    private static QuerySpec spec(String id, String owner, String path, String statement,
                                  LinkedHashMap<String, Object> parameters, List<String> tables,
                                  String budgetClass, boolean approvedMillionTrend) {
        return new QuerySpec(id, owner, path, statement, parameters, tables, budgetClass, approvedMillionTrend);
    }

    private static LinkedHashMap<String, Object> params(Object... values) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    static String id(long value) {
        return String.format("%026d", value);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String stripComments(String value) {
        return value.replaceAll("(?s)<!--.*?-->", "");
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String unescapeXml(String value) {
        return value.replace("&gt;", ">").replace("&lt;", "<")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&apos;", "'");
    }
}
