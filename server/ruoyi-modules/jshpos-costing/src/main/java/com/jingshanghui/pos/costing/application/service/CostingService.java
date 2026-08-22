package com.jingshanghui.pos.costing.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.costing.application.model.CostingCommands.PublishPolicy;
import com.jingshanghui.pos.costing.application.model.CostingCommands.RebuildBalance;
import com.jingshanghui.pos.costing.application.model.CostingViews.BalanceView;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerAggregate;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerView;
import com.jingshanghui.pos.costing.application.model.CostingViews.PolicyView;
import com.jingshanghui.pos.costing.application.model.CostingViews.RebuildResult;
import com.jingshanghui.pos.costing.domain.CostingHash;
import com.jingshanghui.pos.costing.domain.CostingRules;
import com.jingshanghui.pos.costing.domain.CostingRules.BalanceSnapshot;
import com.jingshanghui.pos.costing.domain.CostingRules.CostTransition;
import com.jingshanghui.pos.costing.domain.CostingRules.ValuationInput;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.AuditWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.PolicyWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.RebuildWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.mapper.CostingMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort.CostPostingResult;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort.PostedInventoryLedger;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.application.port.OpeningInventoryCostSourcePort;
import com.jingshanghui.pos.inventory.application.port.OpeningInventoryCostSourcePort.OpeningCostSource;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReturnCostSource;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.DispatchCostSource;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓级移动加权成本 Owner。
 *
 * <p>正式成本效果只能消费库存 Owner 已落库事实；采购价格和原收货关系只能从采购 Owner 读取。</p>
 */
@Service
@RequiredArgsConstructor
public class CostingService implements AuthoritativeCostPostingPort {

    private static final BigDecimal ZERO = new BigDecimal("0.000000");

