package com.jingshanghui.pos.order.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 与 Flutter golden vector 对齐的纯领域规则；禁止 float/double。 */
public final class OrderRules {

    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{16,128}$");
    private static final Pattern DECIMAL_QUANTITY = Pattern.compile("^(?:0|[1-9][0-9]{0,12})(?:\\.[0-9]{1,6})?$");
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> PRICE_SOURCES = Set.of("TENANT_BASE", "STORE_OVERRIDE");

    private OrderRules() {
    }

    public static String requireUlid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw invalid("ORD-ID-002", field + " must be a canonical ULID");
        }
        return value;
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw invalid("ORD-IDEM-001", "invalid idempotency key");
        }
        return value;
    }

    public static BigDecimal requireQuantity(String value) {
        if (value == null || !DECIMAL_QUANTITY.matcher(value).matches()) {
            throw invalid("ORD-QTY-001", "quantity must use plain decimal notation with at most six decimals");
        }
        try {
            BigDecimal quantity = new BigDecimal(value);
            if (quantity.signum() <= 0) {
                throw invalid("ORD-QTY-001", "quantity must be positive with at most six decimals");
            }
            return quantity.stripTrailingZeros();
        } catch (NumberFormatException exception) {
            throw invalid("ORD-QTY-001", "quantity is not a decimal");
        }
    }

    public static long lineGross(long unitPriceMinor, BigDecimal quantity) {
        requireMoney(unitPriceMinor, "unitPriceMinor");
        BigDecimal result = BigDecimal.valueOf(unitPriceMinor).multiply(quantity)
            .setScale(0, RoundingMode.HALF_UP);
        try {
            return requireMoney(result.longValueExact(), "lineGrossMinor");
        } catch (ArithmeticException exception) {
            throw invalid("ORDER_AMOUNT_CHANGED", "line amount overflow");
        }
    }

    public static long validateOrder(List<LineAmount> lines, long claimedGross, long claimedReceivable) {
        if (lines == null || lines.isEmpty() || lines.size() > 500) {
            throw invalid("ORD-LINE-001", "order requires 1..500 lines");
        }
        long total = 0;
        Set<Integer> numbers = new java.util.HashSet<>();
        for (LineAmount line : lines) {
            requireUlid(line.lineId(), "lineId");
            if (line.skuId() == null || line.skuId() <= 0) {
                throw invalid("ORD-LINE-003", "skuId must be a positive platform id");
            }
            if (line.lineNo() < 1 || line.lineNo() > 500 || !numbers.add(line.lineNo())) {
                throw invalid("ORD-LINE-002", "line numbers must be unique within 1..500");
            }
            BigDecimal quantity = requireQuantity(line.quantity());
            if (!PRICE_SOURCES.contains(line.priceSource())) {
                throw invalid("ORD-PRICE-001", "invalid price source");
            }
            long expected = lineGross(line.unitPriceMinor(), quantity);
            if (line.grossAmountMinor() != expected || line.payableAmountMinor() != expected) {
                throw invalid("ORDER_AMOUNT_CHANGED", "line amount does not match exact calculation");
            }
            try {
                total = Math.addExact(total, expected);
            } catch (ArithmeticException exception) {
                throw invalid("ORDER_AMOUNT_CHANGED", "order amount overflow");
            }
        }
        requireMoney(claimedGross, "grossAmountMinor");
        requireMoney(claimedReceivable, "receivableAmountMinor");
        if (total != claimedGross || total != claimedReceivable) {
            throw invalid("ORDER_AMOUNT_CHANGED", "order totals do not conserve money");
        }
        return total;
    }

    public static CashAmounts cash(long receivableMinor, long tenderedMinor) {
        requireMoney(receivableMinor, "receivableAmountMinor");
        requireMoney(tenderedMinor, "tenderedAmountMinor");
        if (tenderedMinor < receivableMinor) {
            throw invalid("CASH_TENDER_INSUFFICIENT", "cash tender is below receivable amount");
        }
        long change = tenderedMinor - receivableMinor;
        return new CashAmounts(tenderedMinor, change, receivableMinor);
    }

    public static long requireMoney(long value, String field) {
        if (value < 0 || value > MAX_SAFE_JSON_INTEGER) {
            throw invalid("ORD-MONEY-001", field + " outside supported integer range");
        }
        return value;
    }

    public static void requireTransition(String from, String to) {
        boolean legal = switch (from + "->" + to) {
            case "DRAFT->PENDING_PAYMENT", "PENDING_PAYMENT->CONFIRMED", "CONFIRMED->COMPLETED",
                 "DRAFT->CANCELLED" -> true;
            default -> false;
        };
        if (!legal) {
            throw invalid("ORDER_STATE_CONFLICT", "illegal order state transition");
        }
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }

    public record LineAmount(String lineId, int lineNo, Long skuId, String quantity,
                             long unitPriceMinor, long grossAmountMinor, long payableAmountMinor,
                             String priceSource) {
    }

    public record CashAmounts(long tenderedMinor, long changeMinor, long netMinor) {
    }
}
