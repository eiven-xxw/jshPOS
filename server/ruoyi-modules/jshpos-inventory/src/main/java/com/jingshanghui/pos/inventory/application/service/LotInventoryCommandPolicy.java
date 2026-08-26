package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules.PolicySpec;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveLine;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.domain.LotInventoryRules;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 批次命令校验、规范化、身份摘要及业务日期策略。
 *
 * <p>该对象不访问 Mapper、不持有事务，只从原应用服务提取纯规则；错误码、
 * 摘要字段顺序、日期算法与允许的移动类型保持不变。</p>
 */
final class LotInventoryCommandPolicy {
    private static final Set<String> INBOUND = Set.of("PURCHASE_RECEIPT_IN", "SALE_RETURN_IN",
        "STOCKTAKE_GAIN", "TRANSFER_IN", "OPENING_IN", "OPENING_ADJUSTMENT");
    private static final Set<String> OUTBOUND = Set.of("SALE_OUT", "PURCHASE_RETURN_OUT",
        "STOCKTAKE_LOSS", "TRANSFER_OUT");

    boolean isInbound(String movement) { return INBOUND.contains(movement); }

    boolean isOutbound(String movement) { return OUTBOUND.contains(movement); }

    LocalDate resolveExpiry(PolicyView policy, LocalDate production, LocalDate received,
                            LocalDate explicitExpiry) {
        return LotExpiryRules.resolveExpiry(new PolicySpec(policy.policyVersionId(), policy.storeId(), policy.skuId(),
            policy.enabled(), policy.expiryBasis(), policy.shelfLifeDays(), policy.nearExpiryDays(),
            policy.effectiveFrom()), production, received, explicitExpiry);
    }

    void requireLotScope(CommandSource source, Long skuId, Long unitId, LotView lot) {
        if (!source.storeId().equals(lot.storeId()) || !source.warehouseId().equals(lot.warehouseId())
            || !skuId.equals(lot.skuId()) || !unitId.equals(lot.baseUnitId())) {
            throw new ServiceException("LOT-SCOPE-001: 批次与门店、仓库、商品或单位不一致", 409);
        }
    }

    void requireSource(CommandSource source) {
        if (source == null) throw new ServiceException("LOT-COMMAND-002: 批次来源命令缺失", 400);
        InventoryRules.requireUlid(source.eventId(), "eventId");
        InventoryRules.requireUlid(source.sourceId(), "sourceId");
        InventoryRules.requireUlid(source.warehouseId(), "warehouseId");
        if (source.sourceType() == null || source.sourceType().isBlank() || source.sourceType().length() > 32
            || source.storeId() == null || source.storeId() <= 0 || source.businessDate() == null
            || source.correlationId() == null || source.correlationId().isBlank()
            || source.correlationId().length() > 96) {
            throw new ServiceException("LOT-COMMAND-003: 批次来源字段非法", 400);
        }
    }

    void requireLine(String sourceLineId, Long skuId, Long unitId, BigDecimal quantity) {
        InventoryRules.requireUlid(sourceLineId, "sourceLineId");
        if (skuId == null || skuId <= 0 || unitId == null || unitId <= 0) {
            throw new ServiceException("LOT-LINE-001: SKU 或基础单位非法", 400);
        }
        LotInventoryRules.exactQuantity(quantity, "quantity");
    }

    void requireLines(List<?> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 500) {
            throw new ServiceException("LOT-LINE-002: 批次命令行数必须为 1..500", 400);
        }
    }

    String explicitMovement(String commandMovement, String lineMovement) {
        String movement = normalizeMovement(lineMovement == null || lineMovement.isBlank()
            ? commandMovement : lineMovement);
        if (!INBOUND.contains(movement) && !OUTBOUND.contains(movement) || "SALE_OUT".equals(movement)
            || "SALE_RETURN_IN".equals(movement)) {
            throw new ServiceException("LOT-SOURCE-005: 显式批次移动类型未准入", 409);
        }
        return movement;
    }

    String eventType(String action) {
        return switch (action) {
            case "LOT_RECEIVED" -> "inventory.lot.received.v1";
            case "LOT_SALE_ALLOCATED" -> "inventory.lot.allocated.v1";
            case "LOT_RETURN_RESTORED" -> "inventory.lot.returned.v1";
            case "LOT_EXPLICIT_APPLIED" -> "inventory.lot.moved.v1";
            case "LOT_TRANSFER_RECEIVED" -> "inventory.lot.transfer-received.v1";
            default -> throw new ServiceException("LOT-EVENT-002: 未知批次事件动作", 500);
        };
    }

    String nullableCode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 96) throw new ServiceException("LOT-CODE-001: 供应商批号过长", 400);
        return normalized;
    }

    String normalizedCode(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.strip();
        if (normalized.length() > 96) throw new ServiceException("LOT-CODE-002: 内部批号过长", 400);
        return normalized;
    }

    String identityHash(CommandSource source, ReceiveLine line, PolicyView policy, LocalDate expiry) {
        return InventoryHash.sha256(InventoryHash.canonical(java.util.Arrays.asList(source.warehouseId(), line.skuId(),
            line.baseUnitId(), nullableCode(line.supplierLotCode()), normalizedCode(line.internalLotCode(),
                line.sourceLineId()), line.productionDate(), line.receivedDate(), expiry, policy.policyVersionId())));
    }

    LotView withStatus(LotView lot, LocalDate businessDate) {
        String value = lot.onHandQuantity().signum() == 0 ? "DEPLETED"
            : LotExpiryRules.classify(businessDate, lot.expiryDate(), lot.nearExpiryDays());
        return new LotView(lot.lotId(), lot.storeId(), lot.warehouseId(), lot.skuId(), lot.baseUnitId(),
            lot.supplierLotCode(), lot.internalLotCode(), lot.productionDate(), lot.receivedDate(), lot.expiryDate(),
            lot.policyVersionId(), lot.nearExpiryDays(), lot.onHandQuantity(), lot.lastLedgerSequence(), value,
            lot.updatedAt());
    }

    Instant atBusinessDate(LocalDate value, String zoneId, LocalTime businessDayStart) {
        try {
            if (businessDayStart == null) throw new IllegalArgumentException("businessDayStart");
            return value.atTime(businessDayStart).atZone(ZoneId.of(zoneId)).toInstant();
        } catch (RuntimeException exception) {
            throw new ServiceException("LOT-DATE-003: 门店业务时区或业务日起点非法", 409);
        }
    }

    private String normalizeMovement(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }
}
