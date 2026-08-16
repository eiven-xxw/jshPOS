package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 盘点状态机和数量不变量。
 *
 * <p>动态盘点在提交时重新锁定账面数；盘点差异只形成调整流水，绝不覆盖余额。</p>
 */
public final class StocktakeRules {

    public static final int SCALE = 6;
    private static final Set<String> MUTABLE_COUNT_STATES = Set.of("COUNTING", "RECOUNT_REQUIRED");

    private StocktakeRules() {
    }

    /** 将盘点数量规范到六位小数，拒绝负数和超精度输入。 */
    public static BigDecimal countQuantity(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.scale() > SCALE
            || value.precision() - value.scale() > 13) {
            throw new ServiceException("INV-STK-001: 盘点数量非法", 409);
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** 盘点阈值是非负六位小数。 */
    public static BigDecimal threshold(BigDecimal value) {
        BigDecimal normalized = countQuantity(value);
        if (normalized.compareTo(new BigDecimal("9999999999999.999999")) > 0) {
            throw new ServiceException("INV-STK-002: 复盘阈值超出范围", 409);
        }
        return normalized;
    }

    public static void requireCountable(String state) {
        if (!MUTABLE_COUNT_STATES.contains(state)) {
            throw new ServiceException("INV-STK-003: 当前盘点状态不可录入数量", 409);
        }
    }

    public static void requireSubmittable(String state) {
        if (!MUTABLE_COUNT_STATES.contains(state)) {
            throw new ServiceException("INV-STK-004: 当前盘点状态不可提交", 409);
        }
    }

    public static void requireReviewable(String state) {
        if (!"PENDING_REVIEW".equals(state)) {
            throw new ServiceException("INV-STK-005: 当前盘点状态不可复核", 409);
        }
    }

    public static void requireApprovable(String state) {
        if (!"REVIEWED".equals(state)) {
            throw new ServiceException("INV-STK-006: 当前盘点状态不可审批入账", 409);
        }
    }

    /** 已冻结账面数与实盘数的差额；正数盘盈，负数盘亏。 */
    public static BigDecimal variance(BigDecimal counted, BigDecimal adjustedBook) {
        return countQuantity(counted).subtract(adjustedBook).setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    public static boolean requiresRecount(BigDecimal variance, BigDecimal threshold, int revision) {
        return variance.abs().compareTo(threshold(threshold)) > 0 && revision < 2;
    }

    /** 创建、复核、审批必须由不同可信用户执行。 */
    public static void requireSegregatedActors(Long creator, Long reviewer, Long approver) {
        if (creator == null || reviewer == null || approver == null
            || creator.equals(reviewer) || creator.equals(approver) || reviewer.equals(approver)) {
            throw new ServiceException("INV-STK-007: 盘点创建、复核和审批必须职责分离", 409);
        }
    }
}
