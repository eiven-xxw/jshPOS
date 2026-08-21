package com.jingshanghui.pos.returns.domain;

import com.jingshanghui.pos.returns.domain.ExchangeStates.Status;
import org.dromara.common.core.exception.ServiceException;

import java.util.EnumSet;
import java.util.Map;

/** 换货身份、金额和 Saga 状态不变量；不包含退货、销售、促销或库存算法。 */
public final class ExchangeRules {
    private ExchangeRules() { }

    private static final Map<Status, EnumSet<Status>> TRANSITIONS = Map.ofEntries(
        Map.entry(Status.DRAFT, EnumSet.of(Status.APPROVED, Status.FAILED)),
        Map.entry(Status.APPROVED, EnumSet.of(Status.RETURN_PENDING, Status.FAILED)),
        Map.entry(Status.RETURN_PENDING, EnumSet.of(Status.RETURN_UNKNOWN, Status.RETURN_COMPLETED,
            Status.FAILED, Status.MANUAL_RECOVERY_REQUIRED)),
        Map.entry(Status.RETURN_UNKNOWN, EnumSet.of(Status.RETURN_UNKNOWN, Status.RETURN_COMPLETED,
            Status.MANUAL_RECOVERY_REQUIRED)),
        Map.entry(Status.RETURN_COMPLETED, EnumSet.of(Status.SALE_PENDING,
            Status.MANUAL_RECOVERY_REQUIRED)),
        Map.entry(Status.SALE_PENDING, EnumSet.of(Status.SALE_UNKNOWN, Status.COMPLETED,
            Status.MANUAL_RECOVERY_REQUIRED)),
        Map.entry(Status.SALE_UNKNOWN, EnumSet.of(Status.SALE_UNKNOWN, Status.COMPLETED,
            Status.MANUAL_RECOVERY_REQUIRED)),
        Map.entry(Status.FAILED, EnumSet.of(Status.CLOSED)),
        Map.entry(Status.MANUAL_RECOVERY_REQUIRED, EnumSet.of(Status.RETURN_PENDING,
            Status.SALE_PENDING, Status.CLOSED)),
        Map.entry(Status.COMPLETED, EnumSet.of(Status.CLOSED))
    );

    /** 校验具名状态迁移，防止 UI 或到达顺序直接决定业务结果。 */
    public static void requireTransition(Status before, Status after) {
        if (!TRANSITIONS.getOrDefault(before, EnumSet.noneOf(Status.class)).contains(after)) {
            throw new ServiceException("EXG-STATE-001: 非法换货状态迁移 " + before + " -> " + after, 409);
        }
    }

    /** 换货金额使用最小货币单位整数；新销售必须具有正应收。 */
    public static void requireExpectedAmounts(long refundMinor, long saleMinor) {
        if (refundMinor <= 0 || saleMinor <= 0) {
            throw new ServiceException("EXG-AMOUNT-001: 预期退款与新销售应收必须为正数分", 409);
        }
    }

    /** Owner 完成后必须与 DRAFT 冻结金额一致，禁止由换货模块做净额覆盖。 */
    public static void requireObservedAmount(long expected, long observed, String owner) {
        if (expected != observed) {
            throw new ServiceException("EXG-AMOUNT-002: " + owner + "权威金额与冻结值不一致", 409);
        }
    }

    /** 换货只支持同门店且新旧订单不能复用同一业务身份。 */
    public static void requireDistinctOrders(String originalOrderId, String newOrderId) {
        requireUlid(originalOrderId, "originalOrderId");
        requireUlid(newOrderId, "newOrderId");
        if (originalOrderId.equals(newOrderId)) {
            throw new ServiceException("EXG-ORDER-001: 新销售不得复用原订单身份", 409);
        }
    }

    /** 所有离线可创建业务身份必须为规范 ULID。 */
    public static void requireUlid(String value, String field) {
        if (value == null || !value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("EXG-ID-001: " + field + " 必须为规范ULID", 409);
        }
    }

    /** 摘要仅接受小写十六进制 SHA-256，禁止弱摘要和隐式规范化。 */
    public static void requireHash(String value, String field) {
        if (value == null || !value.matches("^[a-f0-9]{64}$")) {
            throw new ServiceException("EXG-HASH-001: " + field + " 必须为SHA-256十六进制摘要", 409);
        }
    }
}
