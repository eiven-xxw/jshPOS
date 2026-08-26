package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort.ReturnOrderLine;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort.RefundState;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocatedLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationResult;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.PreviewCommand;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.PreviewResult;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.ApproveReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PaymentObservation;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PreviewReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.RequestReturn;
import com.jingshanghui.pos.returns.application.model.ReturnViews.PreviewLine;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnPreview;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnLineView;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ReturnHash;
import com.jingshanghui.pos.returns.domain.ReturnRules;
import com.jingshanghui.pos.returns.domain.ReturnStates.SettlementKind;
import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import com.jingshanghui.pos.returns.infrastructure.persistence.ReturnPersistenceParams.*;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ReturnMapper.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REF-002 原单退货退款 Saga Owner。
 * 每次方法只提交本 Owner 检查点；外部 Owner 成功后通过稳定事件和 Inbox 推进，支持崩溃后重放。
 */
@Service
@RequiredArgsConstructor
public class ReturnOrchestrationService {

    private static final String REQUEST_COMMAND = "REQUEST_ORIGINAL_RETURN";
    private final ReturnMapper mapper;
    private final ReturnOrderSnapshotPort orders;
    private final ReturnPromotionAllocationPort promotions;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService audit;
    private final UlidGenerator ulids;
    /** 纯命令规则不持有 Mapper 或事务，公开事务入口仍由本服务独占。 */
    private final ReturnCommandPolicy commandPolicy = new ReturnCommandPolicy();

    /**
     * 原单与退款金额只读预检。金额只由 Promotion Owner 原成交快照算法计算，
     * 本方法不创建退款标识、账本、审计或 Outbox。
     */
    @Transactional(readOnly = true)
    public ReturnPreview preview(PreviewReturn command) {
        if (command == null || command.orderQuery() == null || command.orderQuery().isBlank()
            || command.orderQuery().length() > 64 || command.lines().size() > 500) {
            throw new ServiceException("RET-PREVIEW-001: 原单预检字段非法", 400);
        }
        String tenantId = tenantContext.requireTenantId();
        ReturnOrderSnapshotPort.ReturnOrderSnapshot order = orders.resolveSnapshot(command.orderQuery().trim());
        authorizationService.requireStoreAccess(order.storeId());
        Map<String, ReturnOrderLine> source = new LinkedHashMap<>();
        order.lines().forEach(line -> source.put(line.lineId(), line));
        Map<String, BigDecimal> reserved = new HashMap<>();
        mapper.sumReservedQuantities(tenantId, order.orderId())
            .forEach(value -> reserved.put(value.orderLineId(), value.reservedQuantity()));
        Map<String, BigDecimal> requested = new LinkedHashMap<>();
        try {
            for (var line : command.lines()) {
                ReturnRules.requireUlid(line.orderLineId(), "orderLineId");
                BigDecimal quantity = ReturnRules.positiveQuantity(new BigDecimal(line.quantity()), "quantity");
                if (requested.putIfAbsent(line.orderLineId(), quantity) != null) {
                    throw new ServiceException("RET-LINE-002: 同一原订单行不得重复", 409);
                }
                ReturnOrderLine original = source.get(line.orderLineId());
                if (original == null) throw new ServiceException("RET-LINE-001: 原成交行不存在", 409);
                ReturnRules.requireQuantityAvailable(original.quantity(),
                    reserved.getOrDefault(line.orderLineId(), BigDecimal.ZERO), quantity);
            }
        } catch (NumberFormatException exception) {
            throw new ServiceException("RET-QTY-001: quantity必须为精确十进制", 409);
        }
        PreviewResult allocation = requested.isEmpty()
            ? new PreviewResult(order.promotionSnapshotId(), 0, 0, 0, List.of())
            : promotions.preview(new PreviewCommand(order.promotionSnapshotId(), requested.entrySet().stream()
                .map(value -> new AllocationLine(value.getKey(), value.getValue())).toList()));
        if (!order.promotionSnapshotId().equals(allocation.snapshotId())
            || allocation.grossAmountMinor() - allocation.recoveredDiscountMinor()
                != allocation.refundableAmountMinor()) {
            throw new ServiceException("RET-PREVIEW-002: Promotion预检身份或金额不守恒", 500);
        }
        Map<String, AllocatedLine> allocated = new HashMap<>();
        allocation.lines().forEach(line -> allocated.put(line.lineId(), line));
        if (!allocated.keySet().equals(requested.keySet())) {
            throw new ServiceException("RET-PREVIEW-003: Promotion预检行集合不一致", 500);
        }
        long cumulativeRefunded = mapper.sumReservedRefundAmount(tenantId, order.orderId());
        long maximumRefundable = order.receivableAmountMinor() - cumulativeRefunded;
        if (maximumRefundable < 0 || allocation.refundableAmountMinor() > maximumRefundable) {
            throw new ServiceException("RET-PREVIEW-004: 累计退款金额超过原单上限", 409);
        }
        List<PreviewLine> lines = order.lines().stream().map(line -> {
            BigDecimal returned = reserved.getOrDefault(line.lineId(), BigDecimal.ZERO);
            BigDecimal maximum = line.quantity().subtract(returned);
            AllocatedLine value = allocated.get(line.lineId());
            return new PreviewLine(line.lineId(), line.skuCode(), line.productName(), line.unitCode(),
                line.quantity(), returned, maximum, requested.getOrDefault(line.lineId(), BigDecimal.ZERO),
                value == null ? 0 : value.grossAmountMinor(),
                value == null ? 0 : value.recoveredDiscountMinor(),
                value == null ? 0 : value.refundableAmountMinor());
        }).toList();
        return new ReturnPreview(order.orderId(), order.localOrderNo(), order.storeId(), order.businessDate(),
            order.currency(), order.cashPaymentId() == null ? "PROVIDER_NEUTRAL" : "CASH",
            order.promotionSnapshotId(), order.promotionSnapshotSha256(), order.receivableAmountMinor(),
            cumulativeRefunded, maximumRefundable, allocation.grossAmountMinor(),
            allocation.recoveredDiscountMinor(), allocation.refundableAmountMinor(), lines);
    }

