package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.port.TenderOrderSettlementPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Order Owner 对“全部份额已确认成功”事实的原子完成实现。 */
@Service
@RequiredArgsConstructor
public class TenderOrderSettlementService implements TenderOrderSettlementPort {

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final OrderJournalService journal;
    private final UlidGenerator ulids;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public OrderSettlementReceipt complete(OrderSettlementCommand command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validate(command);
        authorizationService.requireStoreAccess(command.storeId());
        OrderView order = mapper.lockOrder(principal.tenantId(), command.orderId());
        OrderMapper.TenderSettlementRow existing = mapper.findTenderSettlement(
            principal.tenantId(), command.orderId());
        requireBinding(command, order, existing != null);
        if (existing != null) {
            if (!existing.planId().equals(command.planId())
                || !existing.orderSnapshotSha256().equals(command.orderSnapshotSha256())
                || !existing.planContentSha256().equals(command.planContentSha256())
                || existing.receivedAmountMinor() != command.receivableAmountMinor()
                || !existing.currency().equals(command.currency())) {
                throw new ServiceException("TENDER_ORDER_IDEMPOTENCY_CONFLICT: 已有结算事实内容不一致", 409);
            }
            return new OrderSettlementReceipt(order.orderId(), existing.effectiveStatus(),
                existing.effectivePaymentStatus(), existing.receivedAmountMinor(),
                existing.orderAggregateVersion(), true);
        }
        int transitions = "PENDING_PAYMENT".equals(order.status()) ? 2 : 1;
        long completedVersion = order.recordVersion() + transitions;
        if (mapper.insertTenderSettlement(principal.tenantId(), ulids.next(), command.planId(), order.orderId(),
            command.receivableAmountMinor(), command.orderSnapshotSha256(), command.planContentSha256(),
            command.correlationId(), completedVersion,
            LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC)) != 1) {
            throw new ServiceException("TENDER_ORDER_CONFLICT: 订单结算事实写入失败", 409);
        }
        LocalDateTime at = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        long version = order.recordVersion();
        if (transitions == 2) {
            mapper.insertStateHistory(principal.tenantId(), ulids.next(), order.orderId(), command.correlationId(),
                "PENDING_PAYMENT", "CONFIRMED", ++version, principal.userId(), "TENDER_PLAN_PAID", at);
        }
        mapper.insertStateHistory(principal.tenantId(), ulids.next(), order.orderId(), command.correlationId(),
            "CONFIRMED", "COMPLETED", ++version, principal.userId(), "TENDER_PLAN_PAID", at);
        String payload = CanonicalJson.from(Map.<String, Object>of(
            "orderId", order.orderId(), "planId", command.planId(), "paymentStatus", "PAID",
            "receivableAmountMinor", command.receivableAmountMinor(), "currency", command.currency(),
            "planContentSha256", command.planContentSha256())).json();
        journal.appendEvent(principal.tenantId(), "order.command", "order.tender-paid.v1", "ORDER",
            order.orderId(), version, command.correlationId(), payload, at);
        journal.audit(principal.tenantId(), "ORDER_TENDER_PAID", "ORDER", order.orderId(), principal.userId(),
            null, command.correlationId(), order.status(), "COMPLETED", command.receivableAmountMinor(),
            command.planContentSha256(), "TENDER_PLAN_PAID", at);
        return new OrderSettlementReceipt(order.orderId(), "COMPLETED", "PAID",
            command.receivableAmountMinor(), completedVersion, false);
    }

    private void validate(OrderSettlementCommand command) {
        OrderRules.requireUlid(command.planId(), "planId");
        OrderRules.requireUlid(command.orderId(), "orderId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        OrderRules.requireUlid(command.correlationId(), "correlationId");
        OrderRules.requireMoney(command.receivableAmountMinor(), "receivableAmountMinor");
        if (command.storeId() == null || command.storeId() <= 0 || command.occurredAt() == null
            || !"CNY".equals(command.currency()) || !sha(command.orderSnapshotSha256())
            || !sha(command.planContentSha256())) {
            throw new ServiceException("TENDER_ORDER_INPUT_INVALID: 订单完成上下文不完整", 409);
        }
    }

    private void requireBinding(OrderSettlementCommand command, OrderView order, boolean hasSettlement) {
        boolean stateAllowed = order != null && (hasSettlement
            ? "COMPLETED".equals(order.status()) && "PAID".equals(order.paymentStatus())
            : ("PENDING_PAYMENT".equals(order.status()) || "CONFIRMED".equals(order.status()))
                && "UNPAID".equals(order.paymentStatus()));
        if (order == null || !order.storeId().equals(command.storeId())
            || !order.terminalId().equals(command.terminalId())
            || !order.snapshotSha256().equals(command.orderSnapshotSha256())
            || order.receivableAmountMinor() != command.receivableAmountMinor()
            || !order.currency().equals(command.currency())
            || !stateAllowed) {
            throw new ServiceException("TENDER_ORDER_BINDING_CONFLICT: 订单冻结身份、状态或金额不匹配", 409);
        }
    }

    private boolean sha(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }
}
