package com.jingshanghui.pos.procurement.application.service;

import com.jingshanghui.pos.inventory.application.port.ReplenishmentInventorySnapshotPort.InventorySnapshot;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.*;
import com.jingshanghui.pos.procurement.domain.ReplenishmentHash;
import com.jingshanghui.pos.procurement.domain.ReplenishmentRules;
import com.jingshanghui.pos.procurement.domain.ReplenishmentRules.Calculation;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 补货命令校验、确定性摘要和查询边界策略。
 *
 * <p>该策略无 Mapper、无事务、无跨 Owner 调用；原摘要字段顺序、错误码与时钟容差保持不变。</p>
 */
final class ReplenishmentCommandPolicy {
    String suggestionHash(PolicyView policy, PolicyItemView item, InventorySnapshot inventory,
                          BigDecimal transit, Calculation calculation) {
        return ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(policy.policyVersionId(),
            item.itemSha256(), inventory.lastLedgerSequence(), inventory.balanceVersion(),
            inventory.onHandQuantity(), inventory.reservedQuantity(), inventory.frozenQuantity(),
            inventory.safetyStockQuantity(), inventory.availableQuantity(), transit,
            calculation.effectiveQuantity(), calculation.requiredBaseQuantity(),
            calculation.suggestedPurchaseQuantity())));
    }

    String policyRequestHash(CreatePolicy command) {
        List<Object> values = new ArrayList<>(List.of(command.policyVersionId(), command.storeId(),
            command.warehouseId(), command.versionNo(), command.effectiveFrom(), command.idempotencyKey()));
        command.items().stream().sorted(Comparator.comparing(PolicyItemInput::policyItemId)).forEach(item -> {
            values.add(item.policyItemId()); values.add(item.skuId()); values.add(item.purchaseUnitId());
            values.add(item.supplierId()); values.add(item.minimumBaseQuantity()); values.add(item.maximumBaseQuantity());
            values.add(item.minimumOrderQuantity()); values.add(item.orderMultiple());
            values.add(item.includeConfirmedInTransit()); values.add(item.unitPriceMinor()); values.add(item.taxRateBps());
        });
        return ReplenishmentHash.sha256(ReplenishmentHash.canonical(values));
    }

    String hashCommand(Object... values) {
        return ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(values)));
    }

    void validateCreatePolicy(CreatePolicy command) {
        if (command == null) throw new ServiceException("RPL-INPUT-004: 请求为空", 400);
        ReplenishmentRules.ulid(command.policyVersionId(), "policyVersionId");
        ReplenishmentRules.ulid(command.warehouseId(), "warehouseId");
        requireStore(command.storeId());
        if (command.versionNo() <= 0 || command.effectiveFrom() == null || command.items().isEmpty()
            || command.items().size() > 10_000) {
            throw new ServiceException("RPL-POLICY-003: 版本、生效时刻或规则项数量非法", 409);
        }
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
        HashSet<String> ids = new HashSet<>();
        HashSet<Long> skus = new HashSet<>();
        for (PolicyItemInput item : command.items()) {
            ReplenishmentRules.ulid(item.policyItemId(), "policyItemId");
            ReplenishmentRules.ulid(item.supplierId(), "supplierId");
            if (item.skuId() == null || item.skuId() <= 0 || item.purchaseUnitId() == null
                || item.purchaseUnitId() <= 0 || !ids.add(item.policyItemId()) || !skus.add(item.skuId())) {
                throw new ServiceException("RPL-POLICY-006: 规则项标识、SKU或单位非法/重复", 409);
            }
        }
    }

    void validatePolicyCommand(PolicyCommand command) {
        if (command == null || command.expectedVersion() < 0) {
            throw new ServiceException("RPL-INPUT-004: 请求或版本非法", 400);
        }
        ReplenishmentRules.ulid(command.policyVersionId(), "policyVersionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    void validateGeneration(GenerateSuggestions command, Instant serviceNow) {
        if (command == null || command.calculationAt() == null) {
            throw new ServiceException("RPL-INPUT-004: 请求或计算时点为空", 400);
        }
        ReplenishmentRules.ulid(command.generationRunId(), "generationRunId");
        ReplenishmentRules.ulid(command.policyVersionId(), "policyVersionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
        if (command.calculationAt().isAfter(serviceNow.plusSeconds(300))) {
            throw new ServiceException("RPL-RUN-001: 计算时点不可显著晚于服务端时钟", 409);
        }
    }

    void validateSuggestionCommand(SuggestionCommand command) {
        if (command == null || command.expectedVersion() < 0) {
            throw new ServiceException("RPL-INPUT-004: 请求或版本非法", 400);
        }
        ReplenishmentRules.ulid(command.suggestionId(), "suggestionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    void validateDraft(CreatePurchaseDraft command) {
        if (command == null || command.expectedVersion() < 0 || command.expectedDate() == null) {
            throw new ServiceException("RPL-INPUT-004: 请求、版本或预计日期非法", 400);
        }
        ReplenishmentRules.ulid(command.suggestionId(), "suggestionId");
        ReplenishmentRules.ulid(command.purchaseOrderId(), "purchaseOrderId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    String optionalState(String state) {
        return state == null || state.isBlank() ? null : ReplenishmentRules.text(state, 24, "RPL-INPUT-007");
    }

    int pageLimit(int limit) {
        if (limit < 1 || limit > 500) throw new ServiceException("RPL-INPUT-008: limit 必须为1至500", 400);
        return limit;
    }

    void requireStore(Long storeId) {
        if (storeId == null || storeId <= 0) throw new ServiceException("RPL-INPUT-009: storeId 非法", 400);
    }

    LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}