    /** 创建待独立审批申请，并在订单守卫锁内校验累计数量上限。 */
    @Transactional
    public ReturnView request(RequestReturn command) {
        NormalizedRequest normalized = validateRequest(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.storeId());
        ReturnOrderSnapshotPort.ReturnOrderSnapshot order = orders.requireSnapshot(command.orderId());
        requireOrder(command, normalized.kind(), order);
        String requestHash = hashRequest(command, normalized.lines());
        IdempotencyRow duplicate = mapper.findIdempotency(principal.tenantId(), REQUEST_COMMAND,
            command.idempotencyKey());
        if (duplicate != null) {
            if (!duplicate.requestSha256().equals(requestHash)) {
                throw new ServiceException("RET-IDEM-001: 同一幂等键对应不同退货内容", 409);
            }
            return view(requireRow(mapper.findReturn(principal.tenantId(), duplicate.aggregateId())), true);
        }
        mapper.insertOrderGuard(principal.tenantId(), command.orderId());
        if (mapper.lockOrderGuard(principal.tenantId(), command.orderId()) == null) {
            throw new ServiceException("RET-LOCK-001: 无法锁定原订单退货上限", 409);
        }
        Map<String, BigDecimal> reserved = new HashMap<>();
        mapper.sumReservedQuantities(principal.tenantId(), command.orderId())
            .forEach(value -> reserved.put(value.orderLineId(), value.reservedQuantity()));
        Map<String, ReturnOrderLine> source = new HashMap<>();
        order.lines().forEach(line -> source.put(line.lineId(), line));
        LocalDateTime at = commandPolicy.utc(command.occurredAt());
        mapper.insertReturn(new ReturnWrite(command.returnId(), principal.tenantId(), command.idempotencyKey(),
            requestHash, command.commandId(), command.orderId(), command.storeId(), command.terminalId(), command.refundShiftId(),
            command.warehouseId(), command.businessDate(), normalized.kind().name(), command.paymentId(),
            order.cashPaymentId(), order.promotionSnapshotId(), order.promotionSnapshotSha256(),
            command.reasonCode(), principal.userId(), command.correlationId(), at));
        for (NormalizedLine line : normalized.lines()) {
            ReturnOrderLine original = source.get(line.orderLineId());
            if (original == null) throw new ServiceException("RET-LINE-001: 原成交行不存在", 409);
            ReturnRules.requireQuantityAvailable(original.quantity(),
                reserved.getOrDefault(line.orderLineId(), BigDecimal.ZERO), line.quantity());
            mapper.insertLine(new LineWrite(ulids.next(), principal.tenantId(), command.returnId(),
                line.orderLineId(), original.skuId(), original.unitId(), line.quantity()));
        }
        mapper.insertHistory(new HistoryWrite(ulids.next(), principal.tenantId(), command.returnId(),
            command.commandId(), null, Status.PENDING_APPROVAL.name(), 1, principal.userId(),
            command.reasonCode(), at));
        mapper.insertIdempotency(new IdempotencyWrite(principal.tenantId(), REQUEST_COMMAND,
            command.idempotencyKey(), requestHash, command.returnId(), at));
        audit.append("RETURN_REQUESTED", "RETURN", command.returnId(), null, Status.PENDING_APPROVAL.name(),
            Map.of("orderId", command.orderId(), "lineCount", normalized.lines().size(),
                "settlementKind", normalized.kind().name()));
        return view(requireRow(mapper.findReturn(principal.tenantId(), command.returnId())), false);
    }

