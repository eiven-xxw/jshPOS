package com.jingshanghui.pos.costing.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/**
 * 不依赖数据库的仓级移动加权成本公式。
 *
 * <p>金额以最小货币单位计量并保留六位小数；数量、成本和舍入全程禁止浮点数。</p>
 */
public final class CostingRules {

    public static final int SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final String CURRENCY = "CNY";
    private static final BigDecimal ZERO = new BigDecimal("0.000000");
    private static final Set<String> SUPPORTED = Set.of("PURCHASE_RECEIPT_IN", "PURCHASE_RETURN_OUT",
        "SALE_OUT", "SALE_RETURN_IN", "STOCKTAKE_GAIN", "STOCKTAKE_LOSS",
        "TRANSFER_OUT", "TRANSFER_IN", "REVERSAL");

    private CostingRules() {
    }

    /** 计算一条成本流水；返回值可直接用于同事务写流水与余额投影。 */
    public static CostTransition calculate(ValuationInput input) {
        if (input == null || input.before() == null || !SUPPORTED.contains(input.movementType())) {
            throw new ServiceException("CST-MOVEMENT-001: 成本移动类型未准入", 409);
        }
        BigDecimal beforeQuantity = quantity(input.before().quantity(), "quantityBefore");
        BigDecimal beforeAmount = signedMoney(input.before().amountMinor(), "costAmountBefore");
        BigDecimal beforeAverage = nonNegativeMoney(input.before().averageUnitCostMinor(), "averageUnitCostBefore");
        BigDecimal beforeLast = nonNegativeMoney(input.before().lastUnitCostMinor(), "lastUnitCostBefore");
        BigDecimal delta = quantity(input.quantityDelta(), "quantityDelta");
        if (delta.compareTo(ZERO) == 0) {
            throw new ServiceException("CST-QTY-001: 成本数量变化不得为零", 409);
        }
        BigDecimal afterQuantity = beforeQuantity.add(delta).setScale(SCALE, RoundingMode.UNNECESSARY);
        if ("REVERSAL".equals(input.movementType())) {
            return reversal(input, beforeQuantity, beforeAmount, beforeAverage, beforeLast, delta, afterQuantity);
        }
        return delta.signum() > 0
            ? inbound(input, beforeQuantity, beforeAmount, beforeAverage, beforeLast, delta, afterQuantity)
            : outbound(input, beforeQuantity, beforeAmount, beforeAverage, beforeLast, delta, afterQuantity);
    }

    private static CostTransition inbound(ValuationInput input, BigDecimal beforeQuantity,
                                          BigDecimal beforeAmount, BigDecimal beforeAverage,
                                          BigDecimal beforeLast, BigDecimal delta,
                                          BigDecimal afterQuantity) {
        BigDecimal unit;
        String method;
        boolean estimated = input.sourceEstimated();
        BigDecimal variance = ZERO;
        BigDecimal afterAmount;
        if ("PURCHASE_RECEIPT_IN".equals(input.movementType()) || "TRANSFER_IN".equals(input.movementType())) {
            unit = requireUnitCost(input.sourceUnitCostMinor());
            if (beforeQuantity.signum() < 0) {
                BigDecimal deficitUnit = usableUnit(beforeAverage, beforeLast, input.before().costKnown());
                BigDecimal settled = delta.min(beforeQuantity.abs());
                variance = settled.multiply(unit.subtract(deficitUnit)).setScale(SCALE, ROUNDING);
                if (afterQuantity.signum() < 0) {
                    afterAmount = afterQuantity.multiply(deficitUnit).setScale(SCALE, ROUNDING);
                } else if (afterQuantity.signum() == 0) {
                    afterAmount = ZERO;
                } else {
                    afterAmount = afterQuantity.multiply(unit).setScale(SCALE, ROUNDING);
                }
                method = "TRANSFER_IN".equals(input.movementType())
                    ? "TRANSFER_NEGATIVE_SETTLEMENT" : "NEGATIVE_SETTLEMENT";
            } else {
                afterAmount = beforeAmount.add(delta.multiply(unit)).setScale(SCALE, ROUNDING);
                method = "TRANSFER_IN".equals(input.movementType())
                    ? "INHERITED_TRANSFER_COST" : "PURCHASE_FROZEN_PRICE";
            }
        } else if ("SALE_RETURN_IN".equals(input.movementType())) {
            unit = input.sourceUnitCostMinor() == null
                ? usableUnit(beforeAverage, beforeLast, input.before().costKnown())
                : requireUnitCost(input.sourceUnitCostMinor());
            afterAmount = beforeAmount.add(delta.multiply(unit)).setScale(SCALE, ROUNDING);
            method = input.sourceEstimated() ? "CURRENT_AVERAGE_ESTIMATED" : "ORIGINAL_SALE_COST";
        } else if ("STOCKTAKE_GAIN".equals(input.movementType())) {
            unit = usableUnit(beforeAverage, beforeLast, input.before().costKnown());
            afterAmount = beforeAmount.add(delta.multiply(unit)).setScale(SCALE, ROUNDING);
            method = "CURRENT_AVERAGE";
        } else {
            throw new ServiceException("CST-MOVEMENT-002: 正向成本移动类型与数量方向不一致", 409);
        }
        return transition(beforeQuantity, beforeAmount, beforeLast, delta, afterQuantity, afterAmount,
            unit, estimated, variance, method);
    }

