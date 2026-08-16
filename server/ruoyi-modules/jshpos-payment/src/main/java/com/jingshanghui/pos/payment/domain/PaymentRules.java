package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.AttemptStatus;
import com.jingshanghui.pos.payment.domain.PaymentStates.PaymentStatus;
import org.dromara.common.core.exception.ServiceException;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Provider 无关支付不变量；所有金额均为最小货币单位整数。 */
public final class PaymentRules {

    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern PROVIDER = Pattern.compile("^[A-Z0-9_-]{2,32}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final Set<PaymentStatus> CONFIRMED_FUNDS = EnumSet.of(
        PaymentStatus.SUCCEEDED, PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED);

    private PaymentRules() {
    }

    public static String requireUlid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw invalid("PAY-ID-001", field + " 必须是规范 ULID");
        }
        return value;
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY.matcher(value).matches()) {
            throw invalid("PAY-IDEM-001", "幂等键格式非法");
        }
        return value;
    }

    public static String requireProviderCode(String value) {
        if (value == null || !PROVIDER.matcher(value).matches()) {
            throw invalid("PAY-PROVIDER-001", "Provider code 格式非法");
        }
        return value;
    }

    public static String requireCurrency(String value) {
        if (value == null || !CURRENCY.matcher(value).matches()) {
            throw invalid("PAY-CURRENCY-001", "币种必须是三位大写代码");
        }
        return value;
    }

    public static String requireHash(String value) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw invalid("PAY-HASH-002", "payload hash 必须为小写 SHA-256");
        }
        return value;
    }

    public static long requirePositiveAmount(long value, String field) {
        if (value <= 0 || value > MAX_SAFE_JSON_INTEGER) {
            throw invalid("PAY-AMOUNT-001", field + " 超出支持范围");
        }
        return value;
    }

    /** UNKNOWN、成功资金态和关闭态都禁止创建新的扣款 attempt。 */
    public static void requireNewAttemptAllowed(PaymentStatus current, int attempts) {
        if (attempts >= 8) {
            throw invalid("PAY-ATTEMPT-002", "单支付意图最多允许 8 个显式 attempt");
        }
        if (current != PaymentStatus.CREATED && current != PaymentStatus.FAILED) {
            throw invalid("PAY-ATTEMPT-001", "当前状态禁止创建新的扣款 attempt");
        }
    }

    /**
     * 按资金事实而非到达顺序合并观察；已成功状态不会因失败或超时回退。
     */
    public static PaymentStatus merge(PaymentStatus current, AttemptStatus observed) {
        if (CONFIRMED_FUNDS.contains(current)) {
            return current;
        }
        if (observed == AttemptStatus.SUCCEEDED) {
            return PaymentStatus.SUCCEEDED;
        }
        if (current == PaymentStatus.FAILED || current == PaymentStatus.CANCELLED || current == PaymentStatus.CLOSED) {
            return current;
        }
        return switch (observed) {
            case CREATED, PROCESSING -> current == PaymentStatus.UNKNOWN ? PaymentStatus.UNKNOWN : PaymentStatus.PROCESSING;
            case UNKNOWN -> PaymentStatus.UNKNOWN;
            case FAILED -> PaymentStatus.FAILED;
            case CANCELLED -> PaymentStatus.CANCELLED;
            case CLOSED -> PaymentStatus.CLOSED;
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
        };
    }

    /** 单 attempt 的成功事实同样不可被后到的失败或超时观察回退。 */
    public static AttemptStatus mergeAttempt(AttemptStatus current, AttemptStatus observed) {
        if (current == AttemptStatus.SUCCEEDED || observed == AttemptStatus.SUCCEEDED) {
            return AttemptStatus.SUCCEEDED;
        }
        if (current == AttemptStatus.FAILED || current == AttemptStatus.CANCELLED || current == AttemptStatus.CLOSED) {
            return current;
        }
        if (current == AttemptStatus.UNKNOWN && (observed == AttemptStatus.CREATED || observed == AttemptStatus.PROCESSING)) {
            return AttemptStatus.UNKNOWN;
        }
        return observed;
    }

    public static boolean isConfirmedFunds(PaymentStatus status) {
        return CONFIRMED_FUNDS.contains(status);
    }

    /** 成功退款累计只允许把支付推进到部分退款或全额退款。 */
    public static PaymentStatus afterSuccessfulRefund(long paidAmount, long succeededRefundAmount) {
        requirePositiveAmount(paidAmount, "paidAmountMinor");
        if (succeededRefundAmount < 0 || succeededRefundAmount > paidAmount) {
            throw invalid("REF-AMOUNT-003", "成功退款累计超出原支付");
        }
        if (succeededRefundAmount == 0) return PaymentStatus.SUCCEEDED;
        return succeededRefundAmount == paidAmount ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