    /** 独立审批后只发布促销恢复命令，不在本事务调用任何其他 Owner。 */
    @Transactional
    public ReturnView approve(ApproveReturn command) {
        commandPolicy.validateApproval(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), command.returnId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!current.correlationId().equals(command.correlationId())) {
            throw new ServiceException("RET-CORRELATION-001: 审批必须沿用原关联标识", 409);
        }
        if (current.requesterUserId().equals(principal.userId())) {
            throw new ServiceException("RET-RBAC-001: 退货申请人与审批人必须分离", 409);
        }
        requireStatus(current, Status.PENDING_APPROVAL);
        ReturnRules.requireTransition(Status.PENDING_APPROVAL, Status.PROMOTION_PENDING);
        String eventId = ulids.next();
        LocalDateTime at = commandPolicy.utc(command.occurredAt());
        if (mapper.approve(principal.tenantId(), current.returnId(), current.recordVersion(), principal.userId(),
            eventId, at) != 1) throw conflict();
        appendOutbox(principal.tenantId(), eventId, "return.promotion.allocate.requested.v1", current.returnId(),
            current.recordVersion() + 1, current.correlationId(), promotionRequestPayload(current), at);
        history(principal, current, eventId, Status.PROMOTION_PENDING, command.reasonCode(), at);
        audit.append("RETURN_APPROVED", "RETURN", current.returnId(), current.status(),
            Status.PROMOTION_PENDING.name(), Map.of("promotionEventId", eventId));
        return view(requireRow(mapper.findReturn(principal.tenantId(), current.returnId())), false);
    }

    /** 消费 Promotion Owner 只追加退款恢复结果并生成下一稳定 Owner 事件。 */
    @Transactional
    public ReturnView acceptPromotion(String eventId, AllocationResult result, Instant occurredAt) {
        ReturnRules.requireUlid(eventId, "eventId");
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String payloadHash = commandPolicy.hashAllocation(result);
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), result.refundId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!acceptInbox(principal.tenantId(), eventId, "PROMOTION", current.returnId(), payloadHash,
            commandPolicy.utc(occurredAt))) return view(current, true);
        requireStatus(current, Status.PROMOTION_PENDING);
        if (!eventId.equals(current.promotionEventId()) || !result.snapshotId().equals(current.promotionSnapshotId())) {
            throw new ServiceException("RET-PRM-001: Promotion结果身份与原事件不一致", 409);
        }
        validateAllocation(current, result);
        for (AllocatedLine line : result.lines()) {
            if (mapper.updateAllocation(new AllocationUpdate(principal.tenantId(), current.returnId(), line.lineId(),
                line.grossAmountMinor(), line.recoveredDiscountMinor(), line.refundableAmountMinor(),
                line.cumulativeQuantity(), line.cumulativePayableAmountMinor())) != 1) throw conflict();
        }
        String paymentEventId = result.refundableAmountMinor() == 0 ? null : ulids.next();
        String inventoryEventId = result.refundableAmountMinor() == 0 ? ulids.next() : null;
        Status next = result.refundableAmountMinor() == 0 ? Status.INVENTORY_PENDING
            : SettlementKind.CASH.name().equals(current.settlementKind())
            ? Status.CASH_REFUND_PENDING : Status.PAYMENT_PENDING;
        ReturnRules.requireTransition(Status.PROMOTION_PENDING, next);
        LocalDateTime at = commandPolicy.utc(occurredAt);
        if (mapper.applyPromotionHeader(principal.tenantId(), current.returnId(), current.recordVersion(), next.name(),
            result.grossAmountMinor(), result.recoveredDiscountMinor(), result.refundableAmountMinor(),
            paymentEventId, inventoryEventId, at) != 1) throw conflict();
        mapper.markOutboxDelivered(principal.tenantId(), eventId, at);
        String nextEvent = paymentEventId == null ? inventoryEventId : paymentEventId;
        appendOutbox(principal.tenantId(), nextEvent, next == Status.CASH_REFUND_PENDING
            ? "return.cash.refund.requested.v1" : next == Status.PAYMENT_PENDING
            ? "return.payment.refund.requested.v1" : "return.inventory.receipt.requested.v1",
            current.returnId(), current.recordVersion() + 1, current.correlationId(),
            Map.of("returnId", current.returnId(), "amountMinor", result.refundableAmountMinor()), at);
        history(principal, current, eventId, next, "ORIGINAL_PROMOTION_ALLOCATED", at);
        audit.append("RETURN_PROMOTION_ALLOCATED", "RETURN", current.returnId(), current.status(), next.name(),
            Map.of("grossAmountMinor", result.grossAmountMinor(),
                "recoveredDiscountMinor", result.recoveredDiscountMinor(),
                "refundableAmountMinor", result.refundableAmountMinor()));
        return view(requireRow(mapper.findReturn(principal.tenantId(), current.returnId())), false);
    }

    /** 记录现金退款 Owner 成功结果；失败抛出时保持 CASH_REFUND_PENDING 以复用原事件重试。 */
    @Transactional
    public ReturnView acceptCashRefund(String eventId, String returnId, long amountMinor,
                                       String status, String payloadSha256, Instant occurredAt) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnRules.requireHash(payloadSha256, "payloadSha256");
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), returnId));
        authorizationService.requireStoreAccess(current.storeId());
        if (!acceptInbox(principal.tenantId(), eventId, "CASH_REFUND", returnId, payloadSha256,
            commandPolicy.utc(occurredAt))) return view(current, true);
        requireStatus(current, Status.CASH_REFUND_PENDING);
        if (!eventId.equals(current.paymentEventId()) || !"SUCCEEDED".equals(status)
            || current.refundableAmountMinor() == null || current.refundableAmountMinor() != amountMinor) {
            throw new ServiceException("RET-CASH-001: 现金退款结果身份、状态或金额不一致", 409);
        }
        return advanceToInventory(principal, current, eventId, "CASH_REFUND_SUCCEEDED", occurredAt);
    }

    /** Payment Owner 接受原业务命令后只确认投递，不把待审批误判为资金成功。 */
    @Transactional
    public ReturnView acknowledgePaymentRequest(String eventId, RefundState state, Instant occurredAt) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), state.refundId()));
        authorizationService.requireStoreAccess(current.storeId());
        String payloadHash = ReturnHash.sha256(ReturnHash.canonical(List.of(state.refundId(), state.paymentId(),
            state.status(), state.amountMinor(), state.currency())));
        if (!acceptInbox(principal.tenantId(), eventId, "PAYMENT_COMMAND", current.returnId(), payloadHash,
            commandPolicy.utc(occurredAt))) return view(current, true);
        requireStatus(current, Status.PAYMENT_PENDING);
        if (!eventId.equals(current.paymentEventId()) || !state.paymentId().equals(current.paymentId())
            || state.amountMinor() != current.refundableAmountMinor() || !"CNY".equals(state.currency())) {
            throw new ServiceException("RET-PAY-001: Payment命令回执身份或金额不一致", 409);
        }
        mapper.markOutboxDelivered(principal.tenantId(), eventId, commandPolicy.utc(occurredAt));
        audit.append("RETURN_PAYMENT_REQUEST_ACKED", "RETURN", current.returnId(), current.status(),
            current.status(), Map.of("paymentStatus", state.status()));
        return view(current, false);
    }

    /** 合并 Provider 无关退款查询/可信观察；UNKNOWN 绝不生成新退款命令。 */
    @Transactional
    public ReturnView observePayment(PaymentObservation observation) {
        commandPolicy.validateObservation(observation);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), observation.returnId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!acceptInbox(principal.tenantId(), observation.observationId(), "PAYMENT_OBSERVATION",
            current.returnId(), observation.payloadSha256(), commandPolicy.utc(observation.observedAt()))) return view(current, true);
        Status before = Status.valueOf(current.status());
        if (before != Status.PAYMENT_PENDING && before != Status.PAYMENT_UNKNOWN) {
            throw new ServiceException("RET-PAY-002: 当前Saga不接受支付退款观察", 409);
        }
        if (current.refundableAmountMinor() == null || current.refundableAmountMinor() != observation.amountMinor()) {
            throw new ServiceException("RET-PAY-003: Payment观察金额与原快照退款金额不一致", 409);
        }
        String paymentStatus = observation.paymentStatus();
        if ("PENDING_APPROVAL".equals(paymentStatus) || "PROCESSING".equals(paymentStatus)) {
            return view(current, false);
        }
        if ("UNKNOWN".equals(paymentStatus)) {
            if (before == Status.PAYMENT_UNKNOWN) return view(current, false);
            return advance(principal, current, observation.observationId(), Status.PAYMENT_UNKNOWN,
                null, "PAYMENT_UNKNOWN_QUERY_REQUIRED", observation.observedAt());
        }
        if ("SUCCEEDED".equals(paymentStatus)) {
            return advanceToInventory(principal, current, observation.observationId(),
                "PAYMENT_REFUND_SUCCEEDED", observation.observedAt());
        }
        if ("FAILED".equals(paymentStatus) || "CANCELLED".equals(paymentStatus)) {
            return advance(principal, current, observation.observationId(), Status.FAILED,
                null, "PAYMENT_REFUND_TERMINAL_FAILURE", observation.observedAt());
        }
        throw new ServiceException("RET-PAY-004: Payment观察状态未准入", 409);
    }

    /** 库存 Owner 已幂等追加 SALE_RETURN_IN 后完成 Saga。 */
    @Transactional
    public ReturnView acceptInventory(String eventId, ApplyResult result, String payloadSha256, Instant occurredAt) {
        ReturnRules.requireHash(payloadSha256, "payloadSha256");
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnRow current = requireRow(mapper.lockReturn(principal.tenantId(), result.sourceId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!acceptInbox(principal.tenantId(), eventId, "INVENTORY", current.returnId(), payloadSha256,
            commandPolicy.utc(occurredAt))) return view(current, true);
        requireStatus(current, Status.INVENTORY_PENDING);
        if (!eventId.equals(current.inventoryEventId()) || !eventId.equals(result.eventId())
            || !"REFUND".equals(result.sourceType()) || result.affectedLines() != mapper.listLines(
            principal.tenantId(), current.returnId()).size()) {
            throw new ServiceException("RET-INV-001: 库存结果身份、来源或行数不一致", 409);
        }
        ReturnRules.requireTransition(Status.INVENTORY_PENDING, Status.COMPLETED);
        LocalDateTime at = commandPolicy.utc(occurredAt);
        if (mapper.completeInventory(principal.tenantId(), current.returnId(), current.recordVersion(), at) != 1) {
            throw conflict();
        }
        mapper.markOutboxDelivered(principal.tenantId(), eventId, at);
        history(principal, current, eventId, Status.COMPLETED, "INVENTORY_RETURN_APPLIED", at);
        audit.append("RETURN_COMPLETED", "RETURN", current.returnId(), current.status(), Status.COMPLETED.name(),
            Map.of("inventoryEventId", eventId, "affectedLines", result.affectedLines()));
        return view(requireRow(mapper.findReturn(principal.tenantId(), current.returnId())), false);
    }

    @Transactional(readOnly = true)
    public ReturnView find(String returnId) {
        ReturnRules.requireUlid(returnId, "returnId");
        ReturnRow row = requireRow(mapper.findReturn(tenantContext.requireTenantId(), returnId));
        authorizationService.requireStoreAccess(row.storeId());
        return view(row, false);
    }

    private ReturnView advanceToInventory(TrustedPrincipal principal, ReturnRow current, String eventId,
                                          String reason, Instant occurredAt) {
        String inventoryEventId = current.inventoryEventId() == null ? ulids.next() : current.inventoryEventId();
        ReturnView changed = advance(principal, current, eventId, Status.INVENTORY_PENDING,
            inventoryEventId, reason, occurredAt);
        LocalDateTime at = commandPolicy.utc(occurredAt);
        appendOutbox(principal.tenantId(), inventoryEventId, "return.inventory.receipt.requested.v1",
            current.returnId(), current.recordVersion() + 1, current.correlationId(),
            Map.of("returnId", current.returnId(), "warehouseId", current.warehouseId()), at);
        mapper.markOutboxDelivered(principal.tenantId(), current.paymentEventId(), at);
        return changed;
    }

    private ReturnView advance(TrustedPrincipal principal, ReturnRow current, String eventId, Status next,
                               String inventoryEventId, String reason, Instant occurredAt) {
        Status before = Status.valueOf(current.status());
        ReturnRules.requireTransition(before, next);
        LocalDateTime at = commandPolicy.utc(occurredAt);
        if (mapper.advancePayment(principal.tenantId(), current.returnId(), current.recordVersion(),
            current.status(), next.name(), inventoryEventId, at) != 1) throw conflict();
        history(principal, current, eventId, next, reason, at);
        audit.append("RETURN_" + next.name(), "RETURN", current.returnId(), before.name(), next.name(),
            Map.of("eventId", eventId, "reason", reason));
        return view(requireRow(mapper.findReturn(principal.tenantId(), current.returnId())), false);
    }

    private void validateAllocation(ReturnRow current, AllocationResult result) {
        ReturnRules.requireAllocation(result.grossAmountMinor(), result.recoveredDiscountMinor(),
            result.refundableAmountMinor());
        Map<String, LineRow> requested = new HashMap<>();
        mapper.listLines(tenantContext.requireTenantId(), current.returnId())
            .forEach(line -> requested.put(line.orderLineId(), line));
        long gross = 0, discount = 0, refundable = 0;
        for (AllocatedLine line : result.lines()) {
            LineRow source = requested.get(line.lineId());
            ReturnRules.requireAllocation(line.grossAmountMinor(), line.recoveredDiscountMinor(),
                line.refundableAmountMinor());
            if (source == null || source.requestedQuantity().compareTo(line.quantity()) != 0) {
                throw new ServiceException("RET-PRM-002: 促销恢复行身份或数量不一致", 409);
            }
            gross = Math.addExact(gross, line.grossAmountMinor());
            discount = Math.addExact(discount, line.recoveredDiscountMinor());
            refundable = Math.addExact(refundable, line.refundableAmountMinor());
        }
        if (requested.size() != result.lines().size() || gross != result.grossAmountMinor()
            || discount != result.recoveredDiscountMinor() || refundable != result.refundableAmountMinor()) {
            throw new ServiceException("RET-PRM-003: 促销恢复头行金额不守恒", 409);
        }
    }

    private boolean acceptInbox(String tenantId, String eventId, String owner, String aggregateId,
                                String payloadHash, LocalDateTime at) {
        ReturnRules.requireUlid(eventId, "eventId");
        ReturnRules.requireHash(payloadHash, "payloadSha256");
        InboxRow existing = mapper.findInbox(tenantId, eventId);
        if (existing != null) {
            if (!existing.ownerCode().equals(owner) || !existing.aggregateId().equals(aggregateId)
                || !existing.payloadSha256().equals(payloadHash)) {
                throw new ServiceException("RET-INBOX-001: 同一Owner事件对应不同身份或内容", 409);
            }
            return false;
        }
        mapper.insertInbox(new InboxWrite(eventId, tenantId, owner, aggregateId, payloadHash, at));
        return true;
    }

    private void appendOutbox(String tenantId, String eventId, String eventType, String aggregateId,
                              long version, String correlationId, Map<String, ?> body, LocalDateTime at) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", "1.0"); payload.putAll(body);
        var canonical = ReturnHash.payload(payload);
        mapper.insertOutbox(new OutboxWrite(eventId, tenantId, eventType, aggregateId, version,
            correlationId, canonical.json(), canonical.sha256(), at));
    }

    private Map<String, Object> promotionRequestPayload(ReturnRow current) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("returnId", current.returnId()); payload.put("orderId", current.orderId());
        payload.put("promotionSnapshotId", current.promotionSnapshotId());
        payload.put("promotionSnapshotHash", "sha256:" + current.promotionSnapshotSha256());
        payload.put("lines", mapper.listLines(tenantContext.requireTenantId(), current.returnId()).stream()
            .map(line -> Map.of("orderLineId", line.orderLineId(),
                "quantity", line.requestedQuantity().toPlainString())).toList());
        return payload;
    }

    private void history(TrustedPrincipal principal, ReturnRow current, String eventId, Status next,
                         String reason, LocalDateTime at) {
        mapper.insertHistory(new HistoryWrite(ulids.next(), principal.tenantId(), current.returnId(), eventId,
            current.status(), next.name(), current.recordVersion() + 1, principal.userId(), reason, at));
    }

    private NormalizedRequest validateRequest(RequestReturn command) {
        ReturnRules.requireUlid(command.commandId(), "commandId"); ReturnRules.requireUlid(command.returnId(), "returnId");
        ReturnRules.requireUlid(command.orderId(), "orderId"); ReturnRules.requireUlid(command.terminalId(), "terminalId");
        ReturnRules.requireUlid(command.refundShiftId(), "refundShiftId"); ReturnRules.requireUlid(command.warehouseId(), "warehouseId");
        ReturnRules.requireUlid(command.correlationId(), "correlationId");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank() || command.idempotencyKey().length() > 96
            || command.storeId() == null || command.storeId() <= 0 || command.businessDate() == null
            || command.reasonCode() == null || !command.reasonCode().matches("^[A-Z0-9_]{2,32}$")
            || command.occurredAt() == null || command.lines().isEmpty() || command.lines().size() > 500) {
            throw new ServiceException("RET-INPUT-001: 退货申请字段或行数非法", 409);
        }
        SettlementKind kind;
        try { kind = SettlementKind.valueOf(command.settlementKind()); }
        catch (RuntimeException exception) { throw new ServiceException("RET-INPUT-002: 结算类型未准入", 409); }
        if (kind == SettlementKind.CASH && command.paymentId() != null
            || kind == SettlementKind.PROVIDER_NEUTRAL && command.paymentId() == null) {
            throw new ServiceException("RET-INPUT-003: 结算类型与原支付身份不一致", 409);
        }
        if (command.paymentId() != null) ReturnRules.requireUlid(command.paymentId(), "paymentId");
        List<NormalizedLine> lines = new ArrayList<>();
        try {
            for (var line : command.lines()) {
                ReturnRules.requireUlid(line.orderLineId(), "orderLineId");
                lines.add(new NormalizedLine(line.orderLineId(),
                    ReturnRules.positiveQuantity(new BigDecimal(line.quantity()), "quantity")));
            }
        } catch (NumberFormatException exception) {
            throw new ServiceException("RET-QTY-001: quantity必须为精确十进制", 409);
        }
        if (new HashSet<>(lines.stream().map(NormalizedLine::orderLineId).toList()).size() != lines.size()) {
            throw new ServiceException("RET-LINE-002: 同一原订单行不得重复", 409);
        }
        return new NormalizedRequest(kind, lines.stream().sorted(Comparator.comparing(NormalizedLine::orderLineId)).toList());
    }

    private void requireOrder(RequestReturn command, SettlementKind kind,
                              ReturnOrderSnapshotPort.ReturnOrderSnapshot order) {
        if (!order.orderId().equals(command.orderId()) || !order.storeId().equals(command.storeId())
            || !"COMPLETED".equals(order.status()) || !"PAID".equals(order.paymentStatus())
            || !"CNY".equals(order.currency())) {
            throw new ServiceException("RET-ORDER-003: 原订单身份、门店、状态或币种不允许退货", 409);
        }
        if (kind == SettlementKind.CASH && order.cashPaymentId() == null
            || kind == SettlementKind.PROVIDER_NEUTRAL && order.cashPaymentId() != null) {
            throw new ServiceException("RET-ORDER-004: 退货结算类型必须继承原订单收款事实", 409);
        }
    }

    private String hashRequest(RequestReturn command, List<NormalizedLine> lines) {
        List<Object> values = new ArrayList<>(List.of(command.returnId(), command.orderId(), command.storeId(),
            command.terminalId(), command.refundShiftId(), command.warehouseId(), command.businessDate(),
            command.settlementKind(), command.paymentId() == null ? "<cash>" : command.paymentId(),
            command.reasonCode(), command.correlationId(), command.occurredAt()));
        lines.forEach(line -> { values.add(line.orderLineId()); values.add(line.quantity().toPlainString()); });
        return ReturnHash.sha256(ReturnHash.canonical(values));
    }

    private ReturnView view(ReturnRow row, boolean duplicate) {
        List<ReturnLineView> lines = mapper.listLines(tenantContext.requireTenantId(), row.returnId()).stream()
            .map(line -> new ReturnLineView(line.returnLineId(), line.orderLineId(), line.skuId(), line.unitId(),
                line.requestedQuantity(), line.grossAmountMinor(), line.recoveredDiscountMinor(),
                line.refundableAmountMinor(), line.cumulativeQuantity(), line.cumulativePayableAmountMinor()))
            .toList();
        return new ReturnView(row.returnId(), row.requestCommandId(), row.orderId(), row.storeId(), row.terminalId(), row.refundShiftId(),
            row.warehouseId(), row.businessDate(), row.settlementKind(), row.paymentId(), row.originalCashPaymentId(),
            row.promotionSnapshotId(), row.promotionSnapshotSha256(), row.status(), row.grossAmountMinor(),
            row.recoveredDiscountMinor(), row.refundableAmountMinor(), row.promotionEventId(), row.paymentEventId(),
            row.inventoryEventId(), row.requesterUserId(), row.approverUserId(), row.reasonCode(),
            row.correlationId(), row.recordVersion(), lines, row.updatedAt(), duplicate);
    }

    private ReturnRow requireRow(ReturnRow row) {
        if (row == null) throw new ServiceException("RET-NOT-FOUND: 退货退款不存在或不可见", 404);
        return row;
    }

    private void requireStatus(ReturnRow row, Status status) {
        if (!status.name().equals(row.status())) throw new ServiceException("RET-STATE-002: Saga检查点不匹配", 409);
    }

    private ServiceException conflict() {
        return new ServiceException("RET-STATE-003: Saga并发状态冲突", 409);
    }

    private record NormalizedLine(String orderLineId, BigDecimal quantity) { }
    private record NormalizedRequest(SettlementKind kind, List<NormalizedLine> lines) { }
}
