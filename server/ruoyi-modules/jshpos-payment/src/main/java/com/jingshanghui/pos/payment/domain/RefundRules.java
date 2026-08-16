package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.RefundStatus;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 原单退款金额、行数量占额和状态迁移规则。 */
public final class RefundRules {

    private static final Set<RefundStatus> RESERVED = EnumSet.of(
        RefundStatus.PROCESSING, RefundStatus.UNKNOWN, RefundStatus.SUCCEEDED);

    private RefundRules() {
    }

    public static void validateLines(List<RefundLine> lines, long claimedAmount) {
        PaymentRules.requirePositiveAmount(claimedAmount, "refundAmountMinor");
        if (lines == null || lines.isEmpty() || lines.size() > 200) {
            throw invalid("REF-LINE-001", "退款必须包含 1..200 行");
        }
        long total = 0;
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (RefundLine line : lines) {
            PaymentRules.requireUlid(line.orderLineId(), "orderLineId");
            if (!ids.add(line.orderLineId()) || line.quantity() == null || line.quantity().signum() <= 0
                || line.quantity().scale() > 6 || line.amountMinor() < 0) {
                throw invalid("REF-LINE-002", "退款行重复、数量或金额非法");
            }
            try {
                total = Math.addExact(total, line.amountMinor());
            } catch (ArithmeticException exception) {
                throw invalid("REF-AMOUNT-001", "退款行金额溢出");
            }
        }
        if (total != claimedAmount) {
            throw invalid("REF-AMOUNT-002", "退款行金额合计不等于退款总额");
        }
    }

    public static void requireAmountAvailable(long paid, long reserved, long requested) {
        PaymentRules.requirePositiveAmount(requested, "refundAmountMinor");
        if (reserved < 0 || reserved > paid || requested > paid - reserved) {
            throw invalid("REF-LIMIT-001", "累计退款金额超过原支付可退额度");
        }
    }

    public static void requireQuantityAvailable(BigDecimal original, BigDecimal reserved, BigDecimal requested) {
        if (original == null || reserved == null || requested == null || original.signum() <= 0
            || reserved.signum() < 0 || requested.signum() <= 0
            || reserved.add(requested).compareTo(original) > 0) {
            throw invalid("REF-LIMIT-002", "累计退款数量超过原订单行数量");
        }
    }

    public static boolean reserves(RefundStatus status) {
        return RESERVED.contains(status);
    }

    /** 成功退款事实不可回退；UNKNOWN 不被较弱的处理中观察覆盖。 */
    public static RefundStatus merge(RefundStatus current, RefundStatus observed) {
        if (current == RefundStatus.SUCCEEDED || observed == RefundStatus.SUCCEEDED) return RefundStatus.SUCCEEDED;
        if (current == RefundStatus.FAILED || current == RefundStatus.CANCELLED || current == RefundStatus.CLOSED) {
            return current;
        }
        if (current == RefundStatus.UNKNOWN && observed == RefundStatus.PROCESSING) return RefundStatus.UNKNOWN;
        return observed;
    }

    public static void requireTransition(RefundStatus from, RefundStatus to) {
        boolean legal = switch (from) {
            case CREATED -> to == RefundStatus.PENDING_APPROVAL || to == RefundStatus.PROCESSING
                || to == RefundStatus.CANCELLED;
            case PENDING_APPROVAL -> to == RefundStatus.PROCESSING || to == RefundStatus.CANCELLED;
            case PROCESSING -> to == RefundStatus.UNKNOWN || to == RefundStatus.SUCCEEDED
                || to == RefundStatus.FAILED || to == RefundStatus.CANCELLED || to == RefundStatus.CLOSED;
            case UNKNOWN -> to == RefundStatus.SUCCEEDED || to == RefundStatus.FAILED
                || to == RefundStatus.CANCELLED || to == RefundStatus.CLOSED;
            case SUCCEEDED, FAILED, CANCELLED, CLOSED -> false;
        };
        if (!legal) throw invalid("REF-STATE-001", "非法退款状态迁移");
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }

    /** 单行退款占额，数量使用精确十进制，金额使用最小货币单位整数。 */
    public record RefundLine(String orderLineId, BigDecimal quantity, long amountMinor) {
    }
}
