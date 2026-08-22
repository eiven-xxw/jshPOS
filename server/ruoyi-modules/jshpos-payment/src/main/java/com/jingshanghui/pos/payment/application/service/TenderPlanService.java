package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort.OrderPaymentSnapshot;
import com.jingshanghui.pos.order.application.port.TenderCashCollectionPort;
import com.jingshanghui.pos.order.application.port.TenderOrderSettlementPort;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CollectTenderAllocation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CancelTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RecoverTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderAllocationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderCollectResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderPlanResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderPlanView;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.domain.PaymentRules;
import com.jingshanghui.pos.payment.domain.TenderRules;
import com.jingshanghui.pos.payment.domain.TenderRules.AllocationSpec;
import com.jingshanghui.pos.payment.domain.TenderRules.AllocationState;
import com.jingshanghui.pos.payment.domain.TenderRules.PlanProjection;
import com.jingshanghui.pos.payment.domain.TenderStates.AllocationStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.PlanStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.TenderType;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Provider 无关组合支付计划、部分收取和失败关闭的正式应用服务。 */
@Service
@RequiredArgsConstructor
public class TenderPlanService {

    private static final String CREATE_PLAN = "CREATE_TENDER_PLAN";
    private static final String COLLECT_ALLOCATION = "COLLECT_TENDER_ALLOCATION";
    private static final String CANCEL_PLAN = "CANCEL_TENDER_PLAN";
    private static final String RECOVER_PLAN = "RECOVER_TENDER_PLAN";

    private final PaymentMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final PaymentOrderSnapshotPort orderSnapshotPort;
    private final TenderCashCollectionPort cashCollectionPort;
    private final TenderOrderSettlementPort orderSettlementPort;
    private final PaymentIdempotencyService idempotency;
    private final PaymentJournalService journal;
    private final UlidGenerator ulids;

    /** 冻结订单、金额、顺序和份额；后续只能推进状态，不能改写计划内容。 */
    @Transactional
    public TenderPlanResult create(CreateTenderPlan command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateCreateShape(command);
        authorizationService.requireStoreAccess(command.storeId());
        List<AllocationSpec> specs = TenderRules.validatePlan(command.receivableAmountMinor(), command.currency(),
            command.allocations().stream().map(item -> new AllocationSpec(item.allocationId(), item.sequenceNo(),
                tenderType(item.tenderType()), item.amountMinor())).toList());
        String requestHash = hashCreate(command, specs);
        TenderPlanResult duplicate = idempotency.find(principal.tenantId(), CREATE_PLAN,
            command.idempotencyKey(), requestHash, TenderPlanResult.class);
        if (duplicate != null) {
            return new TenderPlanResult(duplicate.plan(), duplicate.allocations(), true);
        }
        OrderPaymentSnapshot order = orderSnapshotPort.requireSnapshot(command.orderId());
        requirePayableOrder(command, principal, order);
        String contentHash = hashContent(command, specs, order);
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertTenderPlan(principal.tenantId(), command.planId(), command.orderId(),
            command.orderSnapshotSha256(), command.storeId(), command.terminalId(), order.shiftId(),
            order.businessDate(), command.receivableAmountMinor(), command.currency(), specs.size(), contentHash,
            command.commandId(), at);
        List<TenderAllocationView> allocations = new ArrayList<>();
        for (AllocationSpec spec : specs) {
            String allocationHash = hashAllocation(command.planId(), spec, command.currency());
            mapper.insertTenderAllocation(principal.tenantId(), spec.allocationId(), command.planId(),
                spec.sequenceNo(), spec.tenderType().name(), spec.amountMinor(), command.currency(), allocationHash, at);
            mapper.insertTenderHistory(ulids.next(), principal.tenantId(), command.planId(), spec.allocationId(),
                command.commandId(), "TENDER_ALLOCATION", null, AllocationStatus.PLANNED.name(), 1,
                allocationHash, principal.userId(), "PLAN_FROZEN", at);
            allocations.add(new TenderAllocationView(spec.allocationId(), command.planId(), spec.sequenceNo(),
                spec.tenderType().name(), AllocationStatus.PLANNED.name(), spec.amountMinor(), command.currency(),
                allocationHash, null, null, null, null, 1));
        }
        mapper.insertTenderHistory(ulids.next(), principal.tenantId(), command.planId(), null, command.commandId(),
            "TENDER_PLAN", null, PlanStatus.FROZEN.name(), 1, contentHash, principal.userId(), "PLAN_FROZEN", at);
        TenderPlanView plan = new TenderPlanView(command.planId(), command.orderId(),
            command.orderSnapshotSha256(), command.storeId(), command.terminalId(), order.shiftId(),
            order.businessDate(), PlanStatus.FROZEN.name(), command.receivableAmountMinor(), 0, 0,
            command.currency(), specs.size(), contentHash, command.commandId(), 1, at);
        journal.audit(principal.tenantId(), command.storeId(), "TENDER_PLAN_FROZEN", "TENDER_PLAN",
            command.planId(), principal.userId(), null, command.commandId(), null, PlanStatus.FROZEN.name(),
            command.receivableAmountMinor(), command.currency(), requestHash, "ORDER_PAYMENT", at);
        journal.event(principal.tenantId(), "tender.plan-frozen.v1", "TENDER_PLAN", command.planId(), 1,
            command.commandId(), planPayload(plan), at);
        TenderPlanResult result = new TenderPlanResult(plan, allocations, false);
        idempotency.save(principal.tenantId(), CREATE_PLAN, command.commandId(), command.idempotencyKey(),
            requestHash, command.planId(), result, at);
        return result;
    }

