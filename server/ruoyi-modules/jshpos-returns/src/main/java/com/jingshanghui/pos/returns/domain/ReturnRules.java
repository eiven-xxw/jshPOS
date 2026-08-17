package com.jingshanghui.pos.returns.domain;

import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.Map;

/** 原单退货数量、金额和 Saga 状态不变量。 */
public final class ReturnRules {
    private ReturnRules() { }

    public static BigDecimal positiveQuantity(BigDecimal value, String field) {
        if (value == null || value.scale() > 6 || value.compareTo(BigDecimal.ZERO) <= 0
            || value.precision() - value.scale() > 13) {
            throw new ServiceException("RET-QTY-001: " + field + " 必须为正且最多六位小数", 409);
        }
        return value.setScale(6, RoundingMode.UNNECESSARY);
    }

    public static void requireQuantityAvailable(BigDecimal original, BigDecimal reserved, BigDecimal requested) {
        BigDecimal normalized = positiveQuantity(requested, "requestedQuantity");
        if (reserved == null || original == null || reserved.signum() < 0
            || reserved.add(normalized).compareTo(original) > 0) {
            throw new ServiceException("RET-QTY-002: 累计退货数量超过原成交数量", 409);
        }
    }

    public static void requireAllocation(long gross, long discount, long refundable) {
        if (gross < 0 || discount < 0 || refundable < 0 || gross - discount != refundable) {
            throw new ServiceException("RET-AMOUNT-001: 原快照退款金额不守恒", 409);
        }
    }

    public static void requireTransition(Status before, Status after) {
        Map<Status, EnumSet<Status>> allowed = Map.of(
            Status.PENDING_APPROVAL, EnumSet.of(Status.PROMOTION_PENDING),
            Status.PROMOTION_PENDING, EnumSet.of(Status.CASH_REFUND_PENDING, Status.PAYMENT_PENDING,
                Status.INVENTORY_PENDING, Status.FAILED),
            Status.CASH_REFUND_PENDING, EnumSet.of(Status.INVENTORY_PENDING, Status.FAILED),
            Status.PAYMENT_PENDING, EnumSet.of(Status.PAYMENT_UNKNOWN, Status.INVENTORY_PENDING, Status.FAILED),
            Status.PAYMENT_UNKNOWN, EnumSet.of(Status.PAYMENT_UNKNOWN, Status.INVENTORY_PENDING, Status.FAILED),
            Status.INVENTORY_PENDING, EnumSet.of(Status.COMPLETED, Status.FAILED)
        );
        if (!allowed.getOrDefault(before, EnumSet.noneOf(Status.class)).contains(after)) {
            throw new ServiceException("RET-STATE-001: 非法状态迁移 " + before + " -> " + after, 409);
        }
    }

    public static void requireHash(String value, String field) {
        if (value == null || !value.matches("^[a-f0-9]{64}$")) {
            throw new ServiceException("RET-HASH-001: " + field + " 必须为SHA-256十六进制摘要", 409);
        }
    }

    public static void requireUlid(String value, String field) {
        if (value == null || !value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("RET-ID-001: " + field + " 必须为规范ULID", 409);
        }
    }
}
