package com.jingshanghui.pos.transfer.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovementLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.TransferReceiveCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.TransferReceiveLine;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.*;
import com.jingshanghui.pos.transfer.application.model.TransferViews.*;
import com.jingshanghui.pos.transfer.domain.TransferHash;
import com.jingshanghui.pos.transfer.domain.TransferRules;
import com.jingshanghui.pos.transfer.domain.TransferStates.Status;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.*;
import com.jingshanghui.pos.transfer.infrastructure.persistence.mapper.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基础仓间调拨应用服务。
 *
 * <p>调拨 Owner 先持久化发出/收货权威事实，再在同一事务调用库存 Owner；成本由库存 Owner
 * 同事务委托成本 Owner 追加。任何一步失败均整体回滚，禁止直接修改库存或成本余额。</p>
 */
@Service
@RequiredArgsConstructor
public class TransferService {
    private static final BigDecimal ZERO = new BigDecimal("0.000000");

    private final TransferMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final InventoryCatalogSnapshotPort catalogPort;
    private final AuthoritativeInventoryMovementPort movementPort;
    private final AuthoritativeLotMovementPort lotMovementPort;
    private final StoreService storeService;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 创建不产生数量或成本效果的调拨草稿，并冻结基础单位换算。 */
    @Transactional
    public TransferDetail create(CreateTransfer command) {
        validateCreate(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.sourceStoreId());
        authorizationService.requireStoreAccess(command.destinationStoreId());
        String requestHash = hashCreate(command);
        TransferHead existing = mapper.findOrder(principal.tenantId(), command.transferId());
        if (existing != null) {
            if (!requestHash.equals(mapper.findOrderRequestHash(principal.tenantId(), command.transferId()))) {
                throw new ServiceException("TRF-IDEM-001: 相同 transferId 对应不同内容", 409);
            }
            return detail(command.transferId());
        }
        LocalDateTime at = now();
        try {
            mapper.insertOrder(new OrderWrite(command.transferId(), principal.tenantId(), command.sourceStoreId(),
                command.sourceWarehouseId(), command.destinationStoreId(), command.destinationWarehouseId(),
                requestHash, TransferRules.text(command.reason(), 256, "TRF-INPUT-002"), command.correlationId(),
                principal.userId(), at));
        } catch (DuplicateKeyException exception) {
            if (requestHash.equals(mapper.findOrderRequestHash(principal.tenantId(), command.transferId()))) {
                return detail(command.transferId());
            }
            throw new ServiceException("TRF-IDEM-001: 相同 transferId 对应不同内容", 409);
        }
        for (CreateLine line : command.lines().stream().sorted(Comparator.comparing(CreateLine::transferLineId)).toList()) {
            TransferRules.ulid(line.transferLineId(), "transferLineId");
            SkuUnitSnapshot unit = catalogPort.requireUnit(line.skuId(), line.unitId());
            BigDecimal input = TransferRules.quantity(line.requestedQuantity(), "requestedQuantity");
            BigDecimal base = toBase(input, unit);
            mapper.insertLine(new LineWrite(line.transferLineId(), principal.tenantId(), command.transferId(),
                line.skuId(), unit.unitId(), unit.numerator(), unit.denominator(), input,
                unit.baseUnitId(), base, at));
        }
        audit(principal, command.sourceStoreId(), "TRANSFER_CREATED", command.transferId(), command.transferId(),
            command.correlationId(), null, Status.DRAFT.name(), command.reason(), at);
        event(principal.tenantId(), "inventory.transfer.created.v1", command.transferId(), 0,
            command.correlationId(), Map.of("transferId", command.transferId(), "state", Status.DRAFT.name(),
                "sourceWarehouseId", command.sourceWarehouseId(),
                "destinationWarehouseId", command.destinationWarehouseId(), "lineCount", command.lines().size()), at);
        return detail(command.transferId());
    }

    @Transactional
    public TransferDetail submit(StateCommand command) {
        return transition(command, Status.DRAFT, Status.SUBMITTED, "TRANSFER_SUBMITTED", false);
    }

    @Transactional
    public TransferDetail approve(StateCommand command) {
        return transition(command, Status.SUBMITTED, Status.APPROVED, "TRANSFER_APPROVED", true);
    }