    private static CostTransition outbound(ValuationInput input, BigDecimal beforeQuantity,
                                           BigDecimal beforeAmount, BigDecimal beforeAverage,
                                           BigDecimal beforeLast, BigDecimal delta,
                                           BigDecimal afterQuantity) {
        BigDecimal unit;
        String method;
        boolean estimated = input.sourceEstimated();
        if ("PURCHASE_RETURN_OUT".equals(input.movementType())) {
            if (afterQuantity.signum() < 0) {
                throw new ServiceException("CST-RETURN-QTY-INSUFFICIENT: 采购退货不能制造负成本数量", 409);
            }
            unit = requireUnitCost(input.sourceUnitCostMinor());
            method = "ORIGINAL_RECEIPT_COST";
        } else if ("SALE_OUT".equals(input.movementType()) || "STOCKTAKE_LOSS".equals(input.movementType())
            || "TRANSFER_OUT".equals(input.movementType())) {
            unit = usableUnit(beforeAverage, beforeLast, input.before().costKnown());
            estimated = estimated || beforeQuantity.signum() <= 0 || afterQuantity.signum() < 0;
            method = switch (input.movementType()) {
                case "SALE_OUT" -> estimated ? "LAST_COST_ESTIMATED" : "MOVING_AVERAGE";
                case "TRANSFER_OUT" -> estimated ? "TRANSFER_LAST_COST_ESTIMATED" : "TRANSFER_SOURCE_SNAPSHOT";
                default -> "CURRENT_AVERAGE";
            };
        } else {
            throw new ServiceException("CST-MOVEMENT-003: 负向成本移动类型与数量方向不一致", 409);
        }
        BigDecimal rawAfter = beforeAmount.add(delta.multiply(unit)).setScale(SCALE, ROUNDING);
        BigDecimal afterAmount = rawAfter;
        BigDecimal variance = ZERO;
        if (afterQuantity.signum() == 0) {
            afterAmount = ZERO;
            variance = afterAmount.subtract(rawAfter).setScale(SCALE, ROUNDING);
        } else if (afterQuantity.signum() < 0) {
            afterAmount = afterQuantity.multiply(unit).setScale(SCALE, ROUNDING);
            variance = afterAmount.subtract(rawAfter).setScale(SCALE, ROUNDING);
        }
        return transition(beforeQuantity, beforeAmount, beforeLast, delta, afterQuantity, afterAmount,
            unit, estimated, variance, method);
    }

    private static CostTransition reversal(ValuationInput input, BigDecimal beforeQuantity,
                                           BigDecimal beforeAmount, BigDecimal beforeAverage,
                                           BigDecimal beforeLast, BigDecimal delta,
                                           BigDecimal afterQuantity) {
        if (input.forcedAmountDeltaMinor() == null || input.sourceUnitCostMinor() == null) {
            throw new ServiceException("CST-REVERSAL-001: 冲正必须引用原成本流水的精确反向金额", 409);
        }
        BigDecimal amountDelta = signedMoney(input.forcedAmountDeltaMinor(), "forcedAmountDeltaMinor");
        BigDecimal afterAmount = beforeAmount.add(amountDelta).setScale(SCALE, ROUNDING);
        if (afterQuantity.signum() == 0 && afterAmount.compareTo(ZERO) != 0) {
            throw new ServiceException("CST-REVERSAL-002: 冲正后零数量必须同时结清成本金额", 409);
        }
        BigDecimal unit = requireUnitCost(input.sourceUnitCostMinor());
        BigDecimal average = afterQuantity.signum() == 0 ? unit
            : nonNegativeMoney(afterAmount.divide(afterQuantity, SCALE, ROUNDING), "reversalAverage");
        BigDecimal last = average.signum() > 0 ? average : (unit.signum() > 0 ? unit : beforeLast);
        return new CostTransition(beforeQuantity, delta, afterQuantity, beforeAmount, amountDelta,
            afterAmount, unit, average, last, input.sourceEstimated(),
            signedMoney(input.forcedVarianceMinor(), "forcedVarianceMinor"), "REVERSAL");
    }

