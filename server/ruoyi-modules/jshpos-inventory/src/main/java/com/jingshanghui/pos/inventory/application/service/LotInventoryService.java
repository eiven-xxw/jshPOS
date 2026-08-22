package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.port.LotPolicyReadPort;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules;
import com.jingshanghui.pos.catalog.domain.LotExpiryRules.PolicySpec;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.AllocationView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.GenericMovementView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LedgerProjection;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.RebuildCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.RebuildResult;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReturnCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReturnLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.SaleCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.SaleLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.TransferReceiveCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.TransferReceiveLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.domain.LotInventoryRules;
import com.jingshanghui.pos.inventory.domain.LotInventoryRules.Allocation;
import com.jingshanghui.pos.inventory.domain.LotInventoryRules.Candidate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.AllocationWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.AuditWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceRebuild;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.CommandApplied;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.CommandWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.ExpiryProjectionWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.IdentityWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 社区超市批次库存应用服务。
 *
 * <p>仓库总账先落库，批次拆分再在同一事务内逐行守恒校验；任一批次失败会回滚整个 Owner 调用。</p>
 */
@Service
@RequiredArgsConstructor
public class LotInventoryService implements AuthoritativeLotMovementPort {
    private static final Set<String> INBOUND = Set.of("PURCHASE_RECEIPT_IN", "SALE_RETURN_IN",
        "STOCKTAKE_GAIN", "TRANSFER_IN", "OPENING_IN", "OPENING_ADJUSTMENT");
    private static final Set<String> OUTBOUND = Set.of("SALE_OUT", "PURCHASE_RETURN_OUT",
        "STOCKTAKE_LOSS", "TRANSFER_OUT");

    private final LotInventoryMapper mapper;
    private final LotPolicyReadPort policyReadPort;
    private final StoreIndustryReadPort industryReadPort;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 非社区超市或未启用 SKU 返回 false；已启用时上游 Owner 必须提交批次命令。 */
    @Override
    @Transactional(readOnly = true)
    public boolean requiresLotTracking(Long storeId, Long skuId, LocalDate businessDate) {
        if (businessDate == null) throw new ServiceException("LOT-DATE-002: 业务日缺失", 409);
        IndustryBinding binding = industryReadPort.requireCurrentIndustry(storeId);
        if (!LotExpiryRules.COMMUNITY_SUPERMARKET.equals(binding.industry())) return false;
        return policyReadPort.findEffective(storeId, skuId,
                atBusinessDate(businessDate, binding.zoneId(), binding.businessDayStart()))
            .map(PolicyView::enabled).orElse(false);
    }

