package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.PolicyView;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Approve;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.LotAdjustment;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Create;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.RecordCount;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Review;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Submit;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Detail;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Head;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Line;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovementLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitLine;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.inventory.domain.StocktakeRules;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.JournalWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.AdjustmentWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.CountWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.HeadStatusUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.HeadWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineCountUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineCutoffUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.StocktakeMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态盘点应用服务。
 *
 * <p>提交时锁定最新库存投影作为截止账面；审批只调用库存 Owner 追加差异流水。</p>
 */
@Service
@RequiredArgsConstructor
public class StocktakeService {

    private final StocktakeMapper mapper;
    private final InventoryMapper inventoryMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final InventoryCatalogSnapshotPort catalogPort;
    private final AuthoritativeInventoryMovementPort movementPort;
    private final AuthoritativeLotMovementPort lotMovementPort;
    private final StoreService storeService;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 建立所选 SKU 的动态盘点快照。 */
    @Transactional
    public Detail create(Create command) {
        InventoryRules.requireUlid(command.stocktakeId(), "stocktakeId");
        InventoryRules.requireUlid(command.warehouseId(), "warehouseId");
        requireCorrelation(command.correlationId());
        if (command.skuIds().isEmpty() || command.skuIds().size() > 500
            || new HashSet<>(command.skuIds()).size() != command.skuIds().size()) {
            throw new ServiceException("INV-STK-008: 盘点 SKU 必须唯一且为1至500项", 409);
        }
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        if (mapper.findHead(principal.tenantId(), command.stocktakeId()) != null) {
            return detail(command.stocktakeId());
        }
        PolicyView policy = inventoryMapper.findEffectivePolicyByWarehouse(principal.tenantId(),
            command.warehouseId(), now());
        if (policy == null) throw new ServiceException("INV-POLICY-003: 门店仓缺少已生效库存策略", 409);
        authorizationService.requireStoreAccess(policy.storeId());
        BigDecimal threshold = StocktakeRules.threshold(command.recountThreshold());
        LocalDateTime at = now();
        mapper.insertHead(new HeadWrite(command.stocktakeId(), principal.tenantId(), policy.storeId(),
            command.warehouseId(), command.blindCount(), threshold, command.correlationId(),
            principal.userId(), at, at));
        for (Long skuId : command.skuIds().stream().sorted().toList()) {
            SkuUnitSnapshot unit = catalogPort.requirePrimaryUnit(skuId);
            String dimension = InventoryHash.dimension(principal.tenantId(), command.warehouseId(), skuId);
            inventoryMapper.insertBalanceIfAbsent(new BalanceSeed(principal.tenantId(), dimension,
                command.warehouseId(), skuId));
            BalanceView balance = inventoryMapper.lockBalance(principal.tenantId(), dimension);
            mapper.insertLine(new LineWrite(ulids.next(), principal.tenantId(), command.stocktakeId(),
                dimension, command.warehouseId(), skuId, unit.unitId(), balance.onHandQuantity(),
                balance.lastLedgerSequence(), at));
        }
        audit(principal, policy.storeId(), "STOCKTAKE_CREATED", command.stocktakeId(),
            command.stocktakeId(), command.correlationId(), null, "COUNTING", "SNAPSHOT_CREATED", at);
        return detail(command.stocktakeId());
    }

    /** 追加一条不可变计数修订，并更新盘点行的最新投影。 */
    @Transactional
    public Detail recordCount(RecordCount command) {
        requireStocktakeAndLineIds(command.stocktakeId(), command.lineId());
        InventoryRules.requireUlid(command.countId(), "countId");
        requireCorrelation(command.correlationId());
        requireText(command.deviceId(), 64, "INV-STK-009: 计数设备标识非法");
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        Head head = requireLockedHead(principal.tenantId(), command.stocktakeId());
        authorizationService.requireStoreAccess(head.storeId());
        StocktakeRules.requireCountable(head.status());
        Line line = mapper.lockLine(principal.tenantId(), command.stocktakeId(), command.lineId());
        if (line == null) throw new ServiceException("INV-STK-010: 盘点行不存在或不可见", 404);
        if (line.countRevision() > 0 && (command.reason() == null || command.reason().isBlank())) {
            throw new ServiceException("INV-STK-011: 复盘修订必须填写原因", 409);
        }
        BigDecimal quantity = StocktakeRules.countQuantity(command.countedQuantity());
        LocalDateTime at = now();
        mapper.insertCount(new CountWrite(command.countId(), principal.tenantId(), command.stocktakeId(),
            command.lineId(), line.countRevision() + 1, quantity, principal.userId(), command.deviceId(),
            normalizeReason(command.reason()), command.correlationId(), at));
        if (mapper.updateLineCount(new LineCountUpdate(principal.tenantId(), command.stocktakeId(),
            command.lineId(), quantity, line.countRevision(), principal.userId(), at)) != 1) {
            throw new ServiceException("INV-STK-012: 盘点行计数版本冲突", 409);
        }
        audit(principal, head.storeId(), "STOCKTAKE_COUNT_RECORDED", command.stocktakeId(),
            command.countId(), command.correlationId(), line.countedQuantity(), quantity,
            line.countRevision() == 0 ? "FIRST_COUNT" : "RECOUNT", at);
        return detail(command.stocktakeId());
    }

