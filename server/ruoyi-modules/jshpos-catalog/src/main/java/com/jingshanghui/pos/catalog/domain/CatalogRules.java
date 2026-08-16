package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 商品、条码、数量和状态的集中不变量。 */
public final class CatalogRules {

    public static final Set<String> PRODUCT_TYPES = Set.of("STANDARD", "WEIGHT", "COUNT");
    public static final Set<String> PRODUCT_STATES = Set.of("DRAFT", "ACTIVE", "INACTIVE");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,63}$");
    private static final Pattern BARCODE = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999999999999.999999");

    private CatalogRules() {
    }

    public static String requireCode(String value, String errorCode) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw bad(errorCode, "编码格式无效");
        }
        return normalized;
    }

    public static String requireName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw bad("CAT-PRD-002", "名称长度必须为 1..200");
        }
        return normalized;
    }

    public static String requireBarcode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!BARCODE.matcher(normalized).matches()) {
            throw bad("CAT-PRD-003", "条码格式无效");
        }
        return normalized;
    }

    public static String requireProductType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!PRODUCT_TYPES.contains(normalized)) {
            throw bad("CAT-PRD-004", "商品类型无效");
        }
        return normalized;
    }

    public static String transitionState(String current, String requested) {
        if (!PRODUCT_STATES.contains(current) || !PRODUCT_STATES.contains(requested)) {
            throw bad("CAT-PRD-005", "商品状态无效");
        }
        if (current.equals(requested)) {
            return current;
        }
        boolean allowed = (current.equals("DRAFT") && requested.equals("ACTIVE"))
            || (current.equals("ACTIVE") && requested.equals("INACTIVE"))
            || (current.equals("INACTIVE") && requested.equals("ACTIVE"));
        if (!allowed) {
            throw new ServiceException("CAT-PRD-006: 非法商品状态迁移", 409);
        }
        return requested;
    }

    public static long requireMinorAmount(Long amount) {
        if (amount == null || amount < 0) {
            throw bad("CAT-PRC-001", "金额必须是非负最小货币单位整数");
        }
        return amount;
    }

    public static BigDecimal requireQuantity(String value) {
        try {
            BigDecimal quantity = new BigDecimal(value).stripTrailingZeros();
            if (quantity.signum() <= 0 || quantity.scale() > 6 || quantity.compareTo(MAX_QUANTITY) > 0) {
                throw bad("CAT-PRD-007", "数量必须为正且最多 6 位小数");
            }
            return quantity;
        } catch (NumberFormatException exception) {
            throw bad("CAT-PRD-007", "数量必须是十进制定点数");
        }
    }

    public static UnitRatio requireRatio(Long numerator, Long denominator) {
        if (numerator == null || denominator == null || numerator <= 0 || denominator <= 0) {
            throw bad("CAT-PRD-008", "单位换算分子分母必须为正整数");
        }
        BigInteger gcd = BigInteger.valueOf(numerator).gcd(BigInteger.valueOf(denominator));
        return new UnitRatio(
            BigInteger.valueOf(numerator).divide(gcd).longValueExact(),
            BigInteger.valueOf(denominator).divide(gcd).longValueExact()
        );
    }

    private static ServiceException bad(String code, String message) {
        return new ServiceException(code + ": " + message, 400);
    }

    public record UnitRatio(long numerator, long denominator) {
    }
}