    /** 全量发出并在同一事务追加来源仓 TRANSFER_OUT、成本快照和在途流水。 */
    @Transactional
    public TransferDetail dispatch(DispatchTransfer command) {
        validateDispatch(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TransferHead head = requireLocked(principal.tenantId(), command.transferId());
        authorizeBoth(head);
        String hash = hashDispatch(command);
        if (beginCommand(principal.tenantId(), command.eventId(), command.transferId(), "DISPATCH", hash)) {
            return detail(command.transferId());
        }
        TransferRules.transition(Status.valueOf(head.status()), Status.APPROVED, Status.IN_TRANSIT);
        requireVersion(head, command.expectedVersion());
        if (mapper.findDispatchByTransfer(principal.tenantId(), command.transferId()) != null) {
            throw new ServiceException("TRF-DISPATCH-001: 调拨已存在发出事实", 409);
        }
        LocalDateTime at = now();
        LocalDate businessDate = storeService.businessDate(head.sourceStoreId(), clock.instant()).businessDate();
        mapper.insertDispatch(new DispatchWrite(command.dispatchId(), principal.tenantId(), head.transferId(),
            command.eventId(), businessDate, command.correlationId(), at));
        List<OwnedMovementLine> movements = new ArrayList<>();
        Map<String, OwnedMovementLine> movementByTransferLine = new LinkedHashMap<>();
        for (TransferLine line : mapper.findLines(principal.tenantId(), head.transferId())) {
            String dispatchLineId = ulids.next();
            mapper.insertDispatchLine(new DispatchLineWrite(dispatchLineId, principal.tenantId(), command.dispatchId(),
                line.transferLineId(), line.skuId(), line.baseUnitId(), line.requestedQuantity(), at));
            progress(principal.tenantId(), line.transferLineId(), line.requestedQuantity(), ZERO, ZERO, at);
            mapper.insertTransit(new TransitWrite(ulids.next(), principal.tenantId(), head.transferId(),
                line.transferLineId(), "DISPATCHED", dispatchLineId, line.requestedQuantity(), null, businessDate,
                command.correlationId(), at));
            OwnedMovementLine movement = new OwnedMovementLine(dispatchLineId, line.skuId(), line.baseUnitId(),
                line.requestedQuantity(), MovementType.TRANSFER_OUT);
            movements.add(movement);
            movementByTransferLine.put(line.transferLineId(), movement);
        }
        updateStatus(head, Status.IN_TRANSIT, null, null, at, null, at);
        movementPort.applyOwnedMovement(new OwnedMovement(command.eventId(), "TRANSFER_DISPATCH", command.dispatchId(),
            head.sourceWarehouseId(), head.sourceStoreId(), businessDate, command.correlationId(), movements));
        applyDispatchLots(command, head, movementByTransferLine, businessDate);
        applied(principal.tenantId(), command.eventId(), at);
        audit(principal, head.sourceStoreId(), "TRANSFER_DISPATCHED", head.transferId(), command.eventId(),
            command.correlationId(), head.status(), Status.IN_TRANSIT.name(), "SOURCE_OWNER_POSTED", at);
        event(principal.tenantId(), "inventory.transfer.dispatched.v1", head.transferId(), head.version() + 1,
            command.correlationId(), Map.of("transferId", head.transferId(), "dispatchId", command.dispatchId(),
                "sourceEventId", command.eventId(), "state", Status.IN_TRANSIT.name(),
                "sourceWarehouseId", head.sourceWarehouseId(),
                "destinationWarehouseId", head.destinationWarehouseId(), "businessDate", businessDate.toString(),
                "lineCount", movements.size()), at);
        return detail(head.transferId());
    }

    /** 支持多次部分收货；目的仓成本只从原发出成本快照继承。 */
    @Transactional
    public TransferDetail receive(ReceiveTransfer command) {
        validateReceive(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TransferHead head = requireLocked(principal.tenantId(), command.transferId());
        authorizeBoth(head);
        String hash = hashReceive(command);
        if (beginCommand(principal.tenantId(), command.eventId(), command.transferId(), "RECEIVE", hash)) {
            return detail(command.transferId());
        }
        Status current = Status.valueOf(head.status());
        TransferRules.receivable(current);
        requireVersion(head, command.expectedVersion());
        if (mapper.findReceipt(principal.tenantId(), command.receiptId()) != null) {
            throw new ServiceException("TRF-RECEIPT-001: receiptId 已被占用", 409);
        }
        List<DispatchLine> dispatched = mapper.findDispatchLines(principal.tenantId(), head.transferId());
        Map<String, DispatchLine> byLine = new HashMap<>();
        dispatched.forEach(value -> byLine.put(value.transferLineId(), value));
        LocalDateTime at = now();
        LocalDate businessDate = storeService.businessDate(head.destinationStoreId(), clock.instant()).businessDate();
        mapper.insertReceipt(new ReceiptWrite(command.receiptId(), principal.tenantId(), head.transferId(),
            command.eventId(), command.finalReceipt(), businessDate, command.correlationId(), at));
        List<OwnedMovementLine> movements = new ArrayList<>();
        Map<String, DispatchLine> dispatchByReceiptLine = new LinkedHashMap<>();
        for (ReceiveLine input : command.lines()) {
            TransferLine line = mapper.lockLine(principal.tenantId(), head.transferId(), input.transferLineId());
            DispatchLine dispatchLine = byLine.get(input.transferLineId());
            if (line == null || dispatchLine == null) throw new ServiceException("TRF-RECEIPT-002: 调拨发出行不存在或不可见", 404);
            BigDecimal quantity = TransferRules.quantity(input.receivedQuantity(), "receivedQuantity");
            TransferRules.withinRemaining(line.dispatchedQuantity(), line.receivedQuantity(), line.differenceQuantity(), quantity);
            mapper.insertReceiptLine(new ReceiptLineWrite(input.receiptLineId(), principal.tenantId(),
                command.receiptId(), line.transferLineId(), dispatchLine.dispatchLineId(), line.skuId(),
                line.baseUnitId(), quantity, at));
            progress(principal.tenantId(), line.transferLineId(), ZERO, quantity, ZERO, at);
            mapper.insertTransit(new TransitWrite(ulids.next(), principal.tenantId(), head.transferId(),
                line.transferLineId(), "RECEIVED", input.receiptLineId(), quantity, null, businessDate,
                command.correlationId(), at));
            movements.add(new OwnedMovementLine(input.receiptLineId(), line.skuId(), line.baseUnitId(),
                quantity, MovementType.TRANSFER_IN));
            dispatchByReceiptLine.put(input.receiptLineId(), dispatchLine);
        }
        boolean anyOpen = mapper.findLines(principal.tenantId(), head.transferId()).stream().anyMatch(line ->
            TransferRules.openTransit(line.dispatchedQuantity(), line.receivedQuantity(),
                line.differenceQuantity()).signum() > 0);
        Status next = anyOpen ? (command.finalReceipt() ? Status.DIFFERENCE_PENDING : Status.PARTIALLY_RECEIVED)
            : Status.CLOSED;
        updateStatus(head, next, null, null, null, next == Status.CLOSED ? at : null, at);
        movementPort.applyOwnedMovement(new OwnedMovement(command.eventId(), "TRANSFER_RECEIPT", command.receiptId(),
            head.destinationWarehouseId(), head.destinationStoreId(), businessDate, command.correlationId(), movements));
        applyReceiveLots(command, head, movements, dispatchByReceiptLine, businessDate);
        applied(principal.tenantId(), command.eventId(), at);
        audit(principal, head.destinationStoreId(), "TRANSFER_RECEIVED", head.transferId(), command.eventId(),
            command.correlationId(), head.status(), next.name(), command.finalReceipt() ? "FINAL" : "PARTIAL", at);
        event(principal.tenantId(), "inventory.transfer.received.v1", head.transferId(), head.version() + 1,
            command.correlationId(), Map.of("transferId", head.transferId(), "receiptId", command.receiptId(),
                "sourceEventId", command.eventId(), "state", next.name(),
                "sourceWarehouseId", head.sourceWarehouseId(),
                "destinationWarehouseId", head.destinationWarehouseId(), "businessDate", businessDate.toString()), at);
        return detail(head.transferId());
    }

    /** 发出批次按调拨行完整拆分，并与来源仓 TRANSFER_OUT 总账逐行守恒。 */
    private void applyDispatchLots(DispatchTransfer command, TransferHead head,
                                   Map<String, OwnedMovementLine> movementByTransferLine,
                                   LocalDate businessDate) {
        Map<String, List<DispatchLotSplit>> byLine = command.lotSplits().stream()
            .collect(java.util.stream.Collectors.groupingBy(DispatchLotSplit::transferLineId));
        List<ExplicitLine> lotLines = new ArrayList<>();
        for (Map.Entry<String, OwnedMovementLine> entry : movementByTransferLine.entrySet()) {
            OwnedMovementLine movement = entry.getValue();
            boolean required = lotMovementPort.requiresLotTracking(head.sourceStoreId(), movement.skuId(), businessDate);
            List<DispatchLotSplit> splits = byLine.remove(entry.getKey());
            if (!required) {
                if (splits != null && !splits.isEmpty()) {
                    throw new ServiceException("TRF-LOT-001: 未启用批次的调拨发出禁止提交批次拆分", 409);
                }
                continue;
            }
            if (splits == null || splits.isEmpty()) {
                throw new ServiceException("TRF-LOT-002: 已启用批次的调拨发出缺少批次拆分", 409);
            }
            BigDecimal total = splits.stream().map(DispatchLotSplit::baseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(movement.quantity()) != 0) {
                throw new ServiceException("TRF-LOT-003: 发出批次拆分与仓库总账数量不守恒", 409);
            }
            splits.forEach(split -> lotLines.add(new ExplicitLine(movement.sourceLineId(), split.lotId(),
                movement.skuId(), movement.baseUnitId(), split.baseQuantity(), MovementType.TRANSFER_OUT.name())));
        }
        if (!byLine.isEmpty()) throw new ServiceException("TRF-LOT-004: 发出批次拆分引用了未知调拨行", 409);
        if (!lotLines.isEmpty()) {
            lotMovementPort.applyExplicit(new ExplicitCommand(new CommandSource(command.eventId(),
                "TRANSFER_DISPATCH", command.dispatchId(), head.sourceWarehouseId(), head.sourceStoreId(),
                businessDate, command.correlationId()), MovementType.TRANSFER_OUT.name(), lotLines));
        }
    }

    /** 目的仓只引用原发出批次，Inventory Owner 负责继承日期、批号及剩余可收上限。 */
    private void applyReceiveLots(ReceiveTransfer command, TransferHead head, List<OwnedMovementLine> movements,
                                  Map<String, DispatchLine> dispatchByReceiptLine, LocalDate businessDate) {
        Map<String, List<ReceiveLotSplit>> byLine = command.lotSplits().stream()
            .collect(java.util.stream.Collectors.groupingBy(ReceiveLotSplit::receiptLineId));
        List<TransferReceiveLine> lotLines = new ArrayList<>();
        for (OwnedMovementLine movement : movements) {
            boolean required = lotMovementPort.requiresLotTracking(head.destinationStoreId(), movement.skuId(), businessDate);
            List<ReceiveLotSplit> splits = byLine.remove(movement.sourceLineId());
            if (!required) {
                if (splits != null && !splits.isEmpty()) {
                    throw new ServiceException("TRF-LOT-005: 未启用批次的调拨收货禁止提交批次拆分", 409);
                }
                continue;
            }
            if (splits == null || splits.isEmpty()) {
                throw new ServiceException("TRF-LOT-006: 已启用批次的调拨收货缺少来源批次", 409);
            }
            BigDecimal total = splits.stream().map(ReceiveLotSplit::baseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(movement.quantity()) != 0) {
                throw new ServiceException("TRF-LOT-007: 收货批次拆分与仓库总账数量不守恒", 409);
            }
            DispatchLine dispatch = dispatchByReceiptLine.get(movement.sourceLineId());
            if (dispatch == null) throw new ServiceException("TRF-LOT-008: 原发出行不存在", 409);
            splits.forEach(split -> lotLines.add(new TransferReceiveLine(movement.sourceLineId(),
                dispatch.dispatchLineId(), split.sourceLotId(), movement.skuId(), movement.baseUnitId(),
                split.baseQuantity())));
        }
        if (!byLine.isEmpty()) throw new ServiceException("TRF-LOT-009: 收货批次拆分引用了未知收货行", 409);
        if (!lotLines.isEmpty()) {
            DispatchHead dispatch = mapper.findDispatchByTransfer(tenantContext.requireTenantId(), head.transferId());
            if (dispatch == null) throw new ServiceException("TRF-LOT-010: 调拨发出事实不存在", 409);
            lotMovementPort.receiveTransfer(new TransferReceiveCommand(new CommandSource(command.eventId(),
                "TRANSFER_RECEIPT", command.receiptId(), head.destinationWarehouseId(), head.destinationStoreId(),
                businessDate, command.correlationId()), dispatch.dispatchId(), lotLines));
        }
    }

    /** 审批最终短少差异，只冲销在途，不伪造目的仓入库。 */
    @Transactional
    public TransferDetail resolveDifference(ResolveDifference command) {
        validateDifference(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TransferHead head = requireLocked(principal.tenantId(), command.transferId());
        authorizeBoth(head);
        String hash = hashDifference(command);
        if (beginCommand(principal.tenantId(), command.commandId(), command.transferId(), "DIFFERENCE", hash)) {
            return detail(command.transferId());
        }
        TransferRules.transition(Status.valueOf(head.status()), Status.DIFFERENCE_PENDING, Status.CLOSED);
        requireVersion(head, command.expectedVersion());
        Map<String, DifferenceLine> requested = new HashMap<>();
        command.lines().forEach(line -> requested.put(line.transferLineId(), line));
        LocalDateTime at = now();
        LocalDate businessDate = storeService.businessDate(head.destinationStoreId(), clock.instant()).businessDate();
        for (TransferLine line : mapper.findLines(principal.tenantId(), head.transferId())) {
            BigDecimal open = TransferRules.openTransit(line.dispatchedQuantity(), line.receivedQuantity(), line.differenceQuantity());
            if (open.signum() == 0) continue;
            DifferenceLine input = requested.remove(line.transferLineId());
            if (input == null || TransferRules.quantity(input.differenceQuantity(), "differenceQuantity").compareTo(open) != 0) {
                throw new ServiceException("TRF-DIFF-002: 差异必须精确覆盖全部在途余额", 409);
            }
            String differenceReason = TransferRules.differenceReason(input.differenceReason()).name();
            progress(principal.tenantId(), line.transferLineId(), ZERO, ZERO, open, at);
            mapper.insertTransit(new TransitWrite(ulids.next(), principal.tenantId(), head.transferId(),
                line.transferLineId(), "DIFFERENCE_APPROVED", command.commandId(), open, differenceReason, businessDate,
                command.correlationId(), at));
        }
        if (!requested.isEmpty()) throw new ServiceException("TRF-DIFF-003: 差异包含无在途余额的行", 409);
        updateStatus(head, Status.CLOSED, null, null, null, at, at);
        applied(principal.tenantId(), command.commandId(), at);
        audit(principal, head.destinationStoreId(), "TRANSFER_DIFFERENCE_APPROVED", head.transferId(),
            command.commandId(), command.correlationId(), head.status(), Status.CLOSED.name(), command.reason(), at);
        event(principal.tenantId(), "inventory.transfer.difference-approved.v1", head.transferId(),
            head.version() + 1, command.correlationId(), Map.of("transferId", head.transferId(),
                "commandId", command.commandId(), "state", Status.CLOSED.name(),
                "sourceWarehouseId", head.sourceWarehouseId(),
                "destinationWarehouseId", head.destinationWarehouseId()), at);
        return detail(head.transferId());
    }

    /** 只允许发出前取消；发出后必须走受控反向调拨。 */
    @Transactional
    public TransferDetail cancel(StateCommand command) {
        validateState(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TransferHead head = requireLocked(principal.tenantId(), command.transferId());
        authorizeBoth(head);
        Status current = Status.valueOf(head.status());
        if (current != Status.DRAFT && current != Status.SUBMITTED && current != Status.APPROVED) {
            throw new ServiceException("TRF-CANCEL-001: 发出后禁止取消，必须创建反向调拨", 409);
        }
        requireVersion(head, command.expectedVersion());
        String hash = stateHash(command, "CANCEL");
        if (beginCommand(principal.tenantId(), command.commandId(), command.transferId(), "CANCEL", hash)) {
            return detail(command.transferId());
        }
        LocalDateTime at = now();
        updateStatus(head, Status.CANCELLED, null, null, null, at, at);
        applied(principal.tenantId(), command.commandId(), at);
        audit(principal, head.sourceStoreId(), "TRANSFER_CANCELLED", head.transferId(), command.commandId(),
            command.correlationId(), head.status(), Status.CANCELLED.name(), command.reason(), at);
        event(principal.tenantId(), "inventory.transfer.cancelled.v1", head.transferId(), head.version() + 1,
            command.correlationId(), Map.of("transferId", head.transferId(), "state", Status.CANCELLED.name(),
                "sourceWarehouseId", head.sourceWarehouseId(),
                "destinationWarehouseId", head.destinationWarehouseId()), at);
        return detail(head.transferId());
    }

    @Transactional(readOnly = true)
    public TransferDetail detail(String transferId) {
        TransferRules.ulid(transferId, "transferId");
        String tenantId = tenantContext.requireTenantId();
        TransferHead head = mapper.findOrder(tenantId, transferId);
        if (head == null) throw new ServiceException("TRF-ORDER-001: 调拨单不存在或不可见", 404);
        authorizeBoth(head);
        return new TransferDetail(head, mapper.findLines(tenantId, transferId));
    }

    /** 从不可变在途流水重算并核对在线数量投影；发现漂移时仅返回红灯，不做静默修复。 */
    @Transactional(readOnly = true)
    public TransitReconciliation reconcileTransit(String transferId) {
        TransferRules.ulid(transferId, "transferId");
        String tenantId = tenantContext.requireTenantId();
        TransferHead head = mapper.findOrder(tenantId, transferId);
        if (head == null) throw new ServiceException("TRF-ORDER-001: 调拨单不存在或不可见", 404);
        authorizeBoth(head);
        List<TransitLineReconciliation> lines = mapper.findLines(tenantId, transferId).stream().map(line -> {
            BigDecimal dispatched = transitSum(tenantId, line.transferLineId(), "DISPATCHED");
            BigDecimal received = transitSum(tenantId, line.transferLineId(), "RECEIVED");
            BigDecimal difference = transitSum(tenantId, line.transferLineId(), "DIFFERENCE_APPROVED");
            BigDecimal open = dispatched.subtract(received).subtract(difference)
                .setScale(TransferRules.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
            boolean matches = dispatched.compareTo(line.dispatchedQuantity()) == 0
                && received.compareTo(line.receivedQuantity()) == 0
                && difference.compareTo(line.differenceQuantity()) == 0
                && open.compareTo(TransferRules.openTransit(line.dispatchedQuantity(), line.receivedQuantity(),
                    line.differenceQuantity())) == 0;
            return new TransitLineReconciliation(line.transferLineId(), dispatched, received, difference, open, matches);
        }).toList();
        return new TransitReconciliation(transferId, lines.stream().allMatch(TransitLineReconciliation::consistent), lines);
    }

    private BigDecimal transitSum(String tenantId, String lineId, String type) {
        BigDecimal result = mapper.sumTransit(tenantId, lineId, type);
        return (result == null ? ZERO : result).setScale(TransferRules.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
    }

    private TransferDetail transition(StateCommand command, Status expected, Status next,
                                      String action, boolean approving) {
        validateState(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        TransferHead head = requireLocked(principal.tenantId(), command.transferId());
        authorizeBoth(head);
        String hash = stateHash(command, action);
        if (beginCommand(principal.tenantId(), command.commandId(), command.transferId(), action, hash)) {
            return detail(command.transferId());
        }
        TransferRules.transition(Status.valueOf(head.status()), expected, next);
        requireVersion(head, command.expectedVersion());
        if (approving && principal.userId().equals(head.creatorUserId())) {
            throw new ServiceException("TRF-APPROVAL-001: 调拨创建与审批必须职责分离", 409);
        }
        LocalDateTime at = now();
        updateStatus(head, next, approving ? principal.userId() : null, approving ? at : null, null, null, at);
        applied(principal.tenantId(), command.commandId(), at);
        audit(principal, head.sourceStoreId(), action, head.transferId(), command.commandId(), command.correlationId(),
            head.status(), next.name(), command.reason(), at);
        event(principal.tenantId(), "inventory.transfer." + next.name().toLowerCase() + ".v1", head.transferId(),
            head.version() + 1, command.correlationId(), Map.of("transferId", head.transferId(), "state", next.name(),
                "sourceWarehouseId", head.sourceWarehouseId(),
                "destinationWarehouseId", head.destinationWarehouseId()), at);
        return detail(head.transferId());
    }

    private boolean beginCommand(String tenantId, String commandId, String transferId, String type, String hash) {
        String existing = mapper.findCommandHash(tenantId, commandId);
        if (existing != null) {
            if (!existing.equals(hash)) throw new ServiceException("TRF-IDEM-002: 相同 commandId 对应不同内容", 409);
            if ("APPLIED".equals(mapper.findCommandStatus(tenantId, commandId))) return true;
            throw new ServiceException("TRF-IDEM-003: 命令正在处理，禁止并发重入", 409);
        }
        try {
            mapper.insertCommand(new CommandWrite(commandId, tenantId, transferId, type, hash, "PROCESSING", now()));
            return false;
        } catch (DuplicateKeyException exception) {
            String racedHash = mapper.findCommandHash(tenantId, commandId);
            if (!hash.equals(racedHash)) {
                throw new ServiceException("TRF-IDEM-002: 相同 commandId 对应不同内容", 409);
            }
            if ("APPLIED".equals(mapper.findCommandStatus(tenantId, commandId))) return true;
            throw new ServiceException("TRF-IDEM-003: 命令正在处理，禁止并发重入", 409);
        }
    }

    private void applied(String tenantId, String commandId, LocalDateTime at) {
        if (mapper.markCommandApplied(new CommandApplied(tenantId, commandId, "APPLIED", at)) != 1) {
            throw new ServiceException("TRF-IDEM-004: 命令完成状态冲突", 409);
        }
    }

    private void updateStatus(TransferHead head, Status next, Long approver, LocalDateTime approved,
                              LocalDateTime dispatched, LocalDateTime closed, LocalDateTime at) {
        if (mapper.updateStatus(new StatusUpdate(tenantContext.requireTenantId(), head.transferId(), head.status(),
            next.name(), head.version(), approver, approved, dispatched, closed, at)) != 1) {
            throw new ServiceException("TRF-STATE-003: 调拨状态或版本冲突", 409);
        }
    }

    private void progress(String tenantId, String lineId, BigDecimal dispatched, BigDecimal received,
                          BigDecimal difference, LocalDateTime at) {
        if (mapper.updateLineProgress(new LineProgress(tenantId, lineId, dispatched, received, difference, at)) != 1) {
            throw new ServiceException("TRF-QTY-003: 调拨行数量并发冲突或超过上限", 409);
        }
    }

    private TransferHead requireLocked(String tenantId, String transferId) {
        TransferHead head = mapper.lockOrder(tenantId, transferId);
        if (head == null) throw new ServiceException("TRF-ORDER-001: 调拨单不存在或不可见", 404);
        return head;
    }

    private void authorizeBoth(TransferHead head) {
        authorizationService.requireStoreAccess(head.sourceStoreId());
        authorizationService.requireStoreAccess(head.destinationStoreId());
    }

    private void requireVersion(TransferHead head, long expected) {
        if (head.version() != expected) throw new ServiceException("TRF-STATE-003: 调拨状态或版本冲突", 409);
    }

    private void validateCreate(CreateTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.distinctWarehouses(command.sourceWarehouseId(), command.destinationWarehouseId());
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.sourceStoreId() == null || command.sourceStoreId() <= 0 || command.destinationStoreId() == null
            || command.destinationStoreId() <= 0 || command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(CreateLine::transferLineId).toList()).size() != command.lines().size()
            || new HashSet<>(command.lines().stream().map(CreateLine::skuId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-INPUT-003: 调拨主体、行标识或 SKU 不合法", 409);
        }
    }

    private void validateState(StateCommand command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.commandId(), "commandId");
        TransferRules.text(command.reason(), 256, "TRF-INPUT-002");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
    }

    private void validateDispatch(DispatchTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.dispatchId(), "dispatchId");
        TransferRules.ulid(command.eventId(), "eventId");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lotSplits().size() > 1000) throw new ServiceException("TRF-LOT-011: 发出批次拆分超过上限", 409);
        command.lotSplits().forEach(split -> {
            TransferRules.ulid(split.transferLineId(), "transferLineId");
            TransferRules.ulid(split.lotId(), "lotId");
            TransferRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    private void validateReceive(ReceiveTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.receiptId(), "receiptId");
        TransferRules.ulid(command.eventId(), "eventId");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(ReceiveLine::receiptLineId).toList()).size() != command.lines().size()
            || new HashSet<>(command.lines().stream().map(ReceiveLine::transferLineId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-RECEIPT-003: 收货行必须唯一且为1至500项", 409);
        }
        command.lines().forEach(line -> { TransferRules.ulid(line.receiptLineId(), "receiptLineId");
            TransferRules.ulid(line.transferLineId(), "transferLineId"); });
        if (command.lotSplits().size() > 1000) throw new ServiceException("TRF-LOT-012: 收货批次拆分超过上限", 409);
        command.lotSplits().forEach(split -> {
            TransferRules.ulid(split.receiptLineId(), "receiptLineId");
            TransferRules.ulid(split.sourceLotId(), "sourceLotId");
            TransferRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    private void validateDifference(ResolveDifference command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.commandId(), "commandId");
        TransferRules.text(command.reason(), 256, "TRF-DIFF-004");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(DifferenceLine::transferLineId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-DIFF-005: 差异行必须唯一且为1至500项", 409);
        }
    }

    private BigDecimal toBase(BigDecimal value, SkuUnitSnapshot unit) {
        try {
            return value.multiply(BigDecimal.valueOf(unit.numerator()))
                .divide(BigDecimal.valueOf(unit.denominator()), TransferRules.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException("TRF-UNIT-001: 单位换算不能精确到六位小数", 409);
        }
    }

    private String hashCreate(CreateTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.sourceStoreId(),
            command.sourceWarehouseId(), command.destinationStoreId(), command.destinationWarehouseId(),
            command.reason()));
        command.lines().stream().sorted(Comparator.comparing(CreateLine::transferLineId)).forEach(line -> {
            values.add(line.transferLineId()); values.add(line.skuId()); values.add(line.unitId());
            values.add(line.requestedQuantity());
        });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    private String stateHash(StateCommand command, String type) {
        return TransferHash.sha256(TransferHash.canonical(List.of(type, command.transferId(), command.commandId(),
            command.expectedVersion(), command.reason())));
    }

    private String hashReceive(ReceiveTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.receiptId(), command.eventId(),
            command.expectedVersion(), command.finalReceipt()));
        command.lines().stream().sorted(Comparator.comparing(ReceiveLine::receiptLineId)).forEach(line -> {
            values.add(line.receiptLineId()); values.add(line.transferLineId()); values.add(line.receivedQuantity());
        });
        command.lotSplits().stream().sorted(Comparator.comparing(ReceiveLotSplit::receiptLineId)
            .thenComparing(ReceiveLotSplit::sourceLotId)).forEach(line -> {
                values.add(line.receiptLineId()); values.add(line.sourceLotId()); values.add(line.baseQuantity());
            });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    private String hashDispatch(DispatchTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.dispatchId(), command.eventId(),
            command.expectedVersion()));
        command.lotSplits().stream().sorted(Comparator.comparing(DispatchLotSplit::transferLineId)
            .thenComparing(DispatchLotSplit::lotId)).forEach(line -> {
                values.add(line.transferLineId()); values.add(line.lotId()); values.add(line.baseQuantity());
            });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    private String hashDifference(ResolveDifference command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.commandId(),
            command.expectedVersion(), command.reason()));
        command.lines().stream().sorted(Comparator.comparing(DifferenceLine::transferLineId)).forEach(line -> {
            values.add(line.transferLineId()); values.add(line.differenceQuantity()); values.add(line.differenceReason());
        });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    private void audit(TrustedPrincipal principal, Long storeId, String action, String aggregateId,
                       String commandId, String correlationId, String before, String after,
                       String reason, LocalDateTime at) {
        String safeReason = reason == null || reason.isBlank() ? action : reason;
        String hash = TransferHash.sha256(TransferHash.canonical(List.of(action, aggregateId,
            String.valueOf(before), String.valueOf(after), safeReason)));
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), storeId, action, "TRANSFER",
            aggregateId, principal.userId(), commandId, correlationId, before, after, hash, safeReason, at));
    }

    private void event(String tenantId, String type, String aggregateId, long version,
                       String correlationId, Map<String, Object> payload, LocalDateTime at) {
        String eventId = ulids.next();
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("schemaVersion", "1.0");
        body.put("eventId", eventId);
        body.put("eventType", type);
        body.put("aggregateId", aggregateId);
        body.put("aggregateVersion", version);
        body.put("correlationId", correlationId);
        try {
            String json = objectMapper.writeValueAsString(body);
            mapper.insertOutbox(new OutboxWrite(eventId, tenantId, type, aggregateId, version,
                correlationId, json, TransferHash.sha256(json), at));
        } catch (JsonProcessingException exception) {
            throw new ServiceException("TRF-EVENT-001: 调拨事件序列化失败", 500);
        }
    }

    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)); }
}
