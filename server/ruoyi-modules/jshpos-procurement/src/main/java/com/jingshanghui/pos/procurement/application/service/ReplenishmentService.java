package com.jingshanghui.pos.procurement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.ReplenishmentInventorySnapshotPort;
import com.jingshanghui.pos.inventory.application.port.ReplenishmentInventorySnapshotPort.InventorySnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.*;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentProcurementSnapshotPort;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentPurchaseDraftPort;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentPurchaseDraftPort.DraftCommand;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentPurchaseDraftPort.DraftResult;
import com.jingshanghui.pos.procurement.domain.ProcurementRules;
import com.jingshanghui.pos.procurement.domain.ReplenishmentHash;
import com.jingshanghui.pos.procurement.domain.ReplenishmentRules;
import com.jingshanghui.pos.procurement.domain.ReplenishmentRules.Calculation;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.*;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ReplenishmentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 商业 V1 确定性补货应用服务。
 *
 * <p>本服务只写 rpl_* 自有事实；库存和采购能力分别通过受控端口读取或调用。</p>
 */
@Service
@RequiredArgsConstructor
public class ReplenishmentService {

    private static final List<String> OPEN_STATES = List.of("GENERATED", "REVIEWED", "APPROVED");
    private final ReplenishmentMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final InventoryCatalogSnapshotPort catalogPort;
    private final ReplenishmentInventorySnapshotPort inventoryPort;
    private final ReplenishmentProcurementSnapshotPort procurementSnapshotPort;
    private final ReplenishmentPurchaseDraftPort purchaseDraftPort;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 创建尚未生效的规则版本，并冻结商品单位与供应商快照。 */
    @Transactional
    public PolicyDetail createPolicy(CreatePolicy command) {
        validateCreatePolicy(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        authorizationService.requireStoreAccess(command.storeId());
        String requestHash = policyRequestHash(command);
        PolicyView existingByKey = mapper.findPolicyByIdempotencyKey(principal.tenantId(), command.idempotencyKey());
        if (existingByKey != null) {
            if (!command.policyVersionId().equals(existingByKey.policyVersionId())
                || !requestHash.equals(mapper.findPolicyRequestHash(principal.tenantId(), existingByKey.policyVersionId()))) {
                throw new ServiceException("RPL-IDEM-001: 相同创建幂等键对应不同规则内容", 409);
            }
            return policyDetail(existingByKey.policyVersionId());
        }
        PolicyView existing = mapper.findPolicy(principal.tenantId(), command.policyVersionId());
        if (existing != null) {
            if (!requestHash.equals(mapper.findPolicyRequestHash(principal.tenantId(), command.policyVersionId()))) {
                throw new ServiceException("RPL-IDEM-001: 相同规则版本标识对应不同内容", 409);
            }
            return policyDetail(command.policyVersionId());
        }
        LocalDateTime at = now();
        mapper.insertPolicy(new PolicyWrite(command.policyVersionId(), principal.tenantId(), command.storeId(),
            command.warehouseId(), command.versionNo(), utc(command.effectiveFrom()), command.idempotencyKey(),
            requestHash, principal.userId(), at));
        for (PolicyItemInput input : command.items().stream()
            .sorted(Comparator.comparing(PolicyItemInput::policyItemId)).toList()) {
            SkuUnitSnapshot unit = catalogPort.requireUnit(input.skuId(), input.purchaseUnitId());
            procurementSnapshotPort.requireActiveSupplier(input.supplierId());
            ReplenishmentRules.requireRule(input.minimumBaseQuantity(), input.maximumBaseQuantity(),
                input.minimumOrderQuantity(), input.orderMultiple(), unit.numerator(), unit.denominator());
            long price = ProcurementRules.money(input.unitPriceMinor());
            int tax = ProcurementRules.taxRate(input.taxRateBps());
            String itemHash = ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(
                input.policyItemId(), input.skuId(), unit.skuCode(), unit.baseUnitId(), input.purchaseUnitId(),
                unit.numerator(), unit.denominator(), input.supplierId(), input.minimumBaseQuantity(),
                input.maximumBaseQuantity(), input.minimumOrderQuantity(), input.orderMultiple(),
                input.includeConfirmedInTransit(), price, tax)));
            mapper.insertPolicyItem(new PolicyItemWrite(input.policyItemId(), principal.tenantId(),
                command.policyVersionId(), input.skuId(), unit.skuCode(), unit.baseUnitId(),
                input.purchaseUnitId(), unit.numerator(), unit.denominator(), input.supplierId(),
                ReplenishmentRules.nonNegative(input.minimumBaseQuantity(), "minimumBaseQuantity"),
                ReplenishmentRules.nonNegative(input.maximumBaseQuantity(), "maximumBaseQuantity"),
                ReplenishmentRules.positive(input.minimumOrderQuantity(), "minimumOrderQuantity"),
                ReplenishmentRules.positive(input.orderMultiple(), "orderMultiple"),
                input.includeConfirmedInTransit(), price, tax, itemHash, at));
        }
        audit(principal, command.storeId(), "REPLENISHMENT_POLICY_CREATED", "REPLENISHMENT_POLICY",
            command.policyVersionId(), command.idempotencyKey(), command.correlationId(), null, "DRAFT",
            "CREATED", requestHash, at);
        return policyDetail(command.policyVersionId());
    }

    /** 发布后规则内容不可变，未来生效由生成时点显式判断。 */
    @Transactional
    public PolicyDetail publishPolicy(PolicyCommand command) {
        return changePolicyState(command, "DRAFT", "PUBLISHED", true);
    }

    /** 停用仅阻止新生成，不覆盖既有建议和采购草稿。 */
    @Transactional
    public PolicyDetail retirePolicy(PolicyCommand command) {
        return changePolicyState(command, "PUBLISHED", "RETIRED", false);
    }

    /** 按库存检查点、规则版本和可选确认在途量生成可解释建议。 */
    @Transactional
    public GenerationResult generate(GenerateSuggestions command) {
        validateGeneration(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        PolicyView policy = mapper.lockPolicy(principal.tenantId(), command.policyVersionId());
        if (policy == null) throw new ServiceException("RPL-POLICY-001: 规则版本不存在或不可见", 404);
        authorizationService.requireStoreAccess(policy.storeId());
        LocalDateTime calculationAt = utc(command.calculationAt());
        if (!"PUBLISHED".equals(policy.state()) || policy.effectiveFrom().isAfter(calculationAt)) {
            throw new ServiceException("RPL-POLICY-004: 规则未发布或尚未生效", 409);
        }
        String requestHash = ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(
            command.generationRunId(), command.policyVersionId(), calculationAt, command.idempotencyKey())));
        GenerationRunView existing = mapper.findRunByIdempotencyKey(principal.tenantId(), command.idempotencyKey());
        if (existing != null) {
            if (!requestHash.equals(existing.requestSha256())) {
                throw new ServiceException("RPL-IDEM-002: 相同生成幂等键对应不同内容", 409);
            }
            return new GenerationResult(existing,
                mapper.listSuggestionsByRun(principal.tenantId(), existing.generationRunId()), true);
        }
        GenerationRunView existingById = mapper.findRun(principal.tenantId(), command.generationRunId());
        if (existingById != null) {
            if (!requestHash.equals(existingById.requestSha256())) {
                throw new ServiceException("RPL-IDEM-002: 相同生成运行标识对应不同内容", 409);
            }
            return new GenerationResult(existingById,
                mapper.listSuggestionsByRun(principal.tenantId(), existingById.generationRunId()), true);
        }
        LocalDateTime at = now();
        mapper.insertRun(new RunWrite(command.generationRunId(), principal.tenantId(), policy.policyVersionId(),
            policy.storeId(), policy.warehouseId(), calculationAt, command.idempotencyKey(), requestHash,
            principal.userId(), at));
        int count = 0;
        for (PolicyItemView item : mapper.findPolicyItems(principal.tenantId(), policy.policyVersionId())) {
            InventorySnapshot inventory = inventoryPort.requireReplenishmentSnapshot(policy.warehouseId(), item.skuId());
            if (!policy.storeId().equals(inventory.storeId())) {
                throw new ServiceException("RPL-TEN-001: 仓库门店与规则范围不一致", 403);
            }
            BigDecimal transit = item.includeConfirmedInTransit()
                ? procurementSnapshotPort.confirmedInTransitBase(policy.warehouseId(), item.skuId(), item.supplierId())
                : BigDecimal.ZERO.setScale(ReplenishmentRules.QUANTITY_SCALE);
            Optional<Calculation> result = ReplenishmentRules.calculate(inventory.availableQuantity(), transit,
                item.minimumBaseQuantity(), item.maximumBaseQuantity(), item.minimumOrderQuantity(),
                item.orderMultiple(), item.conversionNumerator(), item.conversionDenominator(),
                item.includeConfirmedInTransit());
            if (result.isEmpty()) continue;
            stalePrevious(principal, item, inventory.lastLedgerSequence(), command.correlationId(), at);
            Calculation calculation = result.orElseThrow();
            String suggestionId = ulids.next();
            String contentHash = suggestionHash(policy, item, inventory, transit, calculation);
            mapper.insertSuggestion(new SuggestionWrite(suggestionId, principal.tenantId(),
                command.generationRunId(), policy.policyVersionId(), item.policyItemId(), policy.storeId(),
                policy.warehouseId(), item.skuId(), item.skuCode(), item.baseUnitId(), item.purchaseUnitId(),
                item.supplierId(), inventory.onHandQuantity(), inventory.reservedQuantity(),
                inventory.frozenQuantity(), inventory.safetyStockQuantity(), inventory.availableQuantity(), transit,
                calculation.effectiveQuantity(), item.minimumBaseQuantity(), item.maximumBaseQuantity(),
                calculation.requiredBaseQuantity(), calculation.suggestedPurchaseQuantity(),
                item.minimumOrderQuantity(), item.orderMultiple(), item.conversionNumerator(),
                item.conversionDenominator(), inventory.lastLedgerSequence(), inventory.balanceVersion(),
                "BELOW_MINIMUM_REPLENISH_TO_MAXIMUM", contentHash, principal.userId(), at));
            appendTransition(principal, policy.storeId(), suggestionId, "REPLENISHMENT_SUGGESTION_GENERATED",
                "gen:" + ReplenishmentHash.sha256(command.idempotencyKey() + ":" + item.policyItemId()),
                contentHash, "GENERATED", null,
                command.correlationId(), Map.of("ledgerSequence", inventory.lastLedgerSequence(),
                    "suggestedPurchaseQuantity", calculation.suggestedPurchaseQuantity()), at);
            count++;
        }
        if (mapper.completeRun(new RunComplete(principal.tenantId(), command.generationRunId(), count, 0, at)) != 1) {
            throw new ServiceException("RPL-RUN-002: 生成运行版本冲突", 409);
        }
        GenerationRunView run = mapper.findRun(principal.tenantId(), command.generationRunId());
        audit(principal, policy.storeId(), "REPLENISHMENT_GENERATED", "REPLENISHMENT_RUN",
            command.generationRunId(), command.idempotencyKey(), command.correlationId(), "RUNNING", "COMPLETED",
            "COUNT=" + count, requestHash, at);
        return new GenerationResult(run, mapper.listSuggestionsByRun(principal.tenantId(),
            command.generationRunId()), false);
    }

    @Transactional
    public SuggestionView review(SuggestionCommand command) {
        return transition(command, "GENERATED", "REVIEWED", false);
    }

    @Transactional
    public SuggestionView approve(SuggestionCommand command) {
        return transition(command, "REVIEWED", "APPROVED", true);
    }

    @Transactional
    public SuggestionView reject(SuggestionCommand command) {
        validateSuggestionCommand(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        SuggestionView value = requireSuggestion(principal.tenantId(), command.suggestionId(), true);
        authorizationService.requireStoreAccess(value.storeId());
        String commandHash = hashCommand("REJECTED", value.suggestionId(), command.expectedVersion(),
            command.reason(), command.idempotencyKey());
        IdempotencyView duplicate = mapper.findIdempotency(principal.tenantId(), command.idempotencyKey());
        if (duplicate != null) {
            return duplicateResult(principal.tenantId(), duplicate, commandHash, value.suggestionId());
        }
        if (!OPEN_STATES.contains(value.state())) {
            throw new ServiceException("RPL-STATE-003: 当前建议不可驳回", 409);
        }
        return applyTransition(principal, value, "REJECTED", command, false);
    }

    /** 经审批建议才可转采购草稿；库存或单位变化会把建议显式置为 STALE。 */
    @Transactional
    public SuggestionView createPurchaseDraft(CreatePurchaseDraft command) {
        validateDraft(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String commandHash = hashCommand("PURCHASE_DRAFT", command.suggestionId(), command.expectedVersion(),
            command.purchaseOrderId(), command.expectedDate(), command.idempotencyKey());
        IdempotencyView duplicate = mapper.findIdempotency(principal.tenantId(), command.idempotencyKey());
        if (duplicate != null) return duplicateResult(principal.tenantId(), duplicate, commandHash, command.suggestionId());
        SuggestionView value = requireSuggestion(principal.tenantId(), command.suggestionId(), true);
        authorizationService.requireStoreAccess(value.storeId());
        if (!"APPROVED".equals(value.state()) || value.version() != command.expectedVersion()) {
            throw new ServiceException("RPL-STATE-004: 建议未审批或版本已变化", 409);
        }
        InventorySnapshot inventory = inventoryPort.requireReplenishmentSnapshot(value.warehouseId(), value.skuId());
        SkuUnitSnapshot unit = catalogPort.requireUnit(value.skuId(), value.purchaseUnitId());
        if (inventory.lastLedgerSequence() != value.inputLedgerSequence()
            || inventory.balanceVersion() != value.inputBalanceVersion()
            || unit.numerator() != value.conversionNumerator()
            || unit.denominator() != value.conversionDenominator()) {
            return applyTerminal(principal, value, "STALE", null, null, command.idempotencyKey(), commandHash,
                command.correlationId(), "INPUT_CHECKPOINT_CHANGED");
        }
        try {
            procurementSnapshotPort.requireActiveSupplier(value.supplierId());
            DraftResult draft = purchaseDraftPort.createReplenishmentDraft(new DraftCommand(command.purchaseOrderId(),
                value.suggestionId(), value.supplierId(), value.storeId(), value.warehouseId(), command.expectedDate(),
                ulids.next(), value.skuId(), value.purchaseUnitId(), value.conversionNumerator(),
                value.conversionDenominator(), value.suggestedPurchaseQuantity(), policyItemPrice(value),
                policyItemTax(value), command.correlationId()));
            return applyTerminal(principal, value, "PURCHASE_DRAFTED", draft.purchaseOrderId(), null,
                command.idempotencyKey(), commandHash, command.correlationId(), "PURCHASE_DRAFT_CREATED");
        } catch (ServiceException exception) {
            return applyTerminal(principal, value, "FAILED", null, "DRAFT_VALIDATION_FAILED",
                command.idempotencyKey(), commandHash, command.correlationId(), "MANUAL_INTERVENTION_REQUIRED");
        }
    }

    @Transactional(readOnly = true)
    public PolicyDetail policyDetail(String policyVersionId) {
        ReplenishmentRules.ulid(policyVersionId, "policyVersionId");
        String tenantId = tenantContext.requireTenantId();
        PolicyView policy = mapper.findPolicy(tenantId, policyVersionId);
        if (policy == null) throw new ServiceException("RPL-POLICY-001: 规则版本不存在或不可见", 404);
        authorizationService.requireStoreAccess(policy.storeId());
        return new PolicyDetail(policy, mapper.findPolicyItems(tenantId, policyVersionId));
    }

    @Transactional(readOnly = true)
    public List<PolicyView> listPolicies(Long storeId, String state, int limit) {
        requireStore(storeId);
        authorizationService.requireStoreAccess(storeId);
        return mapper.listPolicies(tenantContext.requireTenantId(), storeId, optionalState(state), pageLimit(limit));
    }

    @Transactional(readOnly = true)
    public List<SuggestionView> listSuggestions(Long storeId, String state, int limit) {
        requireStore(storeId);
        authorizationService.requireStoreAccess(storeId);
        return mapper.listSuggestions(tenantContext.requireTenantId(), storeId, optionalState(state), pageLimit(limit));
    }

    private PolicyDetail changePolicyState(PolicyCommand command, String expected, String next, boolean publish) {
        validatePolicyCommand(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        String commandHash = hashCommand(next, command.policyVersionId(), command.expectedVersion(),
            command.idempotencyKey());
        IdempotencyView duplicate = mapper.findAuditIdempotency(principal.tenantId(), command.idempotencyKey());
        if (duplicate != null) {
            if (!commandHash.equals(duplicate.commandSha256())
                || !command.policyVersionId().equals(duplicate.aggregateId())
                || !next.equals(duplicate.resultState())) {
                throw new ServiceException("RPL-IDEM-005: 相同规则命令幂等键对应不同内容", 409);
            }
            return policyDetail(command.policyVersionId());
        }
        PolicyView policy = mapper.lockPolicy(principal.tenantId(), command.policyVersionId());
        if (policy == null) throw new ServiceException("RPL-POLICY-001: 规则版本不存在或不可见", 404);
        authorizationService.requireStoreAccess(policy.storeId());
        if (next.equals(policy.state())) {
            throw new ServiceException("RPL-POLICY-007: 已完成状态只能用原幂等键恢复", 409);
        }
        if (!expected.equals(policy.state()) || policy.version() != command.expectedVersion()) {
            throw new ServiceException("RPL-POLICY-002: 规则状态或版本冲突", 409);
        }
        List<PolicyItemView> items = mapper.findPolicyItems(principal.tenantId(), policy.policyVersionId());
        String contentHash = publish ? ReplenishmentHash.sha256(ReplenishmentHash.canonical(items.stream()
            .map(PolicyItemView::itemSha256).sorted().toList())) : policy.contentSha256();
        LocalDateTime at = now();
        if (mapper.updatePolicyState(new PolicyStateUpdate(principal.tenantId(), policy.policyVersionId(), expected,
            next, policy.version(), contentHash, at)) != 1) {
            throw new ServiceException("RPL-POLICY-002: 规则状态或版本冲突", 409);
        }
        audit(principal, policy.storeId(), "REPLENISHMENT_POLICY_" + next, "REPLENISHMENT_POLICY",
            policy.policyVersionId(), command.idempotencyKey(), command.correlationId(), expected, next,
            ReplenishmentRules.text(command.reason(), 256, "RPL-INPUT-003"),
            commandHash, at);
        outbox(principal.tenantId(), "replenishment.policy." + next.toLowerCase() + ".v1",
            policy.policyVersionId(), policy.version() + 1, command.correlationId(),
            Map.of("policyVersionId", policy.policyVersionId(), "state", next, "contentSha256", contentHash), at);
        return policyDetail(policy.policyVersionId());
    }

    private SuggestionView transition(SuggestionCommand command, String expected, String next, boolean admin) {
        validateSuggestionCommand(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        SuggestionView value = requireSuggestion(principal.tenantId(), command.suggestionId(), true);
        authorizationService.requireStoreAccess(value.storeId());
        if (admin) authorizationService.requireTenantAdministrator();
        String commandHash = hashCommand(next, value.suggestionId(), command.expectedVersion(),
            command.reason(), command.idempotencyKey());
        IdempotencyView duplicate = mapper.findIdempotency(principal.tenantId(), command.idempotencyKey());
        if (duplicate != null) {
            return duplicateResult(principal.tenantId(), duplicate, commandHash, value.suggestionId());
        }
        if (next.equals(value.state())) {
            throw new ServiceException("RPL-STATE-005: 已完成状态只能用原幂等键恢复", 409);
        }
        if (!expected.equals(value.state()) || value.version() != command.expectedVersion()) {
            throw new ServiceException("RPL-STATE-001: 建议状态或版本冲突", 409);
        }
        return applyTransition(principal, value, next, command, admin);
    }

    private SuggestionView applyTransition(TrustedPrincipal principal, SuggestionView value, String next,
                                           SuggestionCommand command, boolean approver) {
        String commandHash = hashCommand(next, value.suggestionId(), command.expectedVersion(),
            command.reason(), command.idempotencyKey());
        IdempotencyView duplicate = mapper.findIdempotency(principal.tenantId(), command.idempotencyKey());
        if (duplicate != null) return duplicateResult(principal.tenantId(), duplicate, commandHash, value.suggestionId());
        LocalDateTime at = now();
        Long reviewer = "REVIEWED".equals(next) ? principal.userId() : null;
        Long approval = approver ? principal.userId() : null;
        if (approver && value.reviewerUserId() != null && value.reviewerUserId().equals(principal.userId())) {
            throw new ServiceException("RPL-AUTH-001: 建议复核与审批必须职责分离", 409);
        }
        if (mapper.updateSuggestionState(new SuggestionStateUpdate(principal.tenantId(), value.suggestionId(),
            value.state(), next, value.version(), null, null, reviewer, approval, at)) != 1) {
            throw new ServiceException("RPL-STATE-001: 建议状态或版本冲突", 409);
        }
        String reason = ReplenishmentRules.text(command.reason(), 256, "RPL-INPUT-003");
        appendTransition(principal, value.storeId(), value.suggestionId(), "REPLENISHMENT_SUGGESTION_" + next,
            command.idempotencyKey(), commandHash, next, null, command.correlationId(), Map.of("reason", reason), at);
        audit(principal, value.storeId(), "REPLENISHMENT_SUGGESTION_" + next, "REPLENISHMENT_SUGGESTION",
            value.suggestionId(), command.idempotencyKey(), command.correlationId(), value.state(), next,
            reason, commandHash, at);
        outbox(principal.tenantId(), "replenishment.suggestion." + next.toLowerCase() + ".v1",
            value.suggestionId(), value.version() + 1, command.correlationId(),
            Map.of("suggestionId", value.suggestionId(), "state", next), at);
        return requireSuggestion(principal.tenantId(), value.suggestionId(), false);
    }

    private SuggestionView applyTerminal(TrustedPrincipal principal, SuggestionView value, String next,
                                         String purchaseOrderId, String failureCode, String idempotencyKey,
                                         String commandHash, String correlationId, String reason) {
        LocalDateTime at = now();
        if (mapper.updateSuggestionState(new SuggestionStateUpdate(principal.tenantId(), value.suggestionId(),
            value.state(), next, value.version(), purchaseOrderId, failureCode, null, null, at)) != 1) {
            throw new ServiceException("RPL-STATE-001: 建议状态或版本冲突", 409);
        }
        appendTransition(principal, value.storeId(), value.suggestionId(), "REPLENISHMENT_SUGGESTION_" + next,
            idempotencyKey, commandHash, next, purchaseOrderId, correlationId, Map.of("reason", reason), at);
        audit(principal, value.storeId(), "REPLENISHMENT_SUGGESTION_" + next, "REPLENISHMENT_SUGGESTION",
            value.suggestionId(), idempotencyKey, correlationId, value.state(), next, reason, commandHash, at);
        outbox(principal.tenantId(), "replenishment.suggestion." + next.toLowerCase() + ".v1",
            value.suggestionId(), value.version() + 1, correlationId,
            Map.of("suggestionId", value.suggestionId(), "state", next), at);
        return requireSuggestion(principal.tenantId(), value.suggestionId(), false);
    }

    private void stalePrevious(TrustedPrincipal principal, PolicyItemView item, long ledgerSequence,
                               String correlationId, LocalDateTime at) {
        for (SuggestionView open : mapper.listOpenSuggestionsForUpdate(principal.tenantId(),
            item.policyVersionId() == null ? "" : requirePolicyWarehouse(principal.tenantId(), item.policyVersionId()),
            item.skuId())) {
            if (open.inputLedgerSequence() > ledgerSequence) {
                throw new ServiceException("RPL-ORDER-001: 旧库存检查点不得覆盖较新建议", 409);
            }
            if (mapper.updateSuggestionState(new SuggestionStateUpdate(principal.tenantId(), open.suggestionId(),
                open.state(), "STALE", open.version(), null, null, null, null, at)) != 1) {
                throw new ServiceException("RPL-STATE-001: 建议状态或版本冲突", 409);
            }
            String idem = "stale:" + open.suggestionId() + ":" + ledgerSequence;
            appendTransition(principal, open.storeId(), open.suggestionId(), "REPLENISHMENT_SUGGESTION_STALE",
                idem, ReplenishmentHash.sha256(idem), "STALE", null, correlationId,
                Map.of("newLedgerSequence", ledgerSequence), at);
        }
    }

    private String requirePolicyWarehouse(String tenantId, String policyVersionId) {
        PolicyView value = mapper.findPolicy(tenantId, policyVersionId);
        if (value == null) throw new ServiceException("RPL-POLICY-001: 规则版本不存在或不可见", 404);
        return value.warehouseId();
    }

    private SuggestionView duplicateResult(String tenantId, IdempotencyView duplicate, String commandHash,
                                           String suggestionId) {
        if (!commandHash.equals(duplicate.commandSha256()) || !suggestionId.equals(duplicate.aggregateId())) {
            throw new ServiceException("RPL-IDEM-004: 相同幂等键对应不同命令内容", 409);
        }
        return requireSuggestion(tenantId, suggestionId, false);
    }

    private void appendTransition(TrustedPrincipal principal, Long storeId, String suggestionId,
                                  String eventType, String idempotencyKey, String commandHash,
                                  String resultState, String resultReferenceId, String correlationId,
                                  Map<String, Object> payload, LocalDateTime at) {
        String json = json(payload);
        mapper.insertEvent(new EventWrite(ulids.next(), principal.tenantId(), storeId, suggestionId, eventType,
            idempotencyKey, commandHash, resultState, resultReferenceId, principal.userId(), correlationId,
            json, ReplenishmentHash.sha256(json), at));
    }

    private void audit(TrustedPrincipal principal, Long storeId, String action, String aggregateType,
                       String aggregateId, String commandId, String correlationId, Object before, Object after,
                       String reason, String requestHash, LocalDateTime at) {
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), storeId, action, aggregateType,
            aggregateId, principal.userId(), commandId, correlationId, before == null ? null : String.valueOf(before),
            after == null ? null : String.valueOf(after), requestHash, reason, at));
    }

    private void outbox(String tenantId, String type, String aggregateId, long version,
                        String correlationId, Map<String, Object> payload, LocalDateTime at) {
        String json = json(payload);
        mapper.insertOutbox(new OutboxWrite(ulids.next(), tenantId, type, aggregateId, version, correlationId,
            json, ReplenishmentHash.sha256(json), at));
    }

    private String json(Map<String, Object> values) {
        Map<String, Object> body = new LinkedHashMap<>(values);
        body.put("schemaVersion", "1.0");
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("RPL-EVENT-001: 补货事件序列化失败", 500);
        }
    }

    private SuggestionView requireSuggestion(String tenantId, String suggestionId, boolean lock) {
        ReplenishmentRules.ulid(suggestionId, "suggestionId");
        SuggestionView value = lock ? mapper.lockSuggestion(tenantId, suggestionId)
            : mapper.findSuggestion(tenantId, suggestionId);
        if (value == null) throw new ServiceException("RPL-SUG-001: 建议不存在或不可见", 404);
        return value;
    }

    private long policyItemPrice(SuggestionView suggestion) {
        return mapper.findPolicyItems(tenantContext.requireTenantId(), suggestion.policyVersionId()).stream()
            .filter(item -> item.policyItemId().equals(suggestion.policyItemId())).findFirst()
            .orElseThrow(() -> new ServiceException("RPL-POLICY-005: 冻结规则项缺失", 409)).unitPriceMinor();
    }

    private int policyItemTax(SuggestionView suggestion) {
        return mapper.findPolicyItems(tenantContext.requireTenantId(), suggestion.policyVersionId()).stream()
            .filter(item -> item.policyItemId().equals(suggestion.policyItemId())).findFirst()
            .orElseThrow(() -> new ServiceException("RPL-POLICY-005: 冻结规则项缺失", 409)).taxRateBps();
    }

    private String suggestionHash(PolicyView policy, PolicyItemView item, InventorySnapshot inventory,
                                  BigDecimal transit, Calculation calculation) {
        return ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(policy.policyVersionId(),
            item.itemSha256(), inventory.lastLedgerSequence(), inventory.balanceVersion(),
            inventory.onHandQuantity(), inventory.reservedQuantity(), inventory.frozenQuantity(),
            inventory.safetyStockQuantity(), inventory.availableQuantity(), transit,
            calculation.effectiveQuantity(), calculation.requiredBaseQuantity(),
            calculation.suggestedPurchaseQuantity())));
    }

    private String policyRequestHash(CreatePolicy command) {
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

    private String hashCommand(Object... values) {
        return ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(values)));
    }

    private void validateCreatePolicy(CreatePolicy command) {
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

    private void validatePolicyCommand(PolicyCommand command) {
        if (command == null || command.expectedVersion() < 0) {
            throw new ServiceException("RPL-INPUT-004: 请求或版本非法", 400);
        }
        ReplenishmentRules.ulid(command.policyVersionId(), "policyVersionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    private void validateGeneration(GenerateSuggestions command) {
        if (command == null || command.calculationAt() == null) {
            throw new ServiceException("RPL-INPUT-004: 请求或计算时点为空", 400);
        }
        ReplenishmentRules.ulid(command.generationRunId(), "generationRunId");
        ReplenishmentRules.ulid(command.policyVersionId(), "policyVersionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
        if (command.calculationAt().isAfter(clock.instant().plusSeconds(300))) {
            throw new ServiceException("RPL-RUN-001: 计算时点不可显著晚于服务端时钟", 409);
        }
    }

    private void validateSuggestionCommand(SuggestionCommand command) {
        if (command == null || command.expectedVersion() < 0) {
            throw new ServiceException("RPL-INPUT-004: 请求或版本非法", 400);
        }
        ReplenishmentRules.ulid(command.suggestionId(), "suggestionId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    private void validateDraft(CreatePurchaseDraft command) {
        if (command == null || command.expectedVersion() < 0 || command.expectedDate() == null) {
            throw new ServiceException("RPL-INPUT-004: 请求、版本或预计日期非法", 400);
        }
        ReplenishmentRules.ulid(command.suggestionId(), "suggestionId");
        ReplenishmentRules.ulid(command.purchaseOrderId(), "purchaseOrderId");
        ReplenishmentRules.text(command.idempotencyKey(), 96, "RPL-INPUT-005");
        ReplenishmentRules.text(command.correlationId(), 96, "RPL-INPUT-006");
    }

    private String optionalState(String state) {
        return state == null || state.isBlank() ? null : ReplenishmentRules.text(state, 24, "RPL-INPUT-007");
    }

    private int pageLimit(int limit) {
        if (limit < 1 || limit > 500) throw new ServiceException("RPL-INPUT-008: limit 必须为1至500", 400);
        return limit;
    }

    private void requireStore(Long storeId) {
        if (storeId == null || storeId <= 0) throw new ServiceException("RPL-INPUT-009: storeId 非法", 400);
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
