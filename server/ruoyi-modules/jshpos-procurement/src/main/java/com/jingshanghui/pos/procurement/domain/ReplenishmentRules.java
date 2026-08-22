package com.jingshanghui.pos.procurement.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/** 商业 V1 确定性补货规则；禁止预测、浮点数和隐式舍入。 */
public final class ReplenishmentRules {

    public static final int QUANTITY_SCALE = 6;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(QUANTITY_SCALE);
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private ReplenishmentRules() {
    }

    public static String ulid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new ServiceException("RPL-INPUT-001: " + field + " 必须为 ULID", 400);
        }
        return value;
    }

    public static String text(String value, int maximum, String code) {
        if (value == null || value.isBlank() || value.length() > maximum || value.chars().anyMatch(Character::isISOControl)) {
            throw new ServiceException(code + ": 文本为空、过长或包含控制字符", 400);
        }
        return value.trim();
    }

    public static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.scale() > QUANTITY_SCALE || value.precision() > 19 || value.signum() < 0) {
            throw new ServiceException("RPL-QTY-001: " + field + " 必须为非负六位定点数", 409);
        }
        return value.setScale(QUANTITY_SCALE);
    }

    public static BigDecimal positive(BigDecimal value, String field) {
        BigDecimal normalized = nonNegative(value, field);
        if (normalized.signum() <= 0) {
            throw new ServiceException("RPL-QTY-002: " + field + " 必须大于零", 409);
        }
        return normalized;
    }

    public static void requireRule(BigDecimal minimum, BigDecimal maximum,
                                   BigDecimal minimumOrder, BigDecimal multiple,
                                   long numerator, long denominator) {
        BigDecimal min = nonNegative(minimum, "minimumBaseQuantity");
        BigDecimal max = nonNegative(maximum, "maximumBaseQuantity");
        if (min.compareTo(max) > 0) {
            throw new ServiceException("RPL-RULE-001: 最低库存不得大于最高库存", 409);
        }
        positive(minimumOrder, "minimumOrderQuantity");
        positive(multiple, "orderMultiple");
        if (numerator <= 0 || denominator <= 0) {
            throw new ServiceException("RPL-RULE-002: 单位换算分子分母必须为正整数", 409);
        }
    }

    /**
     * 低于最低库存时补到最高库存，再按采购单位最小量和倍数向上取整。
     */
    public static Optional<Calculation> calculate(BigDecimal available, BigDecimal transit,
                                                   BigDecimal minimum, BigDecimal maximum,
                                                   BigDecimal minimumOrder, BigDecimal multiple,
                                                   long numerator, long denominator,
                                                   boolean includeTransit) {
        requireRule(minimum, maximum, minimumOrder, multiple, numerator, denominator);
        BigDecimal normalizedAvailable = nonNegativeOrSigned(available, "availableQuantity");
        BigDecimal normalizedTransit = nonNegative(transit, "confirmedInTransitQuantity");
        BigDecimal effective = normalizedAvailable.add(includeTransit ? normalizedTransit : ZERO)
            .setScale(QUANTITY_SCALE);
        BigDecimal min = nonNegative(minimum, "minimumBaseQuantity");
        if (effective.compareTo(min) >= 0) return Optional.empty();
        BigDecimal requiredBase = nonNegative(maximum, "maximumBaseQuantity").subtract(effective)
            .max(ZERO).setScale(QUANTITY_SCALE);
        BigDecimal rawPurchase = requiredBase.multiply(BigDecimal.valueOf(denominator))
            .divide(BigDecimal.valueOf(numerator), 12, RoundingMode.CEILING);
        BigDecimal lowerBound = rawPurchase.max(positive(minimumOrder, "minimumOrderQuantity"));
        BigDecimal normalizedMultiple = positive(multiple, "orderMultiple");
        BigDecimal multiplier = lowerBound.divide(normalizedMultiple, 0, RoundingMode.CEILING);
        BigDecimal suggested = multiplier.multiply(normalizedMultiple).setScale(QUANTITY_SCALE);
        return Optional.of(new Calculation(effective, requiredBase, suggested));
    }

    private static BigDecimal nonNegativeOrSigned(BigDecimal value, String field) {
        if (value == null || value.scale() > QUANTITY_SCALE || value.precision() > 19) {
            throw new ServiceException("RPL-QTY-003: " + field + " 必须为六位定点数", 409);
        }
        return value.setScale(QUANTITY_SCALE);
    }

    public record Calculation(BigDecimal effectiveQuantity, BigDecimal requiredBaseQuantity,
                              BigDecimal suggestedPurchaseQuantity) {
    }
}