    private final CostingMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final ProcurementCostSourcePort procurementSourcePort;
    private final TransferCostSourcePort transferSourcePort;
    private final OpeningInventoryCostSourcePort openingSourcePort;
    private final UlidGenerator ulids;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** 在库存 Owner 的同一数据库事务内完成幂等估值、流水、投影、审计和 Outbox。 */
    @Override
    @Transactional
    public CostPostingResult applyPostedLedger(PostedInventoryLedger fact) {
        validateFact(fact);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(fact.storeId());
        ResolvedSource source = resolveSource(principal.tenantId(), fact);
        String sourceHash = sourceHash(fact, source);
        LedgerView existing = mapper.findLedgerByInventory(principal.tenantId(), fact.inventoryLedgerId());
        if (existing != null) {
            if (!sourceHash.equals(existing.sourceSha256())) {
                throw new ServiceException("CST-IDEM-CONFLICT: 相同库存流水对应不同成本事实", 409);
            }
            return result(existing, true);
        }

        LocalDateTime at = now();
        PolicyView policy = requirePolicy(principal.tenantId(), fact.warehouseId(), fact.occurredAt());
        requireFrozenPolicy(policy, fact.storeId());
        String dimension = CostingHash.dimension(principal.tenantId(), fact.warehouseId(), fact.skuId());
        mapper.insertBalanceIfAbsent(new BalanceSeed(principal.tenantId(), dimension, fact.warehouseId(),
            fact.warehouseId(), fact.storeId(), fact.skuId(), CostingRules.CURRENCY,
            policy.policyVersionId(), at));
        BalanceView balance = mapper.lockBalance(principal.tenantId(), dimension);
        if (balance == null) {
            throw new ServiceException("CST-BALANCE-001: 无法锁定成本维度", 409);
        }
        // 并发重复可能在首次预查后由另一事务提交；持锁后必须再次确认再判断单调序列。
        existing = mapper.findLedgerByInventory(principal.tenantId(), fact.inventoryLedgerId());
        if (existing != null) {
            if (!sourceHash.equals(existing.sourceSha256())) {
                throw new ServiceException("CST-IDEM-CONFLICT: 相同库存流水对应不同成本事实", 409);
            }
            return result(existing, true);
        }
        requireSequence(balance, fact);

        CostTransition transition = CostingRules.calculate(new ValuationInput(fact.movementType(),
            fact.quantityDelta(), source.unitCostMinor(), source.estimated(), source.forcedAmountDeltaMinor(),
            source.forcedVarianceMinor(), new BalanceSnapshot(balance.costQuantity(),
                balance.costAmountMinor(), balance.averageUnitCostMinor(), balance.lastUnitCostMinor(),
                balance.lastCostLedgerSequence() > 0)));
        if (transition.quantityBefore().compareTo(fact.quantityBefore()) != 0
            || transition.quantityAfter().compareTo(fact.quantityAfter()) != 0) {
            throw new ServiceException("CST-QTY-MISMATCH: 成本投影数量与权威库存流水不一致", 409);
        }

        long costSequence = balance.lastCostLedgerSequence() + 1;
        String costLedgerId = ulids.next();
        mapper.insertLedger(new LedgerWrite(costLedgerId, principal.tenantId(), dimension, fact.warehouseId(),
            costSequence, fact.inventoryLedgerId(), fact.inventoryLedgerSequence(), fact.warehouseId(),
            fact.skuId(), CostingRules.CURRENCY, fact.movementType(), transition.quantityBefore(),
            transition.quantityDelta(), transition.quantityAfter(), transition.amountBeforeMinor(),
            transition.amountDeltaMinor(), transition.amountAfterMinor(), transition.unitCostMinor(),
            transition.averageUnitCostAfterMinor(), transition.valuationMethod(), transition.costEstimated(),
            transition.varianceAmountMinor(), fact.sourceType(), fact.sourceId(), fact.sourceLineId(),
            fact.sourceEventId(), sourceHash, policy.policyVersionId(), source.reversalOfCostLedgerId(),
            fact.businessDate(), principal.userId(), fact.correlationId(), fact.occurredAt()));
        if (mapper.updateBalance(new BalanceUpdate(principal.tenantId(), dimension,
            transition.quantityAfter(), transition.amountAfterMinor(), transition.averageUnitCostAfterMinor(),
            transition.lastUnitCostAfterMinor(), costSequence, fact.inventoryLedgerSequence(),
            policy.policyVersionId(), balance.recordVersion(), at)) != 1) {
            throw new ServiceException("CST-BALANCE-002: 成本余额并发冲突", 409);
        }
        writeAudit(principal, fact, costLedgerId, sourceHash, transition, at);
        writeOutbox(principal.tenantId(), fact, costLedgerId, costSequence,
            policy.policyVersionId(), transition, at);
        return new CostPostingResult(fact.inventoryLedgerId(), costLedgerId, costSequence, false,
            transition.costEstimated(), transition.varianceAmountMinor());
    }