    /** 锁定每个库存维度的最新账面数，计算差异并判断是否必须复盘。 */
    @Transactional
    public Detail submit(Submit command) {
        InventoryRules.requireUlid(command.stocktakeId(), "stocktakeId");
        requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        Head head = requireLockedHead(principal.tenantId(), command.stocktakeId());
        authorizationService.requireStoreAccess(head.storeId());
        StocktakeRules.requireSubmittable(head.status());
        if (mapper.countUncounted(principal.tenantId(), command.stocktakeId()) != 0) {
            throw new ServiceException("INV-STK-013: 仍有未计数盘点行", 409);
        }
        LocalDateTime at = now();
        boolean recount = false;
        for (Line line : mapper.findLines(principal.tenantId(), command.stocktakeId())) {
            BalanceView balance = inventoryMapper.lockBalance(principal.tenantId(), line.dimensionKey());
            if (balance == null) throw new ServiceException("INV-STK-014: 截止账面库存不存在", 409);
            BigDecimal variance = StocktakeRules.variance(line.countedQuantity(), balance.onHandQuantity());
            recount |= StocktakeRules.requiresRecount(variance, head.recountThreshold(), line.countRevision());
            mapper.updateLineCutoff(new LineCutoffUpdate(principal.tenantId(), command.stocktakeId(),
                line.lineId(), balance.onHandQuantity(), balance.lastLedgerSequence(), variance, at));
        }
        String next = recount ? "RECOUNT_REQUIRED" : "PENDING_REVIEW";
        changeStatus(head, next, null, null, at, null, null, at);
        audit(principal, head.storeId(), "STOCKTAKE_SUBMITTED", command.stocktakeId(), command.stocktakeId(),
            command.correlationId(), head.status(), next, recount ? "THRESHOLD_RECOUNT" : "READY_REVIEW", at);
        return detail(command.stocktakeId());
    }

    /** 复核接受差异，或退回复盘；复核人不得是创建人或任一计数人。 */
    @Transactional
    public Detail review(Review command) {
        InventoryRules.requireUlid(command.stocktakeId(), "stocktakeId");
        requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        Head head = requireLockedHead(principal.tenantId(), command.stocktakeId());
        authorizationService.requireStoreAccess(head.storeId());
        StocktakeRules.requireReviewable(head.status());
        if (principal.userId().equals(head.creatorUserId())
            || mapper.countByUser(principal.tenantId(), command.stocktakeId(), principal.userId()) > 0) {
            throw new ServiceException("INV-STK-015: 复核人不得参与创建或计数", 409);
        }
        String next;
        String reason;
        if ("ACCEPT".equals(command.decision())) {
            next = "REVIEWED";
            reason = "REVIEW_ACCEPTED";
        } else if ("RECOUNT".equals(command.decision())) {
            requireText(command.reason(), 256, "INV-STK-016: 退回复盘必须填写原因");
            next = "RECOUNT_REQUIRED";
            reason = "REVIEW_RECOUNT";
        } else {
            throw new ServiceException("INV-STK-017: 复核决定非法", 409);
        }
        LocalDateTime at = now();
        changeStatus(head, next, principal.userId(), null, null, null, null, at);
        audit(principal, head.storeId(), "STOCKTAKE_REVIEWED", command.stocktakeId(), command.stocktakeId(),
            command.correlationId(), head.status(), next, reason, at);
        return detail(command.stocktakeId());
    }

