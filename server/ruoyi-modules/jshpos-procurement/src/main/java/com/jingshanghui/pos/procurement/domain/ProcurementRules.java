package com.jingshanghui.pos.procurement.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.regex.Pattern;

/** 采购状态机、数量、金额、超收和原收货退货不变量。 */
public final class ProcurementRules {

    public static final int QUANTITY_SCALE = 6;
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Set<String> SUPPLIER_STATES = Set.of("ACTIVE", "SUSPENDED", "BLOCKED");

    private ProcurementRules() {
    }

    public static String ulid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new ServiceException("PUR-INPUT-001: " + field + " 不是规范 ULID", 409);
        }
        return value;
    }

    public static String text(String value, int max, String code) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new ServiceException(code + ": 文本字段非法", 409);
        }
        return value.trim();
    }

    public static BigDecimal quantity(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0 || value.scale() > QUANTITY_SCALE
            || value.precision() - value.scale() > 13) {
            throw new ServiceException("PUR-QTY-001: " + field + " 数量非法", 409);
        }
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    /** 按冻结的整数分子/分母精确换算基础单位，禁止浮点和隐式舍入。 */
    public static BigDecimal toBaseQuantity(BigDecimal quantity, long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) {
            throw new ServiceException("PUR-UNIT-001: 单位换算分子分母非法", 409);
        }
        try {
            return quantity(quantity, "purchaseQuantity").multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException("PUR-UNIT-002: 单位换算无法精确到六位小数", 409);
        }
    }

    public static long money(long value) {
        if (value < 0) throw new ServiceException("PUR-MONEY-001: 最小货币单位金额不可为负", 409);
        return value;
    }

    public static int taxRate(int basisPoints) {
        if (basisPoints < 0 || basisPoints > 10000) {
            throw new ServiceException("PUR-TAX-001: 税率基点非法", 409);
        }
        return basisPoints;
    }

    /** 超收容差最大 10%；任何正容差还需应用层验证租户管理员。 */
    public static int tolerance(int basisPoints) {
        if (basisPoints < 0 || basisPoints > 1000) {
            throw new ServiceException("PUR-RECEIPT-001: 超收容差必须在0至1000基点", 409);
        }
        return basisPoints;
    }

    public static void requireReceivable(String orderState) {
        if (!Set.of("APPROVED", "PARTIALLY_RECEIVED").contains(orderState)) {
            throw new ServiceException("PUR-STATE-001: 当前采购单不可收货", 409);
        }
    }

    public static void requireDraft(String orderState) {
        if (!"DRAFT".equals(orderState)) throw new ServiceException("PUR-STATE-002: 采购单不是草稿", 409);
    }

    public static void requireSubmitted(String orderState) {
        if (!"SUBMITTED".equals(orderState)) {
            throw new ServiceException("PUR-STATE-003: 采购单尚未提交", 409);
        }
    }

    public static void requireClosable(String orderState) {
        if (!Set.of("APPROVED", "PARTIALLY_RECEIVED", "RECEIVED").contains(orderState)) {
            throw new ServiceException("PUR-STATE-004: 当前采购单不可关闭", 409);
        }
    }

    public static String supplierState(String state) {
        if (!SUPPLIER_STATES.contains(state)) throw new ServiceException("PUR-SUP-001: 供应商状态非法", 409);
        return state;
    }

    /** 本次收货加累计收货不得超过采购数量与冻结容差。 */
    public static void requireWithinReceiptLimit(BigDecimal ordered, BigDecimal alreadyReceived,
                                                 BigDecimal current, int toleranceBps) {
        BigDecimal max = ordered.multiply(BigDecimal.valueOf(10000L + tolerance(toleranceBps)))
            .divide(BigDecimal.valueOf(10000), QUANTITY_SCALE, RoundingMode.DOWN);
        if (alreadyReceived.add(quantity(current, "receiptQuantity")).compareTo(max) > 0) {
            throw new ServiceException("PUR-RECEIPT-002: 累计收货超过采购数量与容差", 409);
        }
    }

    /** 累计原收货退货不得超过该原收货行已确认数量。 */
    public static void requireWithinReturnLimit(BigDecimal received, BigDecimal alreadyReturned,
                                                BigDecimal current) {
        if (alreadyReturned.add(quantity(current, "returnQuantity")).compareTo(received) > 0) {
            throw new ServiceException("PUR-RETURN-001: 累计退货超过原收货数量", 409);
        }
    }
}
