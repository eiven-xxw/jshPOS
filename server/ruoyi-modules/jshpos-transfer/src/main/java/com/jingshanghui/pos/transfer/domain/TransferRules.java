package com.jingshanghui.pos.transfer.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

import static com.jingshanghui.pos.transfer.domain.TransferStates.Status;

/** 调拨状态、精度、差异和在途恒等式；不依赖数据库。 */
public final class TransferRules {
    public static final int QUANTITY_SCALE = 6;
    private static final Set<Status> RECEIVABLE = Set.of(Status.IN_TRANSIT, Status.PARTIALLY_RECEIVED);

    private TransferRules() { }

    public static void ulid(String value, String field) {
        if (value == null || !value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("TRF-ID-001: " + field + " 必须为规范 ULID", 409);
        }
    }

    public static String text(String value, int max, String code) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new ServiceException(code + ": 文本为空或超长", 409);
        }
        return normalized;
    }

    public static BigDecimal quantity(BigDecimal value, String field) {
        if (value == null || value.scale() > QUANTITY_SCALE || value.compareTo(BigDecimal.ZERO) <= 0
            || value.precision() - value.scale() > 13) {
            throw new ServiceException("TRF-QTY-001: " + field + " 必须为正且最多六位小数", 409);
        }
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    public static void distinctWarehouses(String source, String destination) {
        ulid(source, "sourceWarehouseId");
        ulid(destination, "destinationWarehouseId");
        if (source.equals(destination)) throw new ServiceException("TRF-ROUTE-001: 来源仓与目的仓必须不同", 409);
    }

    public static Status transition(Status current, Status expected, Status next) {
        if (current != expected) throw new ServiceException("TRF-STATE-001: 调拨状态不允许当前操作", 409);
        return next;
    }

    public static void receivable(Status current) {
        if (!RECEIVABLE.contains(current)) throw new ServiceException("TRF-STATE-002: 当前状态不可收货", 409);
    }

    public static void withinRemaining(BigDecimal dispatched, BigDecimal received, BigDecimal difference,
                                       BigDecimal incoming) {
        BigDecimal remaining = dispatched.subtract(received).subtract(difference);
        if (quantity(incoming, "receivedQuantity").compareTo(remaining) > 0) {
            throw new ServiceException("TRF-QTY-002: 收货数量超过在途余额", 409);
        }
    }

    public static BigDecimal openTransit(BigDecimal dispatched, BigDecimal received, BigDecimal difference) {
        BigDecimal open = dispatched.subtract(received).subtract(difference)
            .setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        if (open.signum() < 0) throw new ServiceException("TRF-TRANSIT-001: 在途恒等式被破坏", 409);
        return open;
    }

    public static TransferStates.DifferenceReason differenceReason(String value) {
        try {
            return TransferStates.DifferenceReason.valueOf(text(value, 32, "TRF-DIFF-001"));
        } catch (IllegalArgumentException exception) {
            throw new ServiceException("TRF-DIFF-001: 非法差异原因", 409);
        }
    }
}