    /** 审批后通过库存 Owner 原子追加盘盈/盘亏流水，并封存盘点。 */
    @Transactional
    public Detail approve(Approve command) {
        InventoryRules.requireUlid(command.stocktakeId(), "stocktakeId");
        InventoryRules.requireUlid(command.eventId(), "eventId");
        requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        Head head = requireLockedHead(principal.tenantId(), command.stocktakeId());
        authorizationService.requireStoreAccess(head.storeId());
        if ("POSTED".equals(head.status())) return detail(command.stocktakeId());
        StocktakeRules.requireApprovable(head.status());
        StocktakeRules.requireSegregatedActors(head.creatorUserId(), head.reviewerUserId(), principal.userId());
        if (mapper.countByUser(principal.tenantId(), command.stocktakeId(), principal.userId()) > 0) {
            throw new ServiceException("INV-STK-018: 审批人不得参与计数", 409);
        }
        List<Line> lines = mapper.findLines(principal.tenantId(), command.stocktakeId());
        List<OwnedMovementLine> movements = new ArrayList<>();
        for (Line line : lines) {
            if (line.varianceQuantity() == null) {
                throw new ServiceException("INV-STK-019: 盘点差异尚未冻结", 409);
            }
            int sign = line.varianceQuantity().signum();
            if (sign != 0) {
                movements.add(new OwnedMovementLine(line.lineId(), line.skuId(), line.baseUnitId(),
                    line.varianceQuantity().abs(), sign > 0 ? MovementType.STOCKTAKE_GAIN : MovementType.STOCKTAKE_LOSS));
            }
        }
        LocalDateTime at = now();
        LocalDate businessDate = storeService.businessDate(head.storeId(), clock.instant()).businessDate();
        ApplyResult result = null;
        if (!movements.isEmpty()) {
            result = movementPort.applyOwnedMovement(new OwnedMovement(command.eventId(), "STOCKTAKE",
                command.stocktakeId(), head.warehouseId(), head.storeId(), businessDate,
                command.correlationId(), movements));
            applyLotAdjustments(command, head, lines, movements, businessDate);
            for (OwnedMovementLine movement : movements) {
                Line line = lines.stream().filter(value -> value.lineId().equals(movement.sourceLineId())).findFirst()
                    .orElseThrow();
                mapper.insertAdjustment(new AdjustmentWrite(ulids.next(), principal.tenantId(),
                    command.stocktakeId(), line.lineId(), command.eventId(), movement.movementType().name(),
                    movement.quantity(), line.varianceQuantity(), at));
            }
        }
        changeStatus(head, "POSTED", null, principal.userId(), null, at, command.eventId(), at);
        audit(principal, head.storeId(), "STOCKTAKE_POSTED", command.stocktakeId(), command.eventId(),
            command.correlationId(), "REVIEWED", "POSTED", movements.isEmpty() ? "ZERO_VARIANCE" : "LEDGER_APPENDED", at);
        writePostedEvent(principal.tenantId(), head, command, movements.size(), result, at);
        return detail(command.stocktakeId());
    }

    /** 对启用批次的盘盈盘亏逐总账行校验拆分，支持同一 SKU 差异跨多个批次。 */
    private void applyLotAdjustments(Approve command, Head head, List<Line> lines,
                                     List<OwnedMovementLine> movements, LocalDate businessDate) {
        Map<String, List<LotAdjustment>> byLine = command.lotAdjustments().stream()
            .collect(java.util.stream.Collectors.groupingBy(LotAdjustment::stocktakeLineId));
        List<ExplicitLine> explicitLines = new ArrayList<>();
        for (OwnedMovementLine movement : movements) {
            boolean required = lotMovementPort.requiresLotTracking(head.storeId(), movement.skuId(), businessDate);
            List<LotAdjustment> splits = byLine.remove(movement.sourceLineId());
            if (!required) {
                if (splits != null && !splits.isEmpty()) {
                    throw new ServiceException("INV-STK-LOT-001: 未启用批次的盘点行禁止提交批次拆分", 409);
                }
                continue;
            }
            if (splits == null || splits.isEmpty()) {
                throw new ServiceException("INV-STK-LOT-002: 已启用批次的盘点差异缺少批次拆分", 409);
            }
            BigDecimal total = splits.stream().map(LotAdjustment::baseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(movement.quantity()) != 0
                || splits.stream().anyMatch(split -> !movement.movementType().name().equals(split.movementType()))) {
                throw new ServiceException("INV-STK-LOT-003: 批次拆分数量或方向与冻结盘点差异不一致", 409);
            }
            splits.forEach(split -> explicitLines.add(new ExplicitLine(split.stocktakeLineId(), split.lotId(),
                movement.skuId(), movement.baseUnitId(), split.baseQuantity(), split.movementType())));
        }
        if (!byLine.isEmpty()) throw new ServiceException("INV-STK-LOT-004: 批次拆分引用了未知盘点差异行", 409);
        if (!explicitLines.isEmpty()) {
            lotMovementPort.applyExplicit(new ExplicitCommand(new CommandSource(command.eventId(), "STOCKTAKE",
                command.stocktakeId(), head.warehouseId(), head.storeId(), businessDate,
                command.correlationId()), "MIXED", explicitLines));
        }
    }

