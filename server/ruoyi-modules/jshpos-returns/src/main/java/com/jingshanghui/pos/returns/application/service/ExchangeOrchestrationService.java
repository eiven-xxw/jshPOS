package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.ApproveExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.CreateExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.OwnerObservation;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.RecoverExchange;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeLegView;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeView;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ExchangeRules;
import com.jingshanghui.pos.returns.domain.ExchangeStates.LegType;
import com.jingshanghui.pos.returns.domain.ExchangeStates.Status;
import com.jingshanghui.pos.returns.domain.ReturnHash;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.EventWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.ExchangeWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.IdempotencyWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.InboxWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.LegWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.OutboxWrite;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ExchangeMapper;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ExchangeMapper.ExchangeRow;
import com.jingshanghui.pos.returns.infrastructure.persistence.mapper.ExchangeMapper.IdempotencyRow;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EXG-001 换货关联 Saga Owner。
 * 本服务只冻结并推进 RETURN/SALE 两条腿，资金、库存、促销和订单事实仍由原 Owner 写入。
 */
@Service
@RequiredArgsConstructor
public class ExchangeOrchestrationService {
    private static final String CREATE_COMMAND = "CREATE_EXCHANGE";
    private final ExchangeMapper mapper;
    private final ReturnOrchestrationService returns;
    private final ExchangeOrderSnapshotPort orders;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService audit;
    private final UlidGenerator ulids;

    /** 原子冻结换货头、两条只追加腿、首事件、幂等结果和 Outbox。 */
    @Transactional
    public ExchangeView create(CreateExchange command) {
        validateCreate(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.storeId());
        ReturnView sourceReturn = returns.find(command.returnId());
        requireSourceReturn(command, sourceReturn);
        String requestHash = requestHash(command);
        IdempotencyRow existing = mapper.findIdempotency(principal.tenantId(), CREATE_COMMAND,
            command.idempotencyKey());
        if (existing != null) {
            if (!existing.requestSha256().equals(requestHash)) throw contentConflict();
            return view(requireRow(mapper.findExchange(principal.tenantId(), existing.exchangeId())), true);
        }
        LocalDateTime at = utc(command.occurredAt());
        mapper.insertExchange(new ExchangeWrite(command.exchangeId(), principal.tenantId(),
            command.idempotencyKey(), requestHash, command.returnId(), command.originalOrderId(),
            command.originalReturnCommandId(), command.newOrderId(), command.newSaleCommandId(),
            command.storeId(), command.terminalId(), command.businessDate(), command.expectedRefundAmountMinor(),
            command.expectedSaleReceivableMinor(), command.quoteFingerprint(), command.newSalePlanSha256(),
            command.reasonCode(), principal.userId(), command.correlationId(), at));
        String returnLegHash = ReturnHash.sha256(ReturnHash.canonical(List.of(LegType.RETURN,
            command.returnId(), command.originalReturnCommandId(), command.expectedRefundAmountMinor())));
        String saleLegHash = ReturnHash.sha256(ReturnHash.canonical(List.of(LegType.SALE,
            command.newOrderId(), command.newSaleCommandId(), command.expectedSaleReceivableMinor(),
            command.quoteFingerprint(), command.newSalePlanSha256())));
        mapper.insertLeg(new LegWrite(ulids.next(), principal.tenantId(), command.exchangeId(),
            LegType.RETURN.name(), "RETURN", command.returnId(), command.originalReturnCommandId(),
            command.expectedRefundAmountMinor(), returnLegHash, at));
        mapper.insertLeg(new LegWrite(ulids.next(), principal.tenantId(), command.exchangeId(),
            LegType.SALE.name(), "ORDER", command.newOrderId(), command.newSaleCommandId(),
            command.expectedSaleReceivableMinor(), saleLegHash, at));
        mapper.insertIdempotency(new IdempotencyWrite(principal.tenantId(), CREATE_COMMAND,
            command.idempotencyKey(), requestHash, command.exchangeId(), at));
        appendEvent(command.commandId(), principal, command.exchangeId(), null, Status.DRAFT,
            "EXCHANGE", command.exchangeId(), command.commandId(), requestHash, 1,
            command.reasonCode(), at);
        appendOutbox(principal.tenantId(), command.commandId(), "exchange.created.v1", command.exchangeId(),
            1, command.correlationId(), Map.of("exchangeId", command.exchangeId(),
                "returnId", command.returnId(), "newOrderId", command.newOrderId()), at);
        audit.append("EXCHANGE_CREATED", "EXCHANGE", command.exchangeId(), null, Status.DRAFT.name(),
            Map.of("returnId", command.returnId(), "newOrderId", command.newOrderId(),
                "storeId", command.storeId()));
        return view(requireRow(mapper.findExchange(principal.tenantId(), command.exchangeId())), false);
    }