    /** 发布不可修改的仓级 CNY 成本策略版本。 */
    @Transactional
    public PolicyView publishPolicy(PublishPolicy command) {
        CostingRules.requireUlid(command.policyVersionId(), "policyVersionId");
        CostingRules.requireUlid(command.warehouseId(), "warehouseId");
        requireCorrelation(command.correlationId());
        if (command.storeId() == null || command.storeId() <= 0 || command.effectiveFrom() == null) {
            throw new ServiceException("CST-POLICY-001: 门店或生效时间非法", 409);
        }
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        authorizationService.requireStoreAccess(command.storeId());
        LocalDateTime at = now();
        LocalDateTime effective = LocalDateTime.ofInstant(command.effectiveFrom(), ZoneOffset.UTC);
        mapper.insertPolicy(new PolicyWrite(command.policyVersionId(), principal.tenantId(), command.storeId(),
            command.warehouseId(), effective, principal.userId(), at));
        String hash = CostingHash.sha256(CostingHash.canonical(List.of(command.policyVersionId(),
            command.storeId(), command.warehouseId(), effective, "WAREHOUSE", "CNY", 6, "HALF_EVEN")));
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), command.storeId(),
            "COST_POLICY_PUBLISHED", "COST_POLICY", command.policyVersionId(), principal.userId(),
            command.policyVersionId(), command.correlationId(), null, effective.toString(), hash,
            "VERSION_PUBLISHED", at));
        writePolicyOutbox(principal.tenantId(), command, effective, hash, at);
        return new PolicyView(command.policyVersionId(), command.storeId(), command.warehouseId(),
            "WAREHOUSE", CostingRules.CURRENCY, 6, 6, "HALF_EVEN",
            "ZERO_AMOUNT_KEEP_LAST_UNIT_COST", effective);
    }

    @Transactional(readOnly = true)
    public BalanceView findBalance(String warehouseId, Long skuId) {
        validateDimension(warehouseId, skuId);
        String tenantId = tenantContext.requireTenantId();
        BalanceView balance = mapper.findBalance(tenantId, warehouseId, skuId);
        if (balance == null) {
            throw new ServiceException("CST-BALANCE-003: 成本维度不存在或不可见", 404);
        }
        authorizationService.requireStoreAccess(balance.storeId());
        return balance;
    }

    @Transactional(readOnly = true)
    public List<LedgerView> findLedger(String warehouseId, Long skuId, long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 500) {
            throw new ServiceException("CST-INPUT-004: 流水游标或分页大小非法", 400);
        }
        BalanceView balance = findBalance(warehouseId, skuId);
        return mapper.findLedger(tenantContext.requireTenantId(), balance.costDimensionKey(), afterSequence, limit);
    }

    /** 只从不可变成本流水重建余额；不修改库存、采购或历史成本流水。 */
    @Transactional
    public RebuildResult rebuild(RebuildBalance command) {
        CostingRules.requireUlid(command.rebuildId(), "rebuildId");
        validateDimension(command.warehouseId(), command.skuId());
        requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        BalanceView visible = mapper.findBalance(principal.tenantId(), command.warehouseId(), command.skuId());
        if (visible == null) throw new ServiceException("CST-BALANCE-003: 成本维度不存在或不可见", 404);
        authorizationService.requireStoreAccess(visible.storeId());
        BalanceView balance = mapper.lockBalance(principal.tenantId(), visible.costDimensionKey());
        LedgerAggregate aggregate = mapper.aggregateLedger(principal.tenantId(), visible.costDimensionKey());
        if (aggregate == null || aggregate.ledgerCount() == 0) {
            throw new ServiceException("CST-REBUILD-001: 无成本流水可供重建", 409);
        }
        BigDecimal quantity = aggregate.quantity().setScale(CostingRules.SCALE, RoundingMode.UNNECESSARY);
        BigDecimal amount = aggregate.amountMinor().setScale(CostingRules.SCALE, RoundingMode.UNNECESSARY);
        BigDecimal average = quantity.signum() == 0 ? aggregate.lastUnitCostMinor()
            : amount.divide(quantity, CostingRules.SCALE, CostingRules.ROUNDING);
        if (average.signum() < 0) throw new ServiceException("CST-REBUILD-002: 重建得到负单位成本", 409);
        boolean changed = balance.costQuantity().compareTo(quantity) != 0
            || balance.costAmountMinor().compareTo(amount) != 0
            || balance.lastCostLedgerSequence() != aggregate.lastCostLedgerSequence()
            || balance.lastInventoryLedgerSequence() != aggregate.lastInventoryLedgerSequence();
        LocalDateTime at = now();
        PolicyView policy = requirePolicy(principal.tenantId(), command.warehouseId(), at);
        if (changed && mapper.rebuildBalance(new BalanceUpdate(principal.tenantId(), balance.costDimensionKey(),
            quantity, amount, average, aggregate.lastUnitCostMinor(), aggregate.lastCostLedgerSequence(),
            aggregate.lastInventoryLedgerSequence(), policy.policyVersionId(), balance.recordVersion(), at)) != 1) {
            throw new ServiceException("CST-BALANCE-002: 成本余额并发冲突", 409);
        }
        mapper.insertRebuild(new RebuildWrite(command.rebuildId(), principal.tenantId(),
            balance.costDimensionKey(), balance.warehouseId(), balance.storeId(), balance.skuId(),
            principal.userId(), command.correlationId(), balance.costQuantity(), quantity,
            balance.costAmountMinor(), amount, aggregate.ledgerCount(), changed, at));
        String hash = CostingHash.sha256(CostingHash.canonical(List.of(balance.costDimensionKey(),
            balance.costQuantity(), quantity, balance.costAmountMinor(), amount, aggregate.ledgerCount())));
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), balance.storeId(),
            changed ? "COST_BALANCE_REBUILT" : "COST_BALANCE_VERIFIED", "COST_BALANCE",
            balance.costDimensionKey(), principal.userId(), command.rebuildId(), command.correlationId(),
            balance.costAmountMinor().toPlainString(), amount.toPlainString(), hash, "LEDGER_RECOMPUTE", at));
        return new RebuildResult(command.rebuildId(), balance.costDimensionKey(), balance.costQuantity(),
            quantity, balance.costAmountMinor(), amount, aggregate.ledgerCount(), changed);
    }

    private ResolvedSource resolveSource(String tenantId, PostedInventoryLedger fact) {
        return switch (fact.movementType()) {
            case "PURCHASE_RECEIPT_IN" -> receiptSource(fact);
            case "PURCHASE_RETURN_OUT" -> returnSource(tenantId, fact);
            case "SALE_RETURN_IN" -> saleReturnSource(tenantId, fact);
            case "TRANSFER_OUT" -> transferDispatchSource(fact);
            case "TRANSFER_IN" -> transferReceiptSource(tenantId, fact);
            case "OPENING_IN" -> openingSource(fact);
            case "REVERSAL" -> reversalSource(tenantId, fact);
            case "SALE_OUT", "STOCKTAKE_GAIN", "STOCKTAKE_LOSS" -> ResolvedSource.current();
            default -> throw new ServiceException("CST-MOVEMENT-001: 成本移动类型未准入", 409);
        };
    }

    private ResolvedSource transferDispatchSource(PostedInventoryLedger fact) {
        if (!"TRANSFER_DISPATCH".equals(fact.sourceType())) {
            throw new ServiceException("CST-SOURCE-003: 调拨发出移动缺少权威调拨来源", 409);
        }
        DispatchCostSource source = transferSourcePort.requireDispatchLine(fact.sourceLineId());
        validateTransferLine(fact, source.skuId(), source.baseUnitId(), source.baseQuantity(),
            source.sourceWarehouseId(), source.currencyCode());
        return new ResolvedSource(null, false, null, null, null,
            List.of(source.dispatchId(), source.transferId(), source.sourceWarehouseId(),
                source.destinationWarehouseId(), source.currencyCode()));
    }

    private ResolvedSource openingSource(PostedInventoryLedger fact) {
        if (!"BUSINESS_MIGRATION".equals(fact.sourceType())) {
            throw new ServiceException("CST-SOURCE-005: 期初库存缺少权威迁移来源", 409);
        }
        OpeningCostSource source = openingSourcePort.requireOpeningLine(fact.sourceLineId());
        if (!source.skuId().equals(fact.skuId()) || !source.baseUnitId().equals(fact.baseUnitId())
            || source.baseQuantity().compareTo(fact.quantityDelta()) != 0 || !"CNY".equals(source.currencyCode())) {
            throw new ServiceException("CST-SOURCE-006: 期初库存来源与库存流水不一致", 409);
        }
        return new ResolvedSource(source.unitCostMinor(), false, null, null, null,
            List.of(source.batchId(), source.rowId(), source.currencyCode()));
    }

    private ResolvedSource transferReceiptSource(String tenantId, PostedInventoryLedger fact) {
        if (!"TRANSFER_RECEIPT".equals(fact.sourceType())) {
            throw new ServiceException("CST-SOURCE-004: 调拨收货移动缺少权威调拨来源", 409);
        }
        TransferCostSourcePort.ReceiptCostSource source = transferSourcePort.requireReceiptLine(fact.sourceLineId());
        validateTransferLine(fact, source.skuId(), source.baseUnitId(), source.baseQuantity(),
            source.destinationWarehouseId(), source.currencyCode());
        LedgerView dispatch = mapper.findSourceLedger(tenantId, source.sourceWarehouseId(), fact.skuId(),
            "TRANSFER_DISPATCH", source.dispatchLineId(), "TRANSFER_OUT");
        if (dispatch == null) throw new ServiceException("CST-COST-MISSING: 调拨收货缺少来源仓发出成本快照", 409);
        return new ResolvedSource(dispatch.unitCostMinor(), dispatch.costEstimated(), null, null, null,
            List.of(source.receiptId(), source.dispatchLineId(), dispatch.costLedgerId(),
                source.sourceWarehouseId(), source.destinationWarehouseId(), source.currencyCode()));
    }

    private ResolvedSource receiptSource(PostedInventoryLedger fact) {
        if (!"PURCHASE_RECEIPT".equals(fact.sourceType())) {
            throw new ServiceException("CST-SOURCE-001: 采购收货移动缺少权威采购来源", 409);
        }
        ReceiptCostSource source = procurementSourcePort.requireReceiptLine(fact.sourceLineId());
        validateProcurementLine(fact, source.skuId(), source.baseUnitId(), source.baseQuantity(), source.currencyCode());
        BigDecimal unit = CostingRules.purchaseBaseUnitCost(source.purchaseUnitPriceMinor(),
            source.conversionNumerator(), source.conversionDenominator());
        return new ResolvedSource(unit, false, null, null, null,
            List.of(source.receiptId(), source.orderLineId(), source.purchaseUnitPriceMinor(),
                source.conversionNumerator(), source.conversionDenominator(), source.currencyCode()));
    }

    private ResolvedSource returnSource(String tenantId, PostedInventoryLedger fact) {
        if (!"PURCHASE_RETURN".equals(fact.sourceType())) {
            throw new ServiceException("CST-SOURCE-002: 采购退货移动缺少权威采购来源", 409);
        }
        ReturnCostSource source = procurementSourcePort.requireReturnLine(fact.sourceLineId());
        validateProcurementLine(fact, source.skuId(), source.baseUnitId(), source.baseQuantity(), source.currencyCode());
        LedgerView receipt = mapper.findSourceLedger(tenantId, fact.warehouseId(), fact.skuId(),
            "PURCHASE_RECEIPT", source.originalReceiptLineId(), "PURCHASE_RECEIPT_IN");
        if (receipt == null) throw new ServiceException("CST-COST-MISSING: 采购退货缺少原收货成本流水", 409);
        return new ResolvedSource(receipt.unitCostMinor(), false, null, null, null,
            List.of(source.purchaseReturnId(), source.originalReceiptLineId(), receipt.costLedgerId()));
    }

    private ResolvedSource saleReturnSource(String tenantId, PostedInventoryLedger fact) {
        LedgerView sale = mapper.findSourceLedger(tenantId, fact.warehouseId(), fact.skuId(),
            "ORDER", fact.sourceLineId(), "SALE_OUT");
        if (sale == null) {
            return new ResolvedSource(null, true, null, null, null, List.of("ORIGINAL_SALE_COST_MISSING"));
        }
        return new ResolvedSource(sale.unitCostMinor(), false, null, null, null,
            List.of(sale.costLedgerId(), sale.sourceSha256()));
    }

    private ResolvedSource reversalSource(String tenantId, PostedInventoryLedger fact) {
        if (fact.reversalOfInventoryLedgerId() == null) {
            throw new ServiceException("CST-REVERSAL-001: 冲正必须引用原库存流水", 409);
        }
        LedgerView original = mapper.findLedgerByInventory(tenantId, fact.reversalOfInventoryLedgerId());
        if (original == null || fact.quantityDelta().compareTo(original.quantityDelta().negate()) != 0) {
            throw new ServiceException("CST-REVERSAL-003: 冲正引用不存在或数量不是精确反向", 409);
        }
        return new ResolvedSource(original.unitCostMinor(), original.costEstimated(),
            original.costAmountDeltaMinor().negate(), original.varianceAmountMinor().negate(),
            original.costLedgerId(), List.of(original.costLedgerId(), original.sourceSha256()));
    }

    private void validateProcurementLine(PostedInventoryLedger fact, Long skuId, Long baseUnitId,
                                         BigDecimal baseQuantity, String currency) {
        if (!fact.skuId().equals(skuId) || !fact.baseUnitId().equals(baseUnitId)
            || fact.quantityDelta().abs().compareTo(baseQuantity) != 0
            || !CostingRules.CURRENCY.equals(currency)) {
            throw new ServiceException("CST-SOURCE-MISMATCH: 采购来源与库存事实不一致", 409);
        }
    }

    private void validateTransferLine(PostedInventoryLedger fact, Long skuId, Long baseUnitId,
                                      BigDecimal baseQuantity, String expectedWarehouse, String currency) {
        if (!fact.skuId().equals(skuId) || !fact.baseUnitId().equals(baseUnitId)
            || fact.quantityDelta().abs().compareTo(baseQuantity) != 0
            || !fact.warehouseId().equals(expectedWarehouse) || !CostingRules.CURRENCY.equals(currency)) {
            throw new ServiceException("CST-SOURCE-MISMATCH: 调拨来源与库存事实不一致", 409);
        }
    }

    private String sourceHash(PostedInventoryLedger fact, ResolvedSource source) {
        List<Object> values = new ArrayList<>(List.of(fact.inventoryLedgerId(), fact.inventoryLedgerSequence(),
            fact.stockDimensionKey(), fact.warehouseId(), fact.storeId(), fact.skuId(), fact.baseUnitId(),
            fact.movementType(), fact.quantityBefore().toPlainString(), fact.quantityDelta().toPlainString(),
            fact.quantityAfter().toPlainString(), fact.sourceType(), fact.sourceId(), fact.sourceLineId(),
            fact.sourceEventId(), fact.businessDate(), fact.correlationId(), fact.occurredAt()));
        values.add(String.valueOf(fact.reversalOfInventoryLedgerId()));
        values.add(String.valueOf(source.unitCostMinor()));
        values.add(source.estimated());
        values.add(String.valueOf(source.forcedAmountDeltaMinor()));
        values.add(String.valueOf(source.forcedVarianceMinor()));
        values.add(String.valueOf(source.reversalOfCostLedgerId()));
        values.addAll(source.evidence());
        return CostingHash.sha256(CostingHash.canonical(values));
    }

    private void requireSequence(BalanceView balance, PostedInventoryLedger fact) {
        long expected = balance.lastInventoryLedgerSequence() + 1;
        if (fact.inventoryLedgerSequence() > expected) {
            throw new ServiceException("CST-SEQUENCE-GAP: 成本消费检测到库存流水序列缺口", 409);
        }
        if (fact.inventoryLedgerSequence() < expected) {
            throw new ServiceException("CST-LATE-REQUIRES-REBUILD: 晚到库存事实必须走受控前向修复", 409);
        }
    }

    private PolicyView requirePolicy(String tenantId, String warehouseId, LocalDateTime at) {
        PolicyView policy = mapper.findEffectivePolicy(tenantId, warehouseId, at);
        if (policy == null) throw new ServiceException("CST-POLICY-002: 门店仓缺少已生效成本策略", 409);
        return policy;
    }

    private void requireFrozenPolicy(PolicyView policy, Long storeId) {
        if (!storeId.equals(policy.storeId()) || !"WAREHOUSE".equals(policy.scopeType())
            || !CostingRules.CURRENCY.equals(policy.currencyCode()) || policy.quantityScale() != 6
            || policy.costScale() != 6 || !"HALF_EVEN".equals(policy.roundingMode())
            || !"ZERO_AMOUNT_KEEP_LAST_UNIT_COST".equals(policy.zeroQuantityMode())) {
            throw new ServiceException("CST-POLICY-003: 成本策略与 Gate 4C 冻结规则不一致", 409);
        }
    }

    private void validateFact(PostedInventoryLedger fact) {
        if (fact == null) throw new ServiceException("CST-FACT-001: 库存流水事实为空", 409);
        CostingRules.requireUlid(fact.inventoryLedgerId(), "inventoryLedgerId");
        if (fact.stockDimensionKey() == null || !fact.stockDimensionKey().matches("^[a-f0-9]{64}$")) {
            throw new ServiceException("CST-ID-002: stockDimensionKey 必须为规范 SHA-256", 409);
        }
        CostingRules.requireUlid(fact.warehouseId(), "warehouseId");
        CostingRules.requireUlid(fact.sourceId(), "sourceId");
        CostingRules.requireUlid(fact.sourceLineId(), "sourceLineId");
        CostingRules.requireUlid(fact.sourceEventId(), "sourceEventId");
        requireCorrelation(fact.correlationId());
        if (fact.inventoryLedgerSequence() <= 0 || fact.storeId() == null || fact.storeId() <= 0
            || fact.skuId() == null || fact.skuId() <= 0 || fact.baseUnitId() == null || fact.baseUnitId() <= 0
            || fact.movementType() == null || fact.sourceType() == null || fact.businessDate() == null
            || fact.occurredAt() == null || fact.quantityBefore() == null || fact.quantityDelta() == null
            || fact.quantityAfter() == null || fact.quantityBefore().add(fact.quantityDelta())
            .compareTo(fact.quantityAfter()) != 0) {
            throw new ServiceException("CST-FACT-002: 库存流水事实字段或数量方程非法", 409);
        }
        if (!fact.stockDimensionKey().equals(InventoryHash.dimension(
            tenantContext.requireTenantId(), fact.warehouseId(), fact.skuId()))) {
            throw new ServiceException("CST-FACT-003: 库存维度不属于可信租户、仓库和 SKU", 409);
        }
        boolean sourceMatches = switch (fact.movementType()) {
            case "SALE_OUT" -> "ORDER".equals(fact.sourceType());
            case "SALE_RETURN_IN" -> "REFUND".equals(fact.sourceType());
            case "STOCKTAKE_GAIN", "STOCKTAKE_LOSS" -> "STOCKTAKE".equals(fact.sourceType());
            case "PURCHASE_RECEIPT_IN" -> "PURCHASE_RECEIPT".equals(fact.sourceType());
            case "PURCHASE_RETURN_OUT" -> "PURCHASE_RETURN".equals(fact.sourceType());
            case "TRANSFER_OUT" -> "TRANSFER_DISPATCH".equals(fact.sourceType());
            case "TRANSFER_IN" -> "TRANSFER_RECEIPT".equals(fact.sourceType());
            case "OPENING_IN" -> "BUSINESS_MIGRATION".equals(fact.sourceType());
            case "REVERSAL" -> "REVERSAL".equals(fact.sourceType());
            default -> false;
        };
        if (!sourceMatches) {
            throw new ServiceException("CST-FACT-004: 库存移动与来源 Owner 不匹配", 409);
        }
    }

    private void validateDimension(String warehouseId, Long skuId) {
        CostingRules.requireUlid(warehouseId, "warehouseId");
        if (skuId == null || skuId <= 0) throw new ServiceException("CST-INPUT-001: skuId 非法", 409);
    }

    private void requireCorrelation(String value) {
        if (value == null || value.isBlank() || value.length() > 96) {
            throw new ServiceException("CST-INPUT-002: correlationId 格式非法", 409);
        }
    }

    private CostPostingResult result(LedgerView ledger, boolean duplicate) {
        return new CostPostingResult(ledger.inventoryLedgerId(), ledger.costLedgerId(),
            ledger.costLedgerSequence(), duplicate, ledger.costEstimated(), ledger.varianceAmountMinor());
    }

    private void writeAudit(TrustedPrincipal principal, PostedInventoryLedger fact, String costLedgerId,
                            String sourceHash, CostTransition transition, LocalDateTime at) {
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), fact.storeId(),
            "COST_" + fact.movementType(), "COST_LEDGER", costLedgerId, principal.userId(),
            fact.sourceEventId(), fact.correlationId(), transition.amountBeforeMinor().toPlainString(),
            transition.amountAfterMinor().toPlainString(), sourceHash, transition.valuationMethod(), at));
    }

    private void writeOutbox(String tenantId, PostedInventoryLedger fact, String costLedgerId,
                             long sequence, String policyVersionId,
                             CostTransition transition, LocalDateTime at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("costLedgerId", costLedgerId);
        payload.put("inventoryLedgerId", fact.inventoryLedgerId());
        payload.put("inventoryLedgerSequence", fact.inventoryLedgerSequence());
        payload.put("warehouseId", fact.warehouseId());
        payload.put("skuId", fact.skuId());
        payload.put("currencyCode", CostingRules.CURRENCY);
        payload.put("movementType", fact.movementType());
        payload.put("quantityDelta", transition.quantityDelta().toPlainString());
        payload.put("unitCostMinor", transition.unitCostMinor().toPlainString());
        payload.put("costAmountDeltaMinor", transition.amountDeltaMinor().toPlainString());
        payload.put("costAmountAfterMinor", transition.amountAfterMinor().toPlainString());
        payload.put("avgUnitCostAfterMinor", transition.averageUnitCostAfterMinor().toPlainString());
        payload.put("costEstimated", transition.costEstimated());
        payload.put("varianceAmountMinor", transition.varianceAmountMinor().toPlainString());
        payload.put("policyVersionId", policyVersionId);
        payload.put("correlationId", fact.correlationId());
        insertOutbox(tenantId, "inventory.cost.changed.v1", costLedgerId, sequence,
            fact.correlationId(), payload, at);
    }

    private void writePolicyOutbox(String tenantId, PublishPolicy command, LocalDateTime effective,
                                   String hash, LocalDateTime at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("policyVersionId", command.policyVersionId());
        payload.put("storeId", command.storeId());
        payload.put("warehouseId", command.warehouseId());
        payload.put("currencyCode", CostingRules.CURRENCY);
        payload.put("effectiveFrom", effective.toString());
        payload.put("policySha256", hash);
        insertOutbox(tenantId, "inventory.cost-policy.published.v1", command.policyVersionId(), 1,
            command.correlationId(), payload, at);
    }

    private void insertOutbox(String tenantId, String eventType, String aggregateId, long version,
                              String correlationId, Map<String, Object> payload, LocalDateTime at) {
        String eventId = ulids.next();
        payload.put("eventId", eventId);
        try {
            String json = objectMapper.writeValueAsString(payload);
            mapper.insertOutbox(new OutboxWrite(eventId, tenantId, eventType, aggregateId, version,
                correlationId, json, CostingHash.sha256(json), at));
        } catch (JsonProcessingException exception) {
            throw new ServiceException("CST-EVENT-001: 成本事件序列化失败", 500);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /** 已从受控 Owner 端口解析的估值依据，不接受 REST 提交。 */
    private record ResolvedSource(BigDecimal unitCostMinor, boolean estimated,
                                  BigDecimal forcedAmountDeltaMinor, BigDecimal forcedVarianceMinor,
                                  String reversalOfCostLedgerId, List<?> evidence) {
        private ResolvedSource {
            evidence = List.copyOf(evidence);
        }

        private static ResolvedSource current() {
            return new ResolvedSource(null, false, null, null, null, List.of("CURRENT_BALANCE"));
        }
    }
}
