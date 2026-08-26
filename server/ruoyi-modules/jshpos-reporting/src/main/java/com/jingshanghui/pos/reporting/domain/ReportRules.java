package com.jingshanghui.pos.reporting.domain;

import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 报表领域规则。这里只校验来源 Owner 已冻结的口径和安全边界，不重新计算业务事实。
 */
public final class ReportRules {
    public static final String ENGINE_VERSION = "g5d-v1";
    public static final int MAX_QUERY_DAYS = 31;
    public static final int APPROVAL_ROW_THRESHOLD = 10_000;
    public static final int MAX_EXPORT_ROWS = 100_000;
    public static final int MAX_SALES_PAGE_ROWS = 500;
    public static final int DOWNLOAD_TTL_MINUTES = 10;
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9._:-]{1,96}$");
    private static final Set<String> SALES_OWNERS = Set.of("ORDER", "SHIFT", "PROMOTION");
    private static final Set<String> INVENTORY_OWNERS = Set.of("INVENTORY", "COSTING");
    private static final Set<String> SALES_FIELDS = Set.of("businessDate", "storeId", "terminalId", "cashierId",
        "currency", "orderCount", "cancelledOrderCount", "returnCount", "grossMinor", "discountMinor",
        "surchargeMinor", "receivableMinor", "refundMinor", "cashReceivedMinor", "cashRefundedMinor",
        "shiftDifferenceMinor", "promotionSnapshotCount", "projectionStatus");
    private static final Set<String> INVENTORY_FIELDS = Set.of("businessDate", "storeId", "warehouseId", "skuId",
        "currency", "onHandDelta", "availableDelta", "reservedDelta", "ledgerQuantityDelta",
        "purchaseQuantityDelta", "stocktakeQuantityDelta", "transferQuantityDelta", "inventoryValueDeltaMinor",
        "cogsDeltaMinor", "purchaseCostDeltaMinor", "stocktakeCostDeltaMinor", "transferCostDeltaMinor",
        "projectionStatus");
    private static final Set<String> PAYMENT_RECONCILIATION_FIELDS = Set.of("reconciliationId", "factType",
        "businessDate", "storeId", "terminalId", "currency", "internalAmountMinor", "billAmountMinor",
        "internalStatus", "billStatus", "internalBusinessDate", "billBusinessDate", "differenceType",
        "handlingState", "handlerId");

    private ReportRules() {
    }

    public static String requireUlid(String value, String errorCode) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw bad(errorCode, "标识必须为 ULID");
        }
        return value;
    }

    public static String requireSha256(String value, String errorCode) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw bad(errorCode, "摘要必须为小写 SHA-256");
        }
        return value;
    }

    public static String requireCode(String value, String errorCode) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw bad(errorCode, "代码格式非法");
        }
        return value;
    }

    public static String requireCurrency(String currency) {
        if (!"CNY".equals(currency)) {
            throw bad("RPT-G5D-004", "商业 V1 只接受 CNY");
        }
        return currency;
    }

    public static void requireOwnerFamily(String owner, String family) {
        boolean allowed = "SALES".equals(family) ? SALES_OWNERS.contains(owner)
            : "INVENTORY_COST".equals(family) && INVENTORY_OWNERS.contains(owner);
        if (!allowed) {
            throw bad("RPT-G5D-005", "来源 Owner 与指标族不匹配");
        }
    }

    public static void requireSalesConservation(long grossMinor, long discountMinor,
                                                 long surchargeMinor, long receivableMinor) {
        long expected;
        try {
            expected = Math.addExact(Math.subtractExact(grossMinor, discountMinor), surchargeMinor);
        } catch (ArithmeticException exception) {
            throw bad("RPT-G5D-006", "销售金额溢出");
        }
        if (expected != receivableMinor) {
            throw bad("RPT-G5D-007", "来源销售金额不守恒");
        }
    }

    public static BigDecimal exactDecimal(BigDecimal value, String errorCode) {
        if (value == null || value.precision() > 25 || value.scale() > 6) {
            throw bad(errorCode, "数量或成本超过 DECIMAL(25,6)");
        }
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    public static void requireDateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_QUERY_DAYS) {
            throw bad("RPT-G5D-009", "报表日期范围必须为 1 至 31 个业务日");
        }
    }

    public static int requireSalesPageLimit(int limit) {
        if (limit < 1 || limit > MAX_SALES_PAGE_ROWS) {
            throw bad("RPT-R2R2-001", "销售分页行数必须为 1 至 500");
        }
        return limit;
    }

    public static int requireInventoryCostPageLimit(int limit) {
        if (limit < 1 || limit > ReportingBatchReadPort.MAX_INTERACTIVE_ROWS) {
            throw bad("RPT-R2R2-021", "库存成本分页行数必须为 1 至 500");
        }
        return limit;
    }

    public static Set<String> requireExportFields(String reportType, Set<String> fields) {
        Set<String> allowed = switch (reportType) {
            case "SALES_DAILY" -> SALES_FIELDS;
            case "INVENTORY_COST_DAILY" -> INVENTORY_FIELDS;
            case "PAYMENT_RECONCILIATION" -> PAYMENT_RECONCILIATION_FIELDS;
            default -> throw bad("RPT-G5D-010", "未知报表类型");
        };
        if (fields == null || fields.isEmpty() || !allowed.containsAll(fields)) {
            throw bad("RPT-G5D-011", "导出字段不在服务端白名单");
        }
        return Set.copyOf(fields);
    }

    public static boolean requiresApproval(String reportType, int estimatedRows, Set<String> fields) {
        return estimatedRows > APPROVAL_ROW_THRESHOLD || "INVENTORY_COST_DAILY".equals(reportType)
            || "PAYMENT_RECONCILIATION".equals(reportType)
            || fields.stream().anyMatch(field -> field.toLowerCase().contains("cost"));
    }

    public static String safeCsvText(Object value) {
        String text = value == null ? "" : String.valueOf(value).replace("\r\n", " ").replace('\r', ' ')
            .replace('\n', ' ');
        if (!text.isEmpty() && "=+-@\t".indexOf(text.charAt(0)) >= 0) {
            return "'" + text;
        }
        return text;
    }

    private static ServiceException bad(String code, String message) {
        return new ServiceException(code + ": " + message, 400);
    }
}