    @Transactional(readOnly = true)
    public Detail detail(String stocktakeId) {
        InventoryRules.requireUlid(stocktakeId, "stocktakeId");
        String tenantId = tenantContext.requireTenantId();
        Head head = mapper.findHead(tenantId, stocktakeId);
        if (head == null) throw new ServiceException("INV-STK-020: 盘点单不存在或不可见", 404);
        authorizationService.requireStoreAccess(head.storeId());
        List<Line> lines = mapper.findLines(tenantId, stocktakeId);
        if (head.blindCount() && ("COUNTING".equals(head.status()) || "RECOUNT_REQUIRED".equals(head.status()))) {
            lines = lines.stream().map(line -> new Line(line.lineId(), line.stocktakeId(), line.dimensionKey(),
                line.warehouseId(), line.skuId(), line.baseUnitId(), null, 0, line.countedQuantity(),
                null, 0, null, line.countRevision(), line.lastCounterUserId())).toList();
        }
        return new Detail(head, lines);
    }

    private Head requireLockedHead(String tenantId, String stocktakeId) {
        Head head = mapper.lockHead(tenantId, stocktakeId);
        if (head == null) throw new ServiceException("INV-STK-020: 盘点单不存在或不可见", 404);
        return head;
    }

    private void changeStatus(Head head, String next, Long reviewer, Long approver,
                              LocalDateTime cutoffAt, LocalDateTime postedAt,
                              String eventId, LocalDateTime at) {
        if (mapper.updateHeadStatus(new HeadStatusUpdate(tenantContext.requireTenantId(), head.stocktakeId(),
            head.status(), next, head.version(), reviewer, approver, cutoffAt, postedAt, eventId, at)) != 1) {
            throw new ServiceException("INV-STK-021: 盘点状态或版本冲突", 409);
        }
    }

    private void audit(TrustedPrincipal principal, Long storeId, String action, String stocktakeId,
                       String commandId, String correlationId, Object before, Object after,
                       String reason, LocalDateTime at) {
        String beforeText = before == null ? null : String.valueOf(before);
        String afterText = after == null ? null : String.valueOf(after);
        String hash = InventoryHash.sha256(InventoryHash.canonical(List.of(action, stocktakeId,
            String.valueOf(beforeText), String.valueOf(afterText), reason)));
        inventoryMapper.insertAudit(new JournalWrite(ulids.next(), principal.tenantId(), storeId, action,
            "STOCKTAKE", stocktakeId, principal.userId(), commandId, correlationId,
            beforeText, afterText, hash, reason, at));
    }

    private void writePostedEvent(String tenantId, Head head, Approve command, int adjustedLines,
                                  ApplyResult result, LocalDateTime at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("stocktakeId", command.stocktakeId());
        payload.put("warehouseId", head.warehouseId());
        payload.put("adjustmentEventId", command.eventId());
        payload.put("adjustedLines", adjustedLines);
        payload.put("duplicateInventoryCommand", result != null && result.duplicate());
        payload.put("correlationId", command.correlationId());
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("INV-STK-022: 盘点事件序列化失败", 500);
        }
        inventoryMapper.insertOutbox(new OutboxWrite(ulids.next(), tenantId, "inventory.stocktake.posted.v1",
            command.stocktakeId(), head.version() + 1, command.correlationId(), json,
            InventoryHash.sha256(json), at));
    }

    private void requireStocktakeAndLineIds(String stocktakeId, String lineId) {
        InventoryRules.requireUlid(stocktakeId, "stocktakeId");
        InventoryRules.requireUlid(lineId, "lineId");
    }

    private void requireCorrelation(String value) {
        requireText(value, 96, "INV-INPUT-001: correlationId 格式非法");
    }

    private void requireText(String value, int max, String message) {
        if (value == null || value.isBlank() || value.length() > max) throw new ServiceException(message, 409);
    }

    private String normalizeReason(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 256) throw new ServiceException("INV-STK-023: 原因说明过长", 409);
        return value.trim();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }
}
