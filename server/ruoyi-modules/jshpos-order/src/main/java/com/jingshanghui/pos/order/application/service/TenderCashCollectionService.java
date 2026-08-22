package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.port.TenderCashCollectionPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.TenderCashMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Order/Shift Owner 的部分现金份额原子记账实现。 */
@Service
@RequiredArgsConstructor
public class TenderCashCollectionService implements TenderCashCollectionPort {

    private final TenderCashMapper mapper;
    private final OrderMapper orderMapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final OrderJournalService journal;
    private final UlidGenerator ulids;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CashTenderReceipt collect(CashTenderCommand command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validate(command);
        authorizationService.requireStoreAccess(command.storeId());
        TenderCashMapper.CashTenderRow existing = mapper.findByAllocation(principal.tenantId(), command.allocationId());
        if (existing != null) {
            if (!existing.requestSha256().equals(command.requestSha256()) || existing.amountMinor() != command.amountMinor()
                || existing.tenderedMinor() != command.tenderedMinor()) {
                throw new ServiceException("IDEMPOTENCY_CONTENT_MISMATCH: 现金份额身份已对应其他内容", 409);
            }
            return new CashTenderReceipt(existing.cashTenderId(), existing.allocationId(), existing.amountMinor(),
                existing.tenderedMinor(), existing.changeMinor(), true);
        }
        OrderView order = orderMapper.findOrder(principal.tenantId(), command.orderId());
        ShiftView shift = orderMapper.lockShift(principal.tenantId(), command.shiftId());
        requireOwnerContext(command, principal, order, shift);
        long changeMinor = command.tenderedMinor() - command.amountMinor();
        String cashTenderId = ulids.next();
        LocalDateTime at = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        mapper.insertCashTender(principal.tenantId(), cashTenderId, command.planId(), command.allocationId(),
            command.orderId(), command.shiftId(), command.storeId(), command.terminalId(), principal.userId(),
            command.businessDate(), command.amountMinor(), command.tenderedMinor(), changeMinor,
            command.requestSha256(), command.correlationId(), at);
        mapper.insertCashLedger(principal.tenantId(), ulids.next(), command.shiftId(), command.orderId(), cashTenderId,
            command.amountMinor(), command.businessDate(), at);
        if (orderMapper.addShiftCash(principal.tenantId(), command.shiftId(), command.amountMinor()) != 1) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 部分现金记账时班次不可用", 409);
        }
        String payload = CanonicalJson.from(Map.<String, Object>of(
            "cashTenderId", cashTenderId, "planId", command.planId(), "allocationId", command.allocationId(),
            "orderId", command.orderId(), "amountMinor", command.amountMinor(), "currency", "CNY")).json();
        journal.appendEvent(principal.tenantId(), "order.command", "cash.tender.received.v1", "CASH_TENDER",
            cashTenderId, 1, command.correlationId(), payload, at);
        journal.audit(principal.tenantId(), "CASH_TENDER_RECEIVED", "CASH_TENDER", cashTenderId,
            principal.userId(), null, command.correlationId(), null, "SUCCEEDED", command.amountMinor(),
            command.requestSha256(), "TENDER_PLAN", at);
        return new CashTenderReceipt(cashTenderId, command.allocationId(), command.amountMinor(),
            command.tenderedMinor(), changeMinor, false);
    }

    private void validate(CashTenderCommand command) {
        OrderRules.requireUlid(command.planId(), "planId");
        OrderRules.requireUlid(command.allocationId(), "allocationId");
        OrderRules.requireUlid(command.orderId(), "orderId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        OrderRules.requireUlid(command.correlationId(), "correlationId");
        OrderRules.requireMoney(command.amountMinor(), "amountMinor");
        OrderRules.requireMoney(command.tenderedMinor(), "tenderedMinor");
        if (command.amountMinor() <= 0 || command.tenderedMinor() < command.amountMinor()
            || command.storeId() == null || command.storeId() <= 0 || command.businessDate() == null
            || command.occurredAt() == null || command.requestSha256() == null
            || !command.requestSha256().matches("^[a-f0-9]{64}$")) {
            throw new ServiceException("TENDER_CASH_INPUT_INVALID: 现金份额上下文不完整", 409);
        }
    }

    private void requireOwnerContext(CashTenderCommand command, TrustedPrincipal principal,
                                     OrderView order, ShiftView shift) {
        boolean orderReady = order != null && ("PENDING_PAYMENT".equals(order.status()) || "CONFIRMED".equals(order.status()))
            && "UNPAID".equals(order.paymentStatus()) && order.storeId().equals(command.storeId())
            && order.terminalId().equals(command.terminalId()) && order.shiftId().equals(command.shiftId())
            && order.businessDate().equals(command.businessDate());
        boolean shiftReady = shift != null && "OPEN".equals(shift.status()) && shift.storeId().equals(command.storeId())
            && shift.terminalId().equals(command.terminalId()) && shift.cashierUserId().equals(principal.userId())
            && shift.businessDate().equals(command.businessDate());
        if (!orderReady || !shiftReady) {
            throw new ServiceException("TENDER_OWNER_CONTEXT_CONFLICT: 订单或班次不允许部分现金收款", 409);
        }
    }
}