    private static CostTransition transition(BigDecimal beforeQuantity, BigDecimal beforeAmount,
                                             BigDecimal beforeLast, BigDecimal delta,
                                             BigDecimal afterQuantity, BigDecimal afterAmount,
                                             BigDecimal unit, boolean estimated,
                                             BigDecimal variance, String method) {
        BigDecimal average = afterQuantity.signum() == 0 ? (unit.signum() > 0 ? unit : beforeLast)
            : nonNegativeMoney(afterAmount.divide(afterQuantity, SCALE, ROUNDING), "averageUnitCostAfter");
        BigDecimal last = average.signum() > 0 ? average : (unit.signum() > 0 ? unit : beforeLast);
        return new CostTransition(beforeQuantity, delta, afterQuantity, beforeAmount,
            afterAmount.subtract(beforeAmount).setScale(SCALE, ROUNDING), afterAmount, unit,
            average, last, estimated, variance.setScale(SCALE, ROUNDING), method);
    }

    public static BigDecimal purchaseBaseUnitCost(long purchaseUnitPriceMinor, long numerator, long denominator) {
        if (purchaseUnitPriceMinor < 0 || numerator <= 0 || denominator <= 0) {
            throw new ServiceException("CST-PURCHASE-001: 采购价格或冻结换算非法", 409);
        }
        return BigDecimal.valueOf(purchaseUnitPriceMinor).multiply(BigDecimal.valueOf(denominator))
            .divide(BigDecimal.valueOf(numerator), SCALE, ROUNDING);
    }

    public static void requireUlid(String value, String field) {
        if (value == null || !value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("CST-ID-001: " + field + " 必须为规范 ULID", 409);
        }
    }

    private static BigDecimal usableUnit(BigDecimal average, BigDecimal last, boolean costKnown) {
        BigDecimal unit = average.signum() > 0 ? average : last;
        if (unit.signum() < 0 || (unit.signum() == 0 && !costKnown)) {
            throw new ServiceException("CST-COST-MISSING: 出库、退货或盘盈缺少可审计成本依据", 409);
        }
        return unit;
    }

    private static BigDecimal requireUnitCost(BigDecimal value) {
        BigDecimal result = nonNegativeMoney(value, "sourceUnitCostMinor");
        return result;
    }

    private static BigDecimal quantity(BigDecimal value, String field) {
        if (value == null || value.scale() > SCALE || value.precision() - value.scale() > 13) {
            throw new ServiceException("CST-QTY-002: " + field + " 超出 DECIMAL(19,6)", 409);
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal signedMoney(BigDecimal value, String field) {
        if (value == null) return ZERO;
        if (value.scale() > SCALE || value.precision() - value.scale() > 19) {
            throw new ServiceException("CST-MONEY-001: " + field + " 超出 DECIMAL(25,6)", 409);
        }
        return value.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal nonNegativeMoney(BigDecimal value, String field) {
        BigDecimal result = signedMoney(value, field);
        if (result.signum() < 0) {
            throw new ServiceException("CST-MONEY-002: " + field + " 不得为负", 409);
        }
        return result;
    }

    /** 成本计算前的可重建投影快照。 */
    public record BalanceSnapshot(BigDecimal quantity, BigDecimal amountMinor,
                                  BigDecimal averageUnitCostMinor, BigDecimal lastUnitCostMinor,
                                  boolean costKnown) {
    }

    /** 单次估值输入；forced 字段仅用于引用权威反向库存事实的冲正。 */
    public record ValuationInput(String movementType, BigDecimal quantityDelta,
                                 BigDecimal sourceUnitCostMinor, boolean sourceEstimated,
                                 BigDecimal forcedAmountDeltaMinor, BigDecimal forcedVarianceMinor,
                                 BalanceSnapshot before) {
    }

    /** 一条不可变成本流水的完整前后快照与差异。 */
    public record CostTransition(BigDecimal quantityBefore, BigDecimal quantityDelta,
                                 BigDecimal quantityAfter, BigDecimal amountBeforeMinor,
                                 BigDecimal amountDeltaMinor, BigDecimal amountAfterMinor,
                                 BigDecimal unitCostMinor, BigDecimal averageUnitCostAfterMinor,
                                 BigDecimal lastUnitCostAfterMinor, boolean costEstimated,
                                 BigDecimal varianceAmountMinor, String valuationMethod) {
    }
}
