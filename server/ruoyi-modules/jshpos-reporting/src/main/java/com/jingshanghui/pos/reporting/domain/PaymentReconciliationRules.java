package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDate;
import java.util.Set;

/** Provider 无关支付退款对账的确定性分类与输入不变量。 */
public final class PaymentReconciliationRules {
    private static final Set<String> TYPES = Set.of("PAYMENT", "REFUND");
    private static final Set<String> STATUSES = Set.of("SUCCEEDED", "FAILED", "UNKNOWN");
    private static final Set<String> DIFFERENCES = Set.of("MATCHED", "MISSING_BILL", "MISSING_INTERNAL",
        "AMOUNT_MISMATCH", "CURRENCY_MISMATCH", "STATUS_MISMATCH", "BUSINESS_DATE_MISMATCH");
    private static final Set<String> HANDLING = Set.of("MATCHED", "OPEN", "ASSIGNED", "RESOLVED", "IGNORED");

    private PaymentReconciliationRules() {
    }

    public static String requireFactType(String value) {
        if (!TYPES.contains(value)) throw bad("RPT-G5D-201", "事实类型必须为 PAYMENT 或 REFUND");
        return value;
    }

    public static String requireLifecycleStatus(String value) {
        if (!STATUSES.contains(value)) throw bad("RPT-G5D-202", "支付退款状态不在 Provider 无关白名单");
        return value;
    }

    public static long requireAmount(long value) {
        if (value < 0) throw bad("RPT-G5D-203", "对账金额不能为负数");
        return value;
    }

    /** 固定优先级：币种、金额、状态、业务日，确保乱序输入得到相同结论。 */
    public static String classify(boolean hasInternal, boolean hasBill, String internalCurrency,
                                  String billCurrency, Long internalAmount, Long billAmount,
                                  String internalStatus, String billStatus,
                                  LocalDate internalDate, LocalDate billDate) {
        if (!hasInternal) return "MISSING_INTERNAL";
        if (!hasBill) return "MISSING_BILL";
        if (!internalCurrency.equals(billCurrency)) return "CURRENCY_MISMATCH";
        if (!internalAmount.equals(billAmount)) return "AMOUNT_MISMATCH";
        if (!internalStatus.equals(billStatus)) return "STATUS_MISMATCH";
        if (!internalDate.equals(billDate)) return "BUSINESS_DATE_MISMATCH";
        return "MATCHED";
    }

    public static String requireDifference(String value) {
        if (!DIFFERENCES.contains(value)) throw bad("RPT-G5D-204", "差异分类非法");
        return value;
    }

    public static String requireHandling(String value) {
        if (!HANDLING.contains(value)) throw bad("RPT-G5D-206", "处理状态非法");
        return value;
    }

    public static String transition(String current, String target) {
        if (!HANDLING.contains(current) || !HANDLING.contains(target) || "MATCHED".equals(current)
            || "OPEN".equals(target) || "MATCHED".equals(target)) {
            throw conflict("RPT-G5D-205", "对账处理状态迁移非法");
        }
        boolean allowed = ("OPEN".equals(current) && Set.of("ASSIGNED", "RESOLVED", "IGNORED").contains(target))
            || ("ASSIGNED".equals(current) && Set.of("RESOLVED", "IGNORED").contains(target));
        if (!allowed) throw conflict("RPT-G5D-205", "对账处理状态迁移非法");
        return target;
    }

    private static ServiceException bad(String code, String message) {
        return new ServiceException(code + ": " + message, 400);
    }

    private static ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
