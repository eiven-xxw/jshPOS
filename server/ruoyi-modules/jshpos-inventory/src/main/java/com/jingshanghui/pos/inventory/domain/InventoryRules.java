package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import static com.jingshanghui.pos.inventory.domain.InventoryStates.NegativeStockMode;

/** 不依赖数据库的库存数量、精度、方向和负库存不变量。 */
public final class InventoryRules {

    public static final int QUANTITY_SCALE = 6;
    private static final BigDecimal ZERO = new BigDecimal("0.000000");

    private InventoryRules() {
    }

    public static void requireUlid(String value, String field) {
        if (value == null || !value.matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("INV-ID-001: " + field + " 必须为规范 ULID", 409);
        }
    }

    public static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.scale() > QUANTITY_SCALE || value.compareTo(BigDecimal.ZERO) <= 0
            || value.precision() - value.scale() > 13) {
            throw new ServiceException("INV-QTY-001: " + field + " 必须为正且最多六位小数", 409);
        }
        return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal signedDelta(MovementType type, BigDecimal positiveQuantity) {
        BigDecimal quantity = positive(positiveQuantity, "quantity");
        return switch (type) {
            case SALE_OUT, STOCKTAKE_LOSS, PURCHASE_RETURN_OUT, TRANSFER_OUT -> quantity.negate();
            case SALE_RETURN_IN, STOCKTAKE_GAIN, PURCHASE_RECEIPT_IN, TRANSFER_IN -> quantity;
        };
    }

    /** 只允许已准入的来源与移动类型组合，防止通用端口演变成任意调账入口。 */
    public static void requireOwnedMovement(String sourceType, MovementType movementType) {
        boolean allowed = switch (sourceType) {
            case "STOCKTAKE" -> movementType == MovementType.STOCKTAKE_GAIN
                || movementType == MovementType.STOCKTAKE_LOSS;
            case "PURCHASE_RECEIPT" -> movementType == MovementType.PURCHASE_RECEIPT_IN;
            case "PURCHASE_RETURN" -> movementType == MovementType.PURCHASE_RETURN_OUT;
            case "REFUND" -> movementType == MovementType.SALE_RETURN_IN;
            case "TRANSFER_DISPATCH" -> movementType == MovementType.TRANSFER_OUT;
            case "TRANSFER_RECEIPT" -> movementType == MovementType.TRANSFER_IN;
            default -> false;
        };
        if (!allowed) {
            throw new ServiceException("INV-SOURCE-003: 来源与库存移动类型未准入", 409);
        }
    }

    public static BigDecimal available(BigDecimal onHand, BigDecimal reserved,
                                       BigDecimal frozen, BigDecimal safetyStock) {
        return normalized(onHand).subtract(normalized(reserved)).subtract(normalized(frozen))
            .subtract(normalized(safetyStock)).setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    /** 锁内校验策略；受控权限模式尚未准入，必须失败关闭。 */
    public static boolean requiresNegativeAlert(NegativeStockMode mode, BigDecimal availableAfter) {
        if (availableAfter.compareTo(ZERO) >= 0) return false;
        if (mode == NegativeStockMode.DENY) {
            throw new ServiceException("INV-STOCK-001: 可用库存不足且策略禁止负库存", 409);
        }
        if (mode == NegativeStockMode.ALLOW_WITH_PERMISSION) {
            throw new ServiceException("INV-STOCK-002: 本 Gate 未准入主管授权负库存", 403);
        }
        return true;
    }

    public static void requireSourceState(String sourceType, String state, String paymentState) {
        if ("ORDER".equals(sourceType) && (!"COMPLETED".equals(state) || !"PAID".equals(paymentState))) {
            throw new ServiceException("INV-SOURCE-001: 只有已完成且已支付订单可出库", 409);
        }
        if ("REFUND".equals(sourceType) && !"SUCCEEDED".equals(state)) {
            throw new ServiceException("INV-SOURCE-002: 只有成功原单退款可退货入库", 409);
        }
    }

    private static BigDecimal normalized(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }
}