    /**
     * 严格按顺序收取份额。电子份额只形成可重复审计的 BLOCKED_EXTERNAL 结果；现金份额由
     * Order/Shift Owner 在同一数据库事务内追加事实。
     */
    @Transactional
    public TenderCollectResult collect(CollectTenderAllocation command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateCollectShape(command);
        TenderPlanView plan = requirePlan(mapper.lockTenderPlan(principal.tenantId(), command.planId()));
        authorizationService.requireStoreAccess(plan.storeId());
        TenderAllocationView allocation = requireAllocation(
            mapper.lockTenderAllocation(principal.tenantId(), command.allocationId()), plan.planId());
        String requestHash = hashCollect(command, allocation);
        TenderCollectResult duplicate = idempotency.find(principal.tenantId(), COLLECT_ALLOCATION,
            command.idempotencyKey(), requestHash, TenderCollectResult.class);
        if (duplicate != null) {
            return new TenderCollectResult(duplicate.planId(), duplicate.allocationId(), duplicate.tenderType(),
                duplicate.allocationStatus(), duplicate.planStatus(), duplicate.amountMinor(),
                duplicate.tenderedMinor(), duplicate.changeMinor(), duplicate.ownerFactId(),
                duplicate.outcome(), true);
        }
        List<TenderAllocationView> current = mapper.findTenderAllocations(principal.tenantId(), plan.planId());
        TenderRules.requirePlanCollectable(planStatus(plan.status()));
        TenderRules.requireCollectable(states(current), allocation.allocationId());
        LocalDateTime at = utc(command.occurredAt());
        if (TenderType.ELECTRONIC.name().equals(allocation.tenderType())) {
            if (command.tenderedMinor() != null) {
                throw new ServiceException("TENDER-ELECTRONIC-INPUT-001: 电子份额不接受现金实收", 409);
            }
            TenderCollectResult blocked = new TenderCollectResult(plan.planId(), allocation.allocationId(),
                allocation.tenderType(), allocation.status(), plan.status(), allocation.amountMinor(), null, null,
                null, "PAYMENT_EXTERNAL_BLOCKED", false);
            journal.audit(principal.tenantId(), plan.storeId(), "TENDER_ELECTRONIC_BLOCKED", "TENDER_ALLOCATION",
                allocation.allocationId(), principal.userId(), null, command.commandId(), allocation.status(),
                allocation.status(), allocation.amountMinor(), allocation.currency(), requestHash,
                "T2_PAY_002_BLOCKED", at);
            journal.event(principal.tenantId(), "tender.allocation-blocked.v1", "TENDER_ALLOCATION",
                allocation.allocationId(), allocation.recordVersion(), command.commandId(),
                Map.of("planId", plan.planId(), "allocationId", allocation.allocationId(),
                    "status", allocation.status(), "outcome", "PAYMENT_EXTERNAL_BLOCKED"), at);
            idempotency.save(principal.tenantId(), COLLECT_ALLOCATION, command.commandId(),
                command.idempotencyKey(), requestHash, allocation.allocationId(), blocked, at);
            return blocked;
        }
        long tendered = command.tenderedMinor() == null ? 0 : command.tenderedMinor();
        if (tendered < allocation.amountMinor()) {
            throw new ServiceException("TENDER-CASH-002: 现金实收不得小于冻结份额", 409);
        }
        var cash = cashCollectionPort.collect(new TenderCashCollectionPort.CashTenderCommand(plan.planId(),
            allocation.allocationId(), plan.orderId(), plan.storeId(), plan.terminalId(), plan.shiftId(),
            plan.businessDate(), allocation.amountMinor(), tendered, requestHash, command.commandId(),
            command.occurredAt()));
        if (mapper.updateTenderAllocation(principal.tenantId(), allocation.allocationId(),
            AllocationStatus.SUCCEEDED.name(), cash.cashTenderId(), command.commandId(), requestHash,
            allocation.recordVersion()) != 1) {
            throw new ServiceException("TENDER-CONCURRENCY-001: 份额状态发生并发冲突", 409);
        }
        mapper.insertTenderHistory(ulids.next(), principal.tenantId(), plan.planId(), allocation.allocationId(),
            command.commandId(), "TENDER_ALLOCATION", allocation.status(), AllocationStatus.SUCCEEDED.name(),
            allocation.recordVersion() + 1, requestHash, principal.userId(), "CASH_CONFIRMED", at);
        List<AllocationState> projectedStates = states(current).stream().map(item ->
            item.allocationId().equals(allocation.allocationId())
                ? new AllocationState(item.allocationId(), item.sequenceNo(), item.tenderType(),
                    AllocationStatus.SUCCEEDED, item.amountMinor()) : item).toList();
        PlanProjection projection = TenderRules.project(projectedStates, plan.receivableAmountMinor());
        if (mapper.updateTenderPlanProjection(principal.tenantId(), plan.planId(), projection.status().name(),
            projection.succeededAmountMinor(), projection.occupiedAmountMinor(), plan.recordVersion()) != 1) {
            throw new ServiceException("TENDER-CONCURRENCY-002: 支付计划状态发生并发冲突", 409);
        }
        mapper.insertTenderHistory(ulids.next(), principal.tenantId(), plan.planId(), null, command.commandId(),
            "TENDER_PLAN", plan.status(), projection.status().name(), plan.recordVersion() + 1, requestHash,
            principal.userId(), "ALLOCATION_CONFIRMED", at);
        if (projection.status() == PlanStatus.PAID) {
            orderSettlementPort.complete(new TenderOrderSettlementPort.OrderSettlementCommand(plan.planId(),
                plan.orderId(), plan.storeId(), plan.terminalId(), plan.orderSnapshotSha256(), plan.contentSha256(),
                plan.receivableAmountMinor(), plan.currency(), command.commandId(), command.occurredAt()));
        }
        journal.audit(principal.tenantId(), plan.storeId(), "TENDER_CASH_SUCCEEDED", "TENDER_ALLOCATION",
            allocation.allocationId(), principal.userId(), null, command.commandId(), allocation.status(),
            AllocationStatus.SUCCEEDED.name(), allocation.amountMinor(), allocation.currency(), requestHash,
            "CASH_CONFIRMED", at);
        journal.event(principal.tenantId(), projection.status() == PlanStatus.PAID
                ? "tender.plan-fully-paid.v1" : "tender.allocation-succeeded.v1",
            "TENDER_PLAN", plan.planId(), plan.recordVersion() + 1, command.commandId(),
            Map.of("planId", plan.planId(), "allocationId", allocation.allocationId(),
                "status", projection.status().name(), "succeededAmountMinor", projection.succeededAmountMinor(),
                "receivableAmountMinor", plan.receivableAmountMinor(), "currency", plan.currency()), at);
        TenderCollectResult result = new TenderCollectResult(plan.planId(), allocation.allocationId(),
            allocation.tenderType(), AllocationStatus.SUCCEEDED.name(), projection.status().name(),
            allocation.amountMinor(), tendered, cash.changeMinor(), cash.cashTenderId(), "SUCCEEDED", false);
        idempotency.save(principal.tenantId(), COLLECT_ALLOCATION, command.commandId(), command.idempotencyKey(),
            requestHash, allocation.allocationId(), result, at);
        return result;
    }