    /** 收货或调拨入库建立不可变批次身份并追加批次流水。 */
    @Override
    @Transactional
    public ApplyResult receive(ReceiveCommand command) {
        requireSource(command == null ? null : command.source());
        requireLines(command.lines());
        String hash = hash(command);
        ApplyResult replay = replay(command.source(), hash);
        if (replay != null) return replay;
        TrustedPrincipal principal = begin(command.source(), hash);
        Map<String, BigDecimal> expectedBySourceLine = new java.util.LinkedHashMap<>();
        for (ReceiveLine line : command.lines()) {
            requireLine(line.sourceLineId(), line.skuId(), line.baseUnitId(), line.quantity());
            String key = line.sourceLineId() + "|" + line.skuId();
            expectedBySourceLine.merge(key, LotInventoryRules.exactQuantity(line.quantity(), "receiveQuantity"),
                BigDecimal::add);
        }
        Map<String, GenericMovementView> genericBySourceLine = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> entry : expectedBySourceLine.entrySet()) {
            int separator = entry.getKey().lastIndexOf('|');
            String sourceLineId = entry.getKey().substring(0, separator);
            Long skuId = Long.valueOf(entry.getKey().substring(separator + 1));
            GenericMovementView generic = requireGeneric(command.source(), sourceLineId, skuId,
                entry.getValue().setScale(LotInventoryRules.QUANTITY_SCALE));
            if (!INBOUND.contains(generic.movementType())) {
                throw new ServiceException("LOT-SOURCE-002: 收货批次对应的仓库总账方向非法", 409);
            }
            genericBySourceLine.put(entry.getKey(), generic);
        }
        List<AllocationView> allocations = new ArrayList<>();
        for (ReceiveLine line : command.lines()) {
            BigDecimal quantity = LotInventoryRules.exactQuantity(line.quantity(), "receiveQuantity");
            GenericMovementView generic = genericBySourceLine.get(line.sourceLineId() + "|" + line.skuId());
            PolicyView policy = requireEnabledPolicy(command.source(), line.skuId());
            LocalDate expiry = resolveExpiry(policy, line.productionDate(), line.receivedDate(), line.explicitExpiryDate());
            String identityHash = identityHash(command.source(), line, policy, expiry);
            LotView lot = mapper.findLotByIdentityHash(principal.tenantId(), command.source().warehouseId(),
                line.skuId(), identityHash);
            if (lot == null) {
                String lotId = ulids.next();
                String internalCode = normalizedCode(line.internalLotCode(), line.sourceLineId());
                mapper.insertIdentity(new IdentityWrite(lotId, principal.tenantId(), command.source().storeId(),
                    command.source().warehouseId(), line.skuId(), line.baseUnitId(), nullableCode(line.supplierLotCode()),
                    internalCode, line.productionDate(), line.receivedDate(), expiry, policy.policyVersionId(),
                    policy.nearExpiryDays(), identityHash, now()));
                mapper.insertBalanceIfAbsent(new BalanceSeed(principal.tenantId(), lotId, now()));
                lot = requireLot(principal.tenantId(), lotId);
            }
            allocations.add(applyToLot(principal, command.source(), line.sourceLineId(), lot, quantity,
                generic.movementType(), "EXPLICIT", null, null));
        }
        return complete(principal, command.source(), hash, allocations, "LOT_RECEIVED");
    }

    /** 销售只根据锁内 FEFO 结果分配，不接受客户端提供批次或过期状态。 */
    @Override
    @Transactional
    public ApplyResult allocateSale(SaleCommand command) {
        requireSource(command == null ? null : command.source());
        requireLines(command.lines());
        String hash = hash(command);
        ApplyResult replay = replay(command.source(), hash);
        if (replay != null) return replay;
        TrustedPrincipal principal = begin(command.source(), hash);
        List<AllocationView> results = new ArrayList<>();
        for (SaleLine line : command.lines()) {
            requireLine(line.sourceLineId(), line.skuId(), line.baseUnitId(), line.quantity());
            BigDecimal quantity = LotInventoryRules.exactQuantity(line.quantity(), "saleQuantity");
            GenericMovementView generic = requireGeneric(command.source(), line.sourceLineId(), line.skuId(), quantity);
            if (!"SALE_OUT".equals(generic.movementType())) {
                throw new ServiceException("LOT-SOURCE-003: 销售批次必须绑定 SALE_OUT 仓库总账", 409);
            }
            requireEnabledPolicy(command.source(), line.skuId());
            List<LotView> lots = mapper.lockFefoCandidates(principal.tenantId(), command.source().warehouseId(),
                line.skuId(), command.source().businessDate(), 100);
            List<Allocation> selected = LotInventoryRules.allocateFefo(lots.stream().map(lot -> new Candidate(
                lot.lotId(), lot.receivedDate(), lot.expiryDate(), lot.onHandQuantity(), lot.policyVersionId())).toList(),
                quantity, command.source().businessDate());
            for (Allocation allocation : selected) {
                LotView lot = lots.stream().filter(item -> item.lotId().equals(allocation.lotId())).findFirst()
                    .orElseThrow(() -> new ServiceException("LOT-BALANCE-002: 锁内批次不可见", 409));
                results.add(applyToLot(principal, command.source(), line.sourceLineId(), lot, allocation.quantity(),
                    "SALE_OUT", "SALE", null, null));
            }
        }
        return complete(principal, command.source(), hash, results, "LOT_SALE_ALLOCATED");
    }

    /** 退货严格沿用原销售分配；过期批次可以退回但仍保持禁售。 */
    @Override
    @Transactional
    public ApplyResult returnOriginal(ReturnCommand command) {
        requireSource(command == null ? null : command.source());
        InventoryRules.requireUlid(command.originalOrderId(), "originalOrderId");
        requireLines(command.lines());
        String hash = hash(command);
        ApplyResult replay = replay(command.source(), hash);
        if (replay != null) return replay;
        TrustedPrincipal principal = begin(command.source(), hash);
        List<AllocationView> results = new ArrayList<>();
        for (ReturnLine line : command.lines()) {
            requireLine(line.sourceLineId(), line.skuId(), line.baseUnitId(), line.quantity());
            InventoryRules.requireUlid(line.originalOrderLineId(), "originalOrderLineId");
            BigDecimal remaining = LotInventoryRules.exactQuantity(line.quantity(), "returnQuantity");
            GenericMovementView generic = requireGeneric(command.source(), line.sourceLineId(), line.skuId(), remaining);
            if (!"SALE_RETURN_IN".equals(generic.movementType())) {
                throw new ServiceException("LOT-SOURCE-004: 原单退货必须绑定 SALE_RETURN_IN 仓库总账", 409);
            }
            List<AllocationView> originals = mapper.lockReturnableAllocations(principal.tenantId(),
                command.originalOrderId(), line.originalOrderLineId(), line.skuId());
            for (AllocationView original : originals) {
                if (remaining.signum() == 0) break;
                BigDecimal amount = original.quantity().min(remaining).setScale(LotInventoryRules.QUANTITY_SCALE);
                LotView lot = requireLot(principal.tenantId(), original.lotId());
                requireEnabledPolicy(command.source(), line.skuId());
                results.add(applyToLot(principal, command.source(), line.sourceLineId(), lot, amount,
                    "SALE_RETURN_IN", "RETURN", command.originalOrderId(), line.originalOrderLineId()));
                remaining = remaining.subtract(amount).setScale(LotInventoryRules.QUANTITY_SCALE);
            }
            if (remaining.signum() != 0) {
                throw new ServiceException("LOT-RETURN-001: 累计退货数量超过原批次分配上限", 409);
            }
        }
        return complete(principal, command.source(), hash, results, "LOT_RETURN_RESTORED");
    }

    /** 采购退货、盘点和调拨按已验证批次追加事实；不允许任意通用调账。 */
    @Override
    @Transactional
    public ApplyResult applyExplicit(ExplicitCommand command) {
        requireSource(command == null ? null : command.source());
        requireLines(command.lines());
        String hash = hash(command);
        ApplyResult replay = replay(command.source(), hash);
        if (replay != null) return replay;
        TrustedPrincipal principal = begin(command.source(), hash);
        Map<String, BigDecimal> expectedBySourceLine = new java.util.LinkedHashMap<>();
        Map<String, String> movementBySourceLine = new java.util.LinkedHashMap<>();
        for (ExplicitLine line : command.lines()) {
            requireLine(line.sourceLineId(), line.skuId(), line.baseUnitId(), line.quantity());
            String movement = explicitMovement(command.movementType(), line.movementType());
            String key = line.sourceLineId() + "|" + line.skuId();
            String previous = movementBySourceLine.putIfAbsent(key, movement);
            if (previous != null && !previous.equals(movement)) {
                throw new ServiceException("LOT-SOURCE-007: 同一仓库总账行不能混用批次移动方向", 409);
            }
            expectedBySourceLine.merge(key, LotInventoryRules.exactQuantity(line.quantity(), "explicitQuantity"),
                BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : expectedBySourceLine.entrySet()) {
            int separator = entry.getKey().lastIndexOf('|');
            String sourceLineId = entry.getKey().substring(0, separator);
            Long skuId = Long.valueOf(entry.getKey().substring(separator + 1));
            GenericMovementView generic = requireGeneric(command.source(), sourceLineId, skuId,
                entry.getValue().setScale(LotInventoryRules.QUANTITY_SCALE));
            if (!movementBySourceLine.get(entry.getKey()).equals(generic.movementType())) {
                throw new ServiceException("LOT-SOURCE-006: 批次移动与仓库总账类型不一致", 409);
            }
        }
        List<AllocationView> results = new ArrayList<>();
        for (ExplicitLine line : command.lines()) {
            String movement = explicitMovement(command.movementType(), line.movementType());
            InventoryRules.requireUlid(line.lotId(), "lotId");
            BigDecimal quantity = LotInventoryRules.exactQuantity(line.quantity(), "explicitQuantity");
            LotView lot = requireLot(principal.tenantId(), line.lotId());
            requireLotScope(command.source(), line.skuId(), line.baseUnitId(), lot);
            requireEnabledPolicy(command.source(), line.skuId());
            results.add(applyToLot(principal, command.source(), line.sourceLineId(), lot, quantity,
                movement, "EXPLICIT", null, null));
        }
        return complete(principal, command.source(), hash, results, "LOT_EXPLICIT_APPLIED");
    }

    /** 调拨目的仓从原发出分配继承批次日期与批号，不接受调用方自报到期日。 */
    @Override
    @Transactional
    public ApplyResult receiveTransfer(TransferReceiveCommand command) {
        requireSource(command == null ? null : command.source());
        InventoryRules.requireUlid(command.dispatchId(), "dispatchId");
        requireLines(command.lines());
        if (command.lines().stream().map(line -> line.receiptLineId() + "|" + line.sourceLotId()).distinct().count()
            != command.lines().size()) {
            throw new ServiceException("LOT-TRANSFER-001: 同一收货行的来源批次必须唯一", 409);
        }
        String hash = hash(command);
        ApplyResult replay = replay(command.source(), hash);
        if (replay != null) return replay;
        TrustedPrincipal principal = begin(command.source(), hash);
        Map<String, BigDecimal> expectedByLine = new java.util.LinkedHashMap<>();
        for (TransferReceiveLine line : command.lines()) {
            requireLine(line.receiptLineId(), line.skuId(), line.baseUnitId(), line.quantity());
            InventoryRules.requireUlid(line.dispatchLineId(), "dispatchLineId");
            InventoryRules.requireUlid(line.sourceLotId(), "sourceLotId");
            expectedByLine.merge(line.receiptLineId() + "|" + line.skuId(),
                LotInventoryRules.exactQuantity(line.quantity(), "transferReceiveQuantity"), BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : expectedByLine.entrySet()) {
            int separator = entry.getKey().lastIndexOf('|');
            String receiptLineId = entry.getKey().substring(0, separator);
            Long skuId = Long.valueOf(entry.getKey().substring(separator + 1));
            GenericMovementView generic = requireGeneric(command.source(), receiptLineId, skuId,
                entry.getValue().setScale(LotInventoryRules.QUANTITY_SCALE));
            if (!"TRANSFER_IN".equals(generic.movementType())) {
                throw new ServiceException("LOT-TRANSFER-002: 调拨收货必须绑定 TRANSFER_IN 总账", 409);
            }
        }
        List<AllocationView> results = new ArrayList<>();
        for (TransferReceiveLine line : command.lines()) {
            BigDecimal quantity = LotInventoryRules.exactQuantity(line.quantity(), "transferReceiveQuantity");
            AllocationView sourceAllocation = mapper.lockTransferableAllocation(principal.tenantId(),
                command.dispatchId(), line.dispatchLineId(), line.sourceLotId(), line.skuId());
            if (sourceAllocation == null || sourceAllocation.quantity().compareTo(quantity) < 0) {
                throw new ServiceException("LOT-TRANSFER-003: 调拨收货超过原发出批次可收数量", 409);
            }
            LotView sourceLot = mapper.findLot(principal.tenantId(), line.sourceLotId());
            if (sourceLot == null || !line.skuId().equals(sourceLot.skuId())
                || !line.baseUnitId().equals(sourceLot.baseUnitId())) {
                throw new ServiceException("LOT-TRANSFER-004: 原发出批次与商品或单位不一致", 409);
            }
            PolicyView policy = requireEnabledPolicy(command.source(), line.skuId());
            LocalDate inheritedExpiry = resolveExpiry(policy, sourceLot.productionDate(), sourceLot.receivedDate(),
                sourceLot.expiryDate());
            if (!inheritedExpiry.equals(sourceLot.expiryDate())) {
                throw new ServiceException("LOT-TRANSFER-005: 目的门店策略会改变原批次到期日", 409);
            }
            ReceiveLine identityLine = new ReceiveLine(line.receiptLineId(), line.skuId(), line.baseUnitId(),
                quantity, sourceLot.supplierLotCode(), sourceLot.internalLotCode(), sourceLot.productionDate(),
                sourceLot.receivedDate(), sourceLot.expiryDate());
            String identityHash = identityHash(command.source(), identityLine, policy, inheritedExpiry);
            LotView destinationLot = mapper.findLotByIdentityHash(principal.tenantId(), command.source().warehouseId(),
                line.skuId(), identityHash);
            if (destinationLot == null) {
                String lotId = ulids.next();
                mapper.insertIdentity(new IdentityWrite(lotId, principal.tenantId(), command.source().storeId(),
                    command.source().warehouseId(), line.skuId(), line.baseUnitId(), sourceLot.supplierLotCode(),
                    sourceLot.internalLotCode(), sourceLot.productionDate(), sourceLot.receivedDate(), inheritedExpiry,
                    policy.policyVersionId(), policy.nearExpiryDays(), identityHash, now()));
                mapper.insertBalanceIfAbsent(new BalanceSeed(principal.tenantId(), lotId, now()));
                destinationLot = requireLot(principal.tenantId(), lotId);
            }
            results.add(applyToLot(principal, command.source(), line.receiptLineId(), destinationLot, quantity,
                "TRANSFER_IN", "EXPLICIT", command.dispatchId(), sourceAllocation.allocationId()));
        }
        return complete(principal, command.source(), hash, results, "LOT_TRANSFER_RECEIVED");
    }

    /** 临期查询使用服务端业务日与可信门店范围，不接受客户端租户。 */
    @Transactional(readOnly = true)
    public List<LotView> findAlerts(Long storeId, String warehouseId, LocalDate businessDate, int limit) {
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        if (storeId == null || storeId <= 0 || businessDate == null || limit < 1 || limit > 500) {
            throw new ServiceException("LOT-QUERY-001: 临期查询参数非法", 400);
        }
        authorization.requireStoreAccess(storeId);
        String tenantId = tenantContext.requireTenantId();
        IndustryBinding binding = industryReadPort.requireCurrentIndustry(storeId);
        if (!LotExpiryRules.COMMUNITY_SUPERMARKET.equals(binding.industry())) return List.of();
        return mapper.findNearExpiry(tenantId, storeId, warehouseId, businessDate, limit).stream()
            .map(lot -> {
                policyReadPort.requireEffective(storeId, lot.skuId(),
                    atBusinessDate(businessDate, binding.zoneId(), binding.businessDayStart()));
                String status = LotExpiryRules.classify(businessDate, lot.expiryDate(), lot.nearExpiryDays());
                return new LotView(lot.lotId(), lot.storeId(), lot.warehouseId(), lot.skuId(), lot.baseUnitId(),
                    lot.supplierLotCode(), lot.internalLotCode(), lot.productionDate(), lot.receivedDate(),
                    lot.expiryDate(), lot.policyVersionId(), lot.nearExpiryDays(), lot.onHandQuantity(), lot.lastLedgerSequence(),
                    status, lot.updatedAt());
            })
            .filter(lot -> "NEAR_EXPIRY".equals(lot.expiryStatus()) || "EXPIRED".equals(lot.expiryStatus()))
            .limit(limit)
            .toList();
    }

    /** 按 FEFO 顺序返回批次投影，状态由服务端业务日重新分类。 */
    @Transactional(readOnly = true)
    public List<LotView> findLots(Long storeId, String warehouseId, Long skuId, LocalDate businessDate,
                                  String status, int limit) {
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        if (storeId == null || storeId <= 0 || skuId == null || skuId <= 0 || businessDate == null
            || limit < 1 || limit > 500) {
            throw new ServiceException("LOT-QUERY-002: 批次查询参数非法", 400);
        }
        authorization.requireStoreAccess(storeId);
        IndustryBinding binding = industryReadPort.requireCurrentIndustry(storeId);
        if (!LotExpiryRules.COMMUNITY_SUPERMARKET.equals(binding.industry())) return List.of();
        policyReadPort.requireEffective(storeId, skuId,
            atBusinessDate(businessDate, binding.zoneId(), binding.businessDayStart()));
        String normalizedStatus = status == null || status.isBlank() ? null : status.strip().toUpperCase();
        if (normalizedStatus != null && !Set.of("AVAILABLE", "NEAR_EXPIRY", "EXPIRED", "DEPLETED", "BLOCKED")
            .contains(normalizedStatus)) {
            throw new ServiceException("LOT-QUERY-003: 批次状态非法", 400);
        }
        return mapper.findLots(tenantContext.requireTenantId(), storeId, warehouseId, skuId, limit).stream()
            .map(lot -> withStatus(lot, businessDate))
            .filter(lot -> normalizedStatus == null || normalizedStatus.equals(lot.expiryStatus()))
            .toList();
    }

    /** 从只追加流水受控重建余额与效期投影，不改写任何历史事实。 */
    @Transactional
    public RebuildResult rebuild(RebuildCommand command) {
        if (command == null) throw new ServiceException("LOT-REBUILD-001: 重建命令缺失", 400);
        InventoryRules.requireUlid(command.commandId(), "commandId");
        InventoryRules.requireUlid(command.warehouseId(), "warehouseId");
        if (command.storeId() == null || command.storeId() <= 0 || command.skuId() == null
            || command.skuId() <= 0 || command.businessDate() == null || command.correlationId() == null
            || command.correlationId().isBlank() || command.correlationId().length() > 96) {
            throw new ServiceException("LOT-REBUILD-002: 重建参数非法", 400);
        }
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireTenantAdministrator();
        authorization.requireStoreAccess(command.storeId());
        requireEnabledPolicy(new CommandSource(command.commandId(), "LOT_REBUILD",
            command.commandId(), command.warehouseId(), command.storeId(), command.businessDate(),
            command.correlationId()), command.skuId());
        String requestHash = InventoryHash.sha256(InventoryHash.canonical(List.of(command.commandId(),
            command.storeId(), command.warehouseId(), command.skuId(), command.businessDate())));
        CommandView existing = mapper.findCommand(principal.tenantId(), command.commandId());
        if (existing != null && !requestHash.equals(existing.requestSha256())) {
            throw new ServiceException("LOT-IDEMPOTENCY-001: 同一事件对应不同批次内容", 409);
        }
        List<LotView> lots = mapper.findLots(principal.tenantId(), command.storeId(), command.warehouseId(),
            command.skuId(), 501);
        if (lots.isEmpty() || lots.size() > 500) {
            throw new ServiceException("LOT-REBUILD-003: 重建批次数必须为 1..500", 409);
        }
        if (existing != null) {
            if (!"APPLIED".equals(existing.status())) throw new ServiceException("LOT-IDEMPOTENCY-002: 原批次命令仍在处理中", 409);
            return rebuildResult(command, lots, false, requestHash);
        }
        mapper.insertCommand(new CommandWrite(command.commandId(), principal.tenantId(), requestHash,
            "LOT_REBUILD", command.commandId(), command.warehouseId(), command.storeId(),
            command.correlationId(), principal.userId(), now()));
        boolean changed = false;
        for (LotView candidate : lots) {
            LotView locked = requireLot(principal.tenantId(), candidate.lotId());
            LedgerProjection aggregate = mapper.aggregateLedger(principal.tenantId(), locked.lotId());
            BigDecimal quantity = aggregate.ledgerQuantity().setScale(LotInventoryRules.QUANTITY_SCALE);
            if (quantity.signum() < 0) throw new ServiceException("LOT-REBUILD-004: 流水聚合为负数", 409);
            boolean lotChanged = quantity.compareTo(locked.onHandQuantity()) != 0
                || aggregate.lastLedgerSequence() != locked.lastLedgerSequence();
            if (lotChanged && mapper.rebuildBalance(new BalanceRebuild(principal.tenantId(), locked.lotId(),
                quantity, aggregate.lastLedgerSequence(), now())) != 1) {
                throw new ServiceException("LOT-REBUILD-005: 批次投影重建并发冲突", 409);
            }
            String expiryStatus = quantity.signum() == 0 ? "DEPLETED" : LotExpiryRules.classify(
                command.businessDate(), locked.expiryDate(), locked.nearExpiryDays());
            mapper.upsertExpiryProjection(new ExpiryProjectionWrite(principal.tenantId(), locked.lotId(),
                expiryStatus, command.businessDate(), locked.nearExpiryDays(), quantity,
                aggregate.lastLedgerSequence(), now()));
            changed |= lotChanged;
        }
        LocalDateTime at = now();
        if (mapper.completeCommand(new CommandApplied(principal.tenantId(), command.commandId(), lots.size(), at)) != 1) {
            throw new ServiceException("LOT-REBUILD-006: 重建命令状态冲突", 409);
        }
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), command.storeId(),
            changed ? "LOT_PROJECTION_REBUILT" : "LOT_PROJECTION_VERIFIED", command.warehouseId(),
            principal.userId(), command.commandId(), command.correlationId(), requestHash,
            "LEDGER_RECOMPUTE", at));
        String payload = json(Map.of("commandId", command.commandId(), "warehouseId", command.warehouseId(),
            "skuId", command.skuId(), "businessDate", command.businessDate().toString(), "lotCount", lots.size(),
            "changed", changed));
        mapper.insertOutbox(new OutboxWrite(ulids.next(), principal.tenantId(), "inventory.lot.rebuilt.v1",
            command.warehouseId(), 1, command.correlationId(), payload, InventoryHash.sha256(payload), at));
        return rebuildResult(command, lots, changed, requestHash);
    }

    private TrustedPrincipal begin(CommandSource source, String hash) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireStoreAccess(source.storeId());
        mapper.insertCommand(new CommandWrite(source.eventId(), principal.tenantId(), hash, source.sourceType(),
            source.sourceId(), source.warehouseId(), source.storeId(), source.correlationId(), principal.userId(), now()));
        return principal;
    }

    private ApplyResult replay(CommandSource source, String hash) {
        CommandView existing = mapper.findCommand(tenantContext.requireTenantId(), source.eventId());
        if (existing == null) return null;
        if (!hash.equals(existing.requestSha256())) {
            throw new ServiceException("LOT-IDEMPOTENCY-001: 同一事件对应不同批次内容", 409);
        }
        if (!"APPLIED".equals(existing.status())) {
            throw new ServiceException("LOT-IDEMPOTENCY-002: 原批次命令仍在处理中", 409);
        }
        return new ApplyResult(existing.eventId(), existing.status(), existing.affectedLines(), hash,
            mapper.findAllocationsByEvent(tenantContext.requireTenantId(), source.eventId()));
    }

    private AllocationView applyToLot(TrustedPrincipal principal, CommandSource source, String sourceLineId,
                                      LotView suppliedLot, BigDecimal positiveQuantity, String movementType,
                                      String allocationType, String originalSourceId, String originalSourceLineId) {
        LotView lot = requireLot(principal.tenantId(), suppliedLot.lotId());
        BigDecimal delta = OUTBOUND.contains(movementType) ? positiveQuantity.negate() : positiveQuantity;
        BigDecimal after = lot.onHandQuantity().add(delta).setScale(LotInventoryRules.QUANTITY_SCALE);
        if (after.signum() < 0) throw new ServiceException("LOT-BALANCE-003: 指定批次余额不足", 409);
        long sequence = lot.lastLedgerSequence() + 1;
        LocalDateTime at = now();
        mapper.insertLedger(new LedgerWrite(ulids.next(), principal.tenantId(), lot.lotId(), sequence,
            lot.onHandQuantity(), delta, after, movementType, source.sourceType(), source.sourceId(), sourceLineId,
            source.eventId(), source.businessDate(), principal.userId(), source.correlationId(), at));
        if (mapper.updateBalance(new BalanceUpdate(principal.tenantId(), lot.lotId(), after, sequence,
            sequence - 1, at)) != 1) {
            throw new ServiceException("LOT-BALANCE-004: 批次余额并发版本冲突", 409);
        }
        String status = after.signum() == 0 ? "DEPLETED" : LotExpiryRules.classify(source.businessDate(),
            lot.expiryDate(), lot.nearExpiryDays());
        mapper.upsertExpiryProjection(new ExpiryProjectionWrite(principal.tenantId(), lot.lotId(), status,
            source.businessDate(), lot.nearExpiryDays(), after, sequence, at));
        String allocationId = ulids.next();
        mapper.insertAllocation(new AllocationWrite(allocationId, principal.tenantId(), allocationType,
            source.sourceId(), sourceLineId, originalSourceId, originalSourceLineId, lot.lotId(), lot.skuId(),
            positiveQuantity, lot.policyVersionId(), lot.expiryDate(), source.eventId(), at));
        return new AllocationView(allocationId, source.sourceId(), sourceLineId, lot.lotId(), lot.skuId(),
            positiveQuantity, allocationType, lot.policyVersionId(), lot.expiryDate());
    }

    private ApplyResult complete(TrustedPrincipal principal, CommandSource source, String hash,
                                 List<AllocationView> allocations, String action) {
        LocalDateTime at = now();
        if (allocations.isEmpty() || mapper.completeCommand(new CommandApplied(principal.tenantId(), source.eventId(),
            allocations.size(), at)) != 1) {
            throw new ServiceException("LOT-COMMAND-001: 批次命令未产生事实或状态冲突", 409);
        }
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), source.storeId(), action,
            source.sourceId(), principal.userId(), source.eventId(), source.correlationId(), hash, "OWNER_APPLIED", at));
        String payload = json(Map.of("sourceEventId", source.eventId(), "sourceType", source.sourceType(),
            "sourceId", source.sourceId(), "warehouseId", source.warehouseId(), "businessDate",
            source.businessDate().toString(), "allocations", allocations));
        String payloadHash = InventoryHash.sha256(payload);
        mapper.insertOutbox(new OutboxWrite(ulids.next(), principal.tenantId(), eventType(action),
            source.sourceId(), 1, source.correlationId(), payload, payloadHash, at));
        return new ApplyResult(source.eventId(), "APPLIED", allocations.size(), hash, allocations);
    }

    private GenericMovementView requireGeneric(CommandSource source, String sourceLineId, Long skuId,
                                               BigDecimal expected) {
        GenericMovementView generic = mapper.findGenericMovement(tenantContext.requireTenantId(), source.eventId(),
            sourceLineId, source.warehouseId(), skuId);
        if (generic == null || generic.absoluteQuantity() == null
            || generic.absoluteQuantity().setScale(LotInventoryRules.QUANTITY_SCALE).compareTo(expected) != 0) {
            throw new ServiceException("LOT-SOURCE-001: 批次数量与仓库总账来源事实不守恒", 409);
        }
        return generic;
    }

    private PolicyView requireEnabledPolicy(CommandSource source, Long skuId) {
        IndustryBinding binding = industryReadPort.requireCurrentIndustry(source.storeId());
        if (!LotExpiryRules.COMMUNITY_SUPERMARKET.equals(binding.industry())) {
            throw new ServiceException("LOT-POLICY-001: 当前门店商品未启用社区超市批次能力", 409);
        }
        PolicyView policy = policyReadPort.requireEffective(source.storeId(), skuId,
            atBusinessDate(source.businessDate(), binding.zoneId(), binding.businessDayStart()));
        if (!policy.enabled() || !LotExpiryRules.COMMUNITY_SUPERMARKET.equals(policy.industry())) {
            throw new ServiceException("LOT-POLICY-001: 当前门店商品未启用社区超市批次能力", 409);
        }
        return policy;
    }

    private static LocalDate resolveExpiry(PolicyView policy, LocalDate production, LocalDate received,
                                           LocalDate explicitExpiry) {
        return LotExpiryRules.resolveExpiry(new PolicySpec(policy.policyVersionId(), policy.storeId(), policy.skuId(),
            policy.enabled(), policy.expiryBasis(), policy.shelfLifeDays(), policy.nearExpiryDays(),
            policy.effectiveFrom()), production, received, explicitExpiry);
    }

    private LotView requireLot(String tenantId, String lotId) {
        LotView result = mapper.lockLot(tenantId, lotId);
        if (result == null) throw new ServiceException("LOT-NOT-FOUND: 批次不存在或不属于可信租户", 404);
        return result;
    }

    private static void requireLotScope(CommandSource source, Long skuId, Long unitId, LotView lot) {
        if (!source.storeId().equals(lot.storeId()) || !source.warehouseId().equals(lot.warehouseId())
            || !skuId.equals(lot.skuId()) || !unitId.equals(lot.baseUnitId())) {
            throw new ServiceException("LOT-SCOPE-001: 批次与门店、仓库、商品或单位不一致", 409);
        }
    }

    private static void requireSource(CommandSource source) {
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

    private static void requireLine(String sourceLineId, Long skuId, Long unitId, BigDecimal quantity) {
        InventoryRules.requireUlid(sourceLineId, "sourceLineId");
        if (skuId == null || skuId <= 0 || unitId == null || unitId <= 0) {
            throw new ServiceException("LOT-LINE-001: SKU 或基础单位非法", 400);
        }
        LotInventoryRules.exactQuantity(quantity, "quantity");
    }

    private static void requireLines(List<?> lines) {
        if (lines == null || lines.isEmpty() || lines.size() > 500) {
            throw new ServiceException("LOT-LINE-002: 批次命令行数必须为 1..500", 400);
        }
    }

    private static String normalizeMovement(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }

    private static String explicitMovement(String commandMovement, String lineMovement) {
        String movement = normalizeMovement(lineMovement == null || lineMovement.isBlank()
            ? commandMovement : lineMovement);
        if (!INBOUND.contains(movement) && !OUTBOUND.contains(movement) || "SALE_OUT".equals(movement)
            || "SALE_RETURN_IN".equals(movement)) {
            throw new ServiceException("LOT-SOURCE-005: 显式批次移动类型未准入", 409);
        }
        return movement;
    }

    private static String eventType(String action) {
        return switch (action) {
            case "LOT_RECEIVED" -> "inventory.lot.received.v1";
            case "LOT_SALE_ALLOCATED" -> "inventory.lot.allocated.v1";
            case "LOT_RETURN_RESTORED" -> "inventory.lot.returned.v1";
            case "LOT_EXPLICIT_APPLIED" -> "inventory.lot.moved.v1";
            case "LOT_TRANSFER_RECEIVED" -> "inventory.lot.transfer-received.v1";
            default -> throw new ServiceException("LOT-EVENT-002: 未知批次事件动作", 500);
        };
    }

    private static String nullableCode(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 96) throw new ServiceException("LOT-CODE-001: 供应商批号过长", 400);
        return normalized;
    }

    private static String normalizedCode(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.strip();
        if (normalized.length() > 96) throw new ServiceException("LOT-CODE-002: 内部批号过长", 400);
        return normalized;
    }

    private static String identityHash(CommandSource source, ReceiveLine line, PolicyView policy,
                                       LocalDate expiry) {
        return InventoryHash.sha256(InventoryHash.canonical(java.util.Arrays.asList(source.warehouseId(), line.skuId(),
            line.baseUnitId(), nullableCode(line.supplierLotCode()), normalizedCode(line.internalLotCode(),
                line.sourceLineId()), line.productionDate(), line.receivedDate(), expiry, policy.policyVersionId())));
    }

    private static String hash(Object command) {
        return InventoryHash.sha256(String.valueOf(command));
    }

    private RebuildResult rebuildResult(RebuildCommand command, List<LotView> lots, boolean changed,
                                        String requestHash) {
        BigDecimal ledger = BigDecimal.ZERO.setScale(LotInventoryRules.QUANTITY_SCALE);
        BigDecimal projected = BigDecimal.ZERO.setScale(LotInventoryRules.QUANTITY_SCALE);
        String tenantId = tenantContext.requireTenantId();
        for (LotView lot : lots) {
            LedgerProjection aggregate = mapper.aggregateLedger(tenantId, lot.lotId());
            ledger = ledger.add(aggregate.ledgerQuantity()).setScale(LotInventoryRules.QUANTITY_SCALE);
            LotView current = mapper.findLot(tenantId, lot.lotId());
            projected = projected.add(current.onHandQuantity()).setScale(LotInventoryRules.QUANTITY_SCALE);
        }
        return new RebuildResult(command.commandId(), lots.size(), ledger, projected, changed, requestHash);
    }

    private static LotView withStatus(LotView lot, LocalDate businessDate) {
        String value = lot.onHandQuantity().signum() == 0 ? "DEPLETED"
            : LotExpiryRules.classify(businessDate, lot.expiryDate(), lot.nearExpiryDays());
        return new LotView(lot.lotId(), lot.storeId(), lot.warehouseId(), lot.skuId(), lot.baseUnitId(),
            lot.supplierLotCode(), lot.internalLotCode(), lot.productionDate(), lot.receivedDate(), lot.expiryDate(),
            lot.policyVersionId(), lot.nearExpiryDays(), lot.onHandQuantity(), lot.lastLedgerSequence(), value,
            lot.updatedAt());
    }

    private static Instant atBusinessDate(LocalDate value, String zoneId, java.time.LocalTime businessDayStart) {
        try {
            if (businessDayStart == null) throw new IllegalArgumentException("businessDayStart");
            return value.atTime(businessDayStart).atZone(ZoneId.of(zoneId)).toInstant();
        } catch (RuntimeException exception) {
            throw new ServiceException("LOT-DATE-003: 门店业务时区或业务日起点非法", 409);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("LOT-EVENT-001: 批次事件序列化失败", 500);
        }
    }
}