    /** 审批人与申请人分离；审批后只进入原退货观察检查点，不创建退款命令。 */
    @Transactional
    public ExchangeView approve(ApproveExchange command) {
        validateApprove(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExchangeRow current = requireRow(mapper.lockExchange(principal.tenantId(), command.exchangeId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (principal.userId().equals(current.requesterUserId())) {
            throw new ServiceException("EXG-APPROVE-001: 申请人与审批人必须分离", 409);
        }
        if (!current.correlationId().equals(command.correlationId())) {
            throw new ServiceException("EXG-APPROVE-002: 关联标识与冻结值不一致", 409);
        }
        ExchangeRow approved = advance(principal, current, command.commandId(), Status.APPROVED,
            principal.userId(), null, null, null, command.reasonCode(), "EXCHANGE", current.exchangeId(),
            command.commandId(), hash(command.commandId(), command.reasonCode()), command.occurredAt());
        ExchangeRow pending = advance(principal, approved, ulids.next(), Status.RETURN_PENDING,
            principal.userId(), null, null, null, "OBSERVE_ORIGINAL_RETURN", "RETURN", approved.returnId(),
            approved.originalReturnCommandId(), hash(approved.returnId(), approved.originalReturnCommandId()),
            command.occurredAt());
        audit.append("EXCHANGE_APPROVED", "EXCHANGE", current.exchangeId(), Status.DRAFT.name(),
            Status.RETURN_PENDING.name(), Map.of("approverUserId", principal.userId()));
        return view(pending, false);
    }

    /** 接受 Return Owner 权威观察；UNKNOWN 只停在观察态，完成后才开放新销售腿。 */
    @Transactional
    public ExchangeView acceptReturn(OwnerObservation observation) {
        validateObservation(observation);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExchangeRow current = requireRow(mapper.lockExchange(principal.tenantId(), observation.exchangeId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!current.returnId().equals(observation.ownerAggregateId())) {
            throw new ServiceException("EXG-RETURN-001: Return Owner身份不一致", 409);
        }
        ReturnView authority = returns.find(current.returnId());
        if (!authority.requestCommandId().equals(current.originalReturnCommandId())
            || !authority.status().equals(observation.ownerStatus())
            || (authority.refundableAmountMinor() == null ? 0 : authority.refundableAmountMinor())
                != observation.amountMinor()) {
            throw new ServiceException("EXG-RETURN-007: Return权威状态、命令或金额与观察不一致", 409);
        }
        if (!acceptInbox(principal.tenantId(), observation, "RETURN")) return view(current, true);
        Status before = Status.valueOf(current.status());
        if (before != Status.RETURN_PENDING && before != Status.RETURN_UNKNOWN) {
            throw new ServiceException("EXG-RETURN-002: 当前检查点不接受退货观察", 409);
        }
        if ("PAYMENT_UNKNOWN".equals(observation.ownerStatus())) {
            if (before == Status.RETURN_UNKNOWN) return view(current, false);
            return view(advance(principal, current, observation.observationId(), Status.RETURN_UNKNOWN,
                null, null, null, null, "RETURN_UNKNOWN_QUERY_ONLY", "RETURN", current.returnId(),
                observation.observationId(), observation.payloadSha256(), observation.observedAt()), false);
        }
        if ("COMPLETED".equals(observation.ownerStatus())) {
            ExchangeRules.requireObservedAmount(current.expectedRefundAmountMinor(), observation.amountMinor(), "Return");
            ExchangeRow returned = advance(principal, current, observation.observationId(), Status.RETURN_COMPLETED,
                null, observation.amountMinor(), null, null, "RETURN_COMPLETED", "RETURN", current.returnId(),
                observation.observationId(), observation.payloadSha256(), observation.observedAt());
            ExchangeRow salePending = advance(principal, returned, ulids.next(), Status.SALE_PENDING,
                null, null, null, null, "OBSERVE_FROZEN_NEW_SALE", "ORDER", returned.newOrderId(),
                returned.newSaleCommandId(), hash(returned.newOrderId(), returned.newSaleCommandId()),
                observation.observedAt());
            return view(salePending, false);
        }
        if ("FAILED".equals(observation.ownerStatus())) {
            return view(advance(principal, current, observation.observationId(), Status.MANUAL_RECOVERY_REQUIRED,
                null, null, null, null, "RETURN_TERMINAL_FAILURE", "RETURN", current.returnId(),
                observation.observationId(), observation.payloadSha256(), observation.observedAt()), false);
        }
        throw new ServiceException("EXG-RETURN-003: Return观察状态未准入", 409);
    }

    /** 接受 Order Owner 权威新销售观察；只有冻结金额和报价指纹匹配才完成。 */
    @Transactional
    public ExchangeView acceptSale(OwnerObservation observation) {
        validateObservation(observation);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExchangeRow current = requireRow(mapper.lockExchange(principal.tenantId(), observation.exchangeId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (!current.newOrderId().equals(observation.ownerAggregateId())) {
            throw new ServiceException("EXG-SALE-001: Order Owner身份不一致", 409);
        }
        var authority = orders.find(current.newOrderId());
        if (authority == null || !authority.storeId().equals(current.storeId())
            || !authority.terminalId().equals(current.terminalId())
            || !authority.businessDate().equals(current.businessDate())
            || !"CNY".equals(authority.currency())
            || authority.receivableAmountMinor() != observation.amountMinor()
            || !authority.orderSnapshotSha256().equals(observation.ownerSnapshotSha256())
            || (!current.quoteFingerprint().equals(authority.quoteFingerprint())
                && !current.quoteFingerprint().equals(authority.settlementFingerprint()))) {
            throw new ServiceException("EXG-SALE-004: Order权威范围、金额、报价或摘要与观察不一致", 409);
        }
        String authoritativeStatus = "UNKNOWN".equals(authority.paymentStatus()) ? "UNKNOWN"
            : ("COMPLETED".equals(authority.status()) && "PAID".equals(authority.paymentStatus()))
                ? "COMPLETED" : authority.status();
        if (!authoritativeStatus.equals(observation.ownerStatus())) {
            throw new ServiceException("EXG-SALE-005: Order权威状态与观察不一致", 409);
        }
        if (!acceptInbox(principal.tenantId(), observation, "ORDER")) return view(current, true);
        Status before = Status.valueOf(current.status());
        if (before != Status.SALE_PENDING && before != Status.SALE_UNKNOWN) {
            throw new ServiceException("EXG-SALE-002: 当前检查点不接受新销售观察", 409);
        }
        if ("UNKNOWN".equals(observation.ownerStatus())) {
            if (before == Status.SALE_UNKNOWN) return view(current, false);
            return view(advance(principal, current, observation.observationId(), Status.SALE_UNKNOWN,
                null, null, null, null, "SALE_UNKNOWN_QUERY_ONLY", "ORDER", current.newOrderId(),
                observation.observationId(), observation.payloadSha256(), observation.observedAt()), false);
        }
        if ("COMPLETED".equals(observation.ownerStatus())) {
            ExchangeRules.requireObservedAmount(current.expectedSaleReceivableMinor(), observation.amountMinor(), "Order");
            ExchangeRules.requireHash(observation.ownerSnapshotSha256(), "ownerSnapshotSha256");
            ExchangeRow completed = advance(principal, current, observation.observationId(), Status.COMPLETED,
                null, null, observation.amountMinor(), observation.ownerSnapshotSha256(), "NEW_SALE_COMPLETED",
                "ORDER", current.newOrderId(), observation.observationId(), observation.payloadSha256(),
                observation.observedAt());
            appendOutbox(principal.tenantId(), ulids.next(), "exchange.completed.v1", current.exchangeId(),
                completed.recordVersion(), current.correlationId(), Map.of("exchangeId", current.exchangeId(),
                    "returnId", current.returnId(), "newOrderId", current.newOrderId()), utc(observation.observedAt()));
            audit.append("EXCHANGE_COMPLETED", "EXCHANGE", current.exchangeId(), current.status(),
                Status.COMPLETED.name(), Map.of("actualRefundAmountMinor", completed.actualRefundAmountMinor(),
                    "actualSaleReceivableMinor", completed.actualSaleReceivableMinor()));
            return view(completed, false);
        }
        if ("FAILED".equals(observation.ownerStatus()) || "CANCELLED".equals(observation.ownerStatus())) {
            return view(advance(principal, current, observation.observationId(), Status.MANUAL_RECOVERY_REQUIRED,
                null, null, null, null, "SALE_TERMINAL_FAILURE", "ORDER", current.newOrderId(),
                observation.observationId(), observation.payloadSha256(), observation.observedAt()), false);
        }
        throw new ServiceException("EXG-SALE-003: Order观察状态未准入", 409);
    }

    /** 人工恢复只恢复已有检查点；不接收也不生成新的退款或销售命令。 */
    @Transactional
    public ExchangeView recover(RecoverExchange command) {
        validateRecover(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ExchangeRow current = requireRow(mapper.lockExchange(principal.tenantId(), command.exchangeId()));
        authorizationService.requireStoreAccess(current.storeId());
        if (Status.valueOf(current.status()) != Status.MANUAL_RECOVERY_REQUIRED) {
            throw new ServiceException("EXG-RECOVER-001: 仅人工恢复检查点可执行恢复", 409);
        }
        Status target;
        String owner;
        String aggregate;
        String ownerCommand;
        if (LegType.RETURN.name().equals(command.targetLeg()) && current.actualRefundAmountMinor() == null) {
            target = Status.RETURN_PENDING; owner = "RETURN"; aggregate = current.returnId();
            ownerCommand = current.originalReturnCommandId();
        } else if (LegType.SALE.name().equals(command.targetLeg())
            && current.actualRefundAmountMinor() != null
            && current.actualRefundAmountMinor() == current.expectedRefundAmountMinor()) {
            target = Status.SALE_PENDING; owner = "ORDER"; aggregate = current.newOrderId();
            ownerCommand = current.newSaleCommandId();
        } else {
            throw new ServiceException("EXG-RECOVER-002: 恢复腿与已完成事实不一致", 409);
        }
        ExchangeRow recovered = advance(principal, current, command.commandId(), target, principal.userId(),
            null, null, null, command.reasonCode(), owner, aggregate, ownerCommand,
            hash(command.commandId(), command.targetLeg(), command.reasonCode()), command.occurredAt());
        audit.append("EXCHANGE_RECOVERED", "EXCHANGE", current.exchangeId(), current.status(), target.name(),
            Map.of("targetLeg", command.targetLeg(), "reasonCode", command.reasonCode()));
        return view(recovered, false);
    }

    @Transactional(readOnly = true)
    public ExchangeView find(String exchangeId) {
        ExchangeRules.requireUlid(exchangeId, "exchangeId");
        ExchangeRow row = requireRow(mapper.findExchange(tenantContext.requireTenantId(), exchangeId));
        authorizationService.requireStoreAccess(row.storeId());
        return view(row, false);
    }

    private ExchangeRow advance(TrustedPrincipal principal, ExchangeRow current, String eventId, Status next,
                                Long approver, Long actualRefund, Long actualSale, String saleSnapshot,
                                String reason, String owner, String ownerAggregate, String ownerEvent,
                                String payloadHash, Instant occurredAt) {
        Status before = Status.valueOf(current.status());
        ExchangeRules.requireTransition(before, next);
        LocalDateTime at = utc(occurredAt);
        if (mapper.advance(principal.tenantId(), current.exchangeId(), current.recordVersion(), current.status(),
            next.name(), approver, actualRefund, actualSale, saleSnapshot, at) != 1) throw stateConflict();
        appendEvent(eventId, principal, current.exchangeId(), before, next, owner, ownerAggregate, ownerEvent,
            payloadHash, current.recordVersion() + 1, reason, at);
        return requireRow(mapper.findExchange(principal.tenantId(), current.exchangeId()));
    }

    private void appendEvent(String eventId, TrustedPrincipal principal, String exchangeId, Status before,
                             Status after, String owner, String aggregate, String ownerEvent, String payloadHash,
                             long version, String reason, LocalDateTime at) {
        mapper.insertEvent(new EventWrite(eventId, principal.tenantId(), exchangeId,
            before == null ? null : before.name(), after.name(), owner, aggregate, ownerEvent,
            payloadHash, version, principal.userId(), reason, at));
    }

    private boolean acceptInbox(String tenantId, OwnerObservation observation, String owner) {
        var existing = mapper.findInbox(tenantId, observation.observationId());
        if (existing != null) {
            if (!owner.equals(existing.ownerCode())
                || !observation.ownerAggregateId().equals(existing.aggregateId())
                || !observation.payloadSha256().equals(existing.payloadSha256())) throw contentConflict();
            return false;
        }
        mapper.insertInbox(new InboxWrite(observation.observationId(), tenantId, owner,
            observation.ownerAggregateId(), observation.payloadSha256(), utc(observation.observedAt())));
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

    private void requireSourceReturn(CreateExchange command, ReturnView source) {
        if (!source.orderId().equals(command.originalOrderId()) || !source.storeId().equals(command.storeId())
            || source.requestCommandId() == null
            || !source.requestCommandId().equals(command.originalReturnCommandId())) {
            throw new ServiceException("EXG-RETURN-004: 原退货身份、命令或门店不一致", 409);
        }
        if (source.refundableAmountMinor() != null
            && source.refundableAmountMinor() != command.expectedRefundAmountMinor()) {
            throw new ServiceException("EXG-RETURN-005: 原退货冻结金额不一致", 409);
        }
        if ("FAILED".equals(source.status())) {
            throw new ServiceException("EXG-RETURN-006: 失败退货不得建立换货", 409);
        }
    }

    private void validateCreate(CreateExchange command) {
        if (command == null) throw new ServiceException("EXG-INPUT-001: 创建命令必填", 400);
        ExchangeRules.requireUlid(command.commandId(), "commandId");
        ExchangeRules.requireUlid(command.exchangeId(), "exchangeId");
        ExchangeRules.requireUlid(command.returnId(), "returnId");
        ExchangeRules.requireUlid(command.originalReturnCommandId(), "originalReturnCommandId");
        ExchangeRules.requireUlid(command.newSaleCommandId(), "newSaleCommandId");
        ExchangeRules.requireUlid(command.terminalId(), "terminalId");
        ExchangeRules.requireUlid(command.correlationId(), "correlationId");
        ExchangeRules.requireDistinctOrders(command.originalOrderId(), command.newOrderId());
        ExchangeRules.requireExpectedAmounts(command.expectedRefundAmountMinor(), command.expectedSaleReceivableMinor());
        ExchangeRules.requireHash(command.quoteFingerprint(), "quoteFingerprint");
        ExchangeRules.requireHash(command.newSalePlanSha256(), "newSalePlanSha256");
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()
            || command.idempotencyKey().length() > 96 || command.storeId() == null || command.storeId() <= 0
            || command.businessDate() == null || command.occurredAt() == null
            || command.reasonCode() == null || !command.reasonCode().matches("^[A-Z0-9_]{2,32}$")) {
            throw new ServiceException("EXG-INPUT-002: 创建字段非法", 409);
        }
    }

    private void validateApprove(ApproveExchange command) {
        if (command == null) throw new ServiceException("EXG-APPROVE-003: 审批命令必填", 400);
        ExchangeRules.requireUlid(command.commandId(), "commandId");
        ExchangeRules.requireUlid(command.exchangeId(), "exchangeId");
        ExchangeRules.requireUlid(command.correlationId(), "correlationId");
        requireReasonAndTime(command.reasonCode(), command.occurredAt());
    }

    private void validateRecover(RecoverExchange command) {
        if (command == null) throw new ServiceException("EXG-RECOVER-003: 恢复命令必填", 400);
        ExchangeRules.requireUlid(command.commandId(), "commandId");
        ExchangeRules.requireUlid(command.exchangeId(), "exchangeId");
        ExchangeRules.requireUlid(command.correlationId(), "correlationId");
        requireReasonAndTime(command.reasonCode(), command.occurredAt());
    }

    private void validateObservation(OwnerObservation observation) {
        if (observation == null) throw new ServiceException("EXG-OBS-001: Owner观察必填", 400);
        ExchangeRules.requireUlid(observation.observationId(), "observationId");
        ExchangeRules.requireUlid(observation.exchangeId(), "exchangeId");
        ExchangeRules.requireUlid(observation.ownerAggregateId(), "ownerAggregateId");
        ExchangeRules.requireHash(observation.payloadSha256(), "payloadSha256");
        if (observation.ownerSnapshotSha256() != null) {
            ExchangeRules.requireHash(observation.ownerSnapshotSha256(), "ownerSnapshotSha256");
        }
        if (observation.ownerStatus() == null || observation.amountMinor() < 0
            || observation.observedAt() == null) {
            throw new ServiceException("EXG-OBS-002: Owner观察字段非法", 409);
        }
    }

    private void requireReasonAndTime(String reason, Instant occurredAt) {
        if (reason == null || !reason.matches("^[A-Z0-9_]{2,32}$") || occurredAt == null) {
            throw new ServiceException("EXG-INPUT-003: 原因码或发生时间非法", 409);
        }
    }

    private String requestHash(CreateExchange command) {
        return ReturnHash.sha256(ReturnHash.canonical(List.of(command.exchangeId(), command.returnId(),
            command.originalOrderId(), command.originalReturnCommandId(), command.newOrderId(),
            command.newSaleCommandId(), command.storeId(), command.terminalId(), command.businessDate(),
            command.expectedRefundAmountMinor(), command.expectedSaleReceivableMinor(), command.quoteFingerprint(),
            command.newSalePlanSha256(), command.reasonCode(), command.correlationId(), command.occurredAt())));
    }

    private String hash(Object... values) { return ReturnHash.sha256(ReturnHash.canonical(List.of(values))); }

    private ExchangeView view(ExchangeRow row, boolean duplicate) {
        List<ExchangeLegView> legs = mapper.listLegs(tenantContext.requireTenantId(), row.exchangeId()).stream()
            .map(value -> new ExchangeLegView(value.legId(), value.legType(), value.ownerCode(),
                value.ownerAggregateId(), value.ownerCommandId(), value.expectedAmountMinor(), value.frozenSha256()))
            .toList();
        return new ExchangeView(row.exchangeId(), row.returnId(), row.originalOrderId(),
            row.originalReturnCommandId(), row.newOrderId(), row.newSaleCommandId(), row.storeId(), row.terminalId(),
            row.businessDate(), row.currency(), row.expectedRefundAmountMinor(), row.actualRefundAmountMinor(),
            row.expectedSaleReceivableMinor(), row.actualSaleReceivableMinor(),
            Math.subtractExact(row.expectedSaleReceivableMinor(), row.expectedRefundAmountMinor()),
            row.quoteFingerprint(), row.newSalePlanSha256(), row.actualNewOrderSnapshotSha256(), row.status(),
            row.requesterUserId(), row.approverUserId(), row.reasonCode(), row.correlationId(), row.recordVersion(),
            legs, row.updatedAt(), duplicate);
    }

    private ExchangeRow requireRow(ExchangeRow row) {
        if (row == null) throw new ServiceException("EXG-NOT-FOUND: 换货Saga不存在或不可见", 404);
        return row;
    }

    private LocalDateTime utc(Instant value) {
        if (value == null) throw new ServiceException("EXG-TIME-001: 发生时间必填", 409);
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private ServiceException contentConflict() {
        return new ServiceException("EXG-IDEMPOTENCY-001: 同一幂等或观察键对应不同内容", 409);
    }

    private ServiceException stateConflict() {
        return new ServiceException("EXG-STATE-002: 换货Saga并发检查点冲突", 409);
    }
}