    /** 仅无成功、处理中或 UNKNOWN 份额时取消原计划；所有事实保留。 */
    @Transactional
    public TenderPlanResult cancel(CancelTenderPlan command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateAction(command.commandId(), command.idempotencyKey(), command.planId(),
            command.reasonCode(), command.occurredAt());
        String requestHash = hashAction(command.planId(), command.reasonCode(), command.occurredAt());
        TenderPlanResult duplicate = idempotency.find(principal.tenantId(), CANCEL_PLAN,
            command.idempotencyKey(), requestHash, TenderPlanResult.class);
        if (duplicate != null) {
            return new TenderPlanResult(duplicate.plan(), duplicate.allocations(), true);
        }
        TenderPlanView plan = requirePlan(mapper.lockTenderPlan(principal.tenantId(), command.planId()));
        authorizationService.requireStoreAccess(plan.storeId());
        List<TenderAllocationView> current = mapper.findTenderAllocations(principal.tenantId(), plan.planId());
        TenderRules.requireCancellable(planStatus(plan.status()), states(current));
        LocalDateTime at = utc(command.occurredAt());
        List<TenderAllocationView> cancelled = new ArrayList<>();
        for (TenderAllocationView allocation : current) {
            if (AllocationStatus.CANCELLED.name().equals(allocation.status())) {
                cancelled.add(allocation);
                continue;
            }
            if (mapper.cancelTenderAllocation(principal.tenantId(), allocation.allocationId(),
                allocation.recordVersion()) != 1) {
                throw new ServiceException("TENDER-CONCURRENCY-003: 取消份额发生并发冲突", 409);
            }
            mapper.insertTenderHistory(ulids.next(), principal.tenantId(), plan.planId(),
                allocation.allocationId(), command.commandId(), "TENDER_ALLOCATION", allocation.status(),
                AllocationStatus.CANCELLED.name(), allocation.recordVersion() + 1, requestHash,
                principal.userId(), command.reasonCode(), at);
            cancelled.add(copyAllocation(allocation, AllocationStatus.CANCELLED.name()));
        }
        if (mapper.updateTenderPlanProjection(principal.tenantId(), plan.planId(), PlanStatus.CANCELLED.name(),
            0, 0, plan.recordVersion()) != 1) {
            throw new ServiceException("TENDER-CONCURRENCY-004: 取消计划发生并发冲突", 409);
        }
        TenderPlanView cancelledPlan = copyPlan(plan, PlanStatus.CANCELLED.name(), 0, 0);
        mapper.insertTenderHistory(ulids.next(), principal.tenantId(), plan.planId(), null, command.commandId(),
            "TENDER_PLAN", plan.status(), PlanStatus.CANCELLED.name(), plan.recordVersion() + 1,
            requestHash, principal.userId(), command.reasonCode(), at);
        journal.audit(principal.tenantId(), plan.storeId(), "TENDER_PLAN_CANCELLED", "TENDER_PLAN",
            plan.planId(), principal.userId(), null, command.commandId(), plan.status(),
            PlanStatus.CANCELLED.name(), plan.receivableAmountMinor(), plan.currency(), requestHash,
            command.reasonCode(), at);
        journal.event(principal.tenantId(), "tender.plan-cancelled.v1", "TENDER_PLAN", plan.planId(),
            plan.recordVersion() + 1, command.commandId(), planPayload(cancelledPlan), at);
        TenderPlanResult result = new TenderPlanResult(cancelledPlan, cancelled, false);
        idempotency.save(principal.tenantId(), CANCEL_PLAN, command.commandId(), command.idempotencyKey(),
            requestHash, plan.planId(), result, at);
        return result;
    }

    /**
     * 对原计划执行受审计恢复检查。该操作不改变份额事实；PROCESSING/UNKNOWN 仍必须由
     * 原 Provider 查询、验签回调或账单观察收敛。
     */
    @Transactional
    public TenderPlanResult recover(RecoverTenderPlan command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validateAction(command.commandId(), command.idempotencyKey(), command.planId(),
            command.reasonCode(), command.occurredAt());
        String requestHash = hashAction(command.planId(), command.reasonCode(), command.occurredAt());
        TenderPlanResult duplicate = idempotency.find(principal.tenantId(), RECOVER_PLAN,
            command.idempotencyKey(), requestHash, TenderPlanResult.class);
        if (duplicate != null) {
            return new TenderPlanResult(duplicate.plan(), duplicate.allocations(), true);
        }
        TenderPlanView plan = requirePlan(mapper.lockTenderPlan(principal.tenantId(), command.planId()));
        authorizationService.requireStoreAccess(plan.storeId());
        List<TenderAllocationView> allocations = mapper.findTenderAllocations(principal.tenantId(), plan.planId());
        LocalDateTime at = utc(command.occurredAt());
        journal.audit(principal.tenantId(), plan.storeId(), "TENDER_RECOVERY_INSPECTED", "TENDER_PLAN",
            plan.planId(), principal.userId(), null, command.commandId(), plan.status(), plan.status(),
            plan.occupiedAmountMinor(), plan.currency(), requestHash, command.reasonCode(), at);
        journal.event(principal.tenantId(), "tender.plan-recovery-inspected.v1", "TENDER_PLAN", plan.planId(),
            plan.recordVersion(), command.commandId(), Map.of("planId", plan.planId(), "status", plan.status(),
                "outcome", "QUERY_OR_OBSERVE_ORIGINAL"), at);
        TenderPlanResult result = new TenderPlanResult(plan, allocations, false);
        idempotency.save(principal.tenantId(), RECOVER_PLAN, command.commandId(), command.idempotencyKey(),
            requestHash, plan.planId(), result, at);
        return result;
    }

    @Transactional(readOnly = true)
    public TenderPlanResult find(String planId) {
        PaymentRules.requireUlid(planId, "planId");
        String tenantId = tenantContext.requireTenantId();
        TenderPlanView plan = requirePlan(mapper.findTenderPlan(tenantId, planId));
        authorizationService.requireStoreAccess(plan.storeId());
        return new TenderPlanResult(plan, mapper.findTenderAllocations(tenantId, planId), false);
    }

    private void validateCreateShape(CreateTenderPlan command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.planId(), "planId");
        PaymentRules.requireUlid(command.orderId(), "orderId");
        PaymentRules.requireUlid(command.terminalId(), "terminalId");
        PaymentRules.requireHash(command.orderSnapshotSha256());
        if (command.storeId() == null || command.storeId() <= 0 || command.occurredAt() == null) {
            throw new ServiceException("TENDER-INPUT-001: 支付计划上下文不完整", 409);
        }
    }

    private void validateCollectShape(CollectTenderAllocation command) {
        PaymentRules.requireUlid(command.commandId(), "commandId");
        PaymentRules.requireIdempotencyKey(command.idempotencyKey());
        PaymentRules.requireUlid(command.planId(), "planId");
        PaymentRules.requireUlid(command.allocationId(), "allocationId");
        if (command.occurredAt() == null || command.tenderedMinor() != null && command.tenderedMinor() < 0) {
            throw new ServiceException("TENDER-INPUT-002: 份额收取上下文不完整", 409);
        }
    }

    private void validateAction(String commandId, String idempotencyKey, String planId,
                                String reasonCode, java.time.Instant occurredAt) {
        PaymentRules.requireUlid(commandId, "commandId");
        PaymentRules.requireIdempotencyKey(idempotencyKey);
        PaymentRules.requireUlid(planId, "planId");
        if (reasonCode == null || !reasonCode.matches("^[A-Z0-9_]{2,32}$") || occurredAt == null) {
            throw new ServiceException("TENDER-INPUT-003: 取消或恢复上下文不完整", 409);
        }
    }

    private void requirePayableOrder(CreateTenderPlan command, TrustedPrincipal principal,
                                     OrderPaymentSnapshot order) {
        boolean payable = order != null && ("PENDING_PAYMENT".equals(order.status())
            || "CONFIRMED".equals(order.status())) && "UNPAID".equals(order.paymentStatus());
        if (!payable || order.shiftId() == null || order.cashierUserId() == null || order.businessDate() == null
            || order.snapshotSha256() == null || !order.storeId().equals(command.storeId())
            || !order.terminalId().equals(command.terminalId()) || !order.cashierUserId().equals(principal.userId())
            || order.receivableAmountMinor() != command.receivableAmountMinor()
            || !order.currency().equals(command.currency())
            || !order.snapshotSha256().equals(command.orderSnapshotSha256())) {
            throw new ServiceException("TENDER-ORDER-001: 原单状态、可信上下文、金额或摘要不匹配", 409);
        }
    }

    private List<AllocationState> states(List<TenderAllocationView> views) {
        return views.stream().map(item -> new AllocationState(item.allocationId(), item.sequenceNo(),
            tenderType(item.tenderType()), allocationStatus(item.status()), item.amountMinor())).toList();
    }

    private TenderPlanView requirePlan(TenderPlanView value) {
        if (value == null) throw new ServiceException("TENDER-NOT-VISIBLE: 支付计划不存在或不可见", 404);
        return value;
    }

    private TenderAllocationView requireAllocation(TenderAllocationView value, String planId) {
        if (value == null || !value.planId().equals(planId)) {
            throw new ServiceException("TENDER-NOT-VISIBLE: 支付份额不存在或不可见", 404);
        }
        return value;
    }

    private TenderType tenderType(String value) {
        try {
            return TenderType.valueOf(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("TENDER-TYPE-001: 支付份额类型非法", 409);
        }
    }

    private AllocationStatus allocationStatus(String value) {
        try {
            return AllocationStatus.valueOf(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("TENDER-STATE-002: 支付份额状态非法", 409);
        }
    }

    private PlanStatus planStatus(String value) {
        try {
            return PlanStatus.valueOf(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("TENDER-STATE-003: 支付计划状态非法", 409);
        }
    }

    private String hashCreate(CreateTenderPlan command, List<AllocationSpec> specs) {
        List<Object> values = new ArrayList<>(List.of(command.planId(), command.orderId(),
            command.orderSnapshotSha256(), command.storeId(), command.terminalId(),
            command.receivableAmountMinor(), command.currency(), command.occurredAt()));
        specs.forEach(item -> values.addAll(List.of(item.allocationId(), item.sequenceNo(),
            item.tenderType(), item.amountMinor())));
        return PaymentHash.sha256(PaymentHash.canonical(values));
    }

    private String hashContent(CreateTenderPlan command, List<AllocationSpec> specs, OrderPaymentSnapshot order) {
        return TenderRules.contentSha256(command.planId(), command.orderId(), command.orderSnapshotSha256(),
            command.storeId(), command.terminalId(), order.shiftId(), order.businessDate(),
            command.receivableAmountMinor(), command.currency(), specs);
    }

    private String hashAllocation(String planId, AllocationSpec spec, String currency) {
        return PaymentHash.sha256(PaymentHash.canonical(List.of(planId, spec.allocationId(),
            spec.sequenceNo(), spec.tenderType(), spec.amountMinor(), currency)));
    }

    private String hashCollect(CollectTenderAllocation command, TenderAllocationView allocation) {
        List<Object> values = new ArrayList<>();
        values.add(command.planId());
        values.add(command.allocationId());
        values.add(allocation.amountMinor());
        values.add(allocation.currency());
        values.add(command.tenderedMinor());
        values.add(command.occurredAt());
        return PaymentHash.sha256(PaymentHash.canonical(values));
    }

    private String hashAction(String planId, String reasonCode, java.time.Instant occurredAt) {
        return PaymentHash.sha256(PaymentHash.canonical(List.of(planId, reasonCode, occurredAt)));
    }

    private TenderPlanView copyPlan(TenderPlanView plan, String status, long succeeded, long occupied) {
        return new TenderPlanView(plan.planId(), plan.orderId(), plan.orderSnapshotSha256(), plan.storeId(),
            plan.terminalId(), plan.shiftId(), plan.businessDate(), status, plan.receivableAmountMinor(),
            succeeded, occupied, plan.currency(), plan.allocationCount(), plan.contentSha256(),
            plan.correlationId(), plan.recordVersion() + 1, plan.frozenAt());
    }

    private TenderAllocationView copyAllocation(TenderAllocationView allocation, String status) {
        return new TenderAllocationView(allocation.allocationId(), allocation.planId(), allocation.sequenceNo(),
            allocation.tenderType(), status, allocation.amountMinor(), allocation.currency(),
            allocation.allocationSha256(), allocation.ownerFactId(), allocation.observationRef(),
            allocation.collectionCommandId(), allocation.collectionRequestSha256(),
            allocation.recordVersion() + 1);
    }

    private Map<String, Object> planPayload(TenderPlanView plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("planId", plan.planId());
        payload.put("orderId", plan.orderId());
        payload.put("status", plan.status());
        payload.put("receivableAmountMinor", plan.receivableAmountMinor());
        payload.put("currency", plan.currency());
        payload.put("contentSha256", plan.contentSha256());
        payload.put("allocationCount", plan.allocationCount());
        return payload;
    }

    private LocalDateTime utc(java.time.Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
