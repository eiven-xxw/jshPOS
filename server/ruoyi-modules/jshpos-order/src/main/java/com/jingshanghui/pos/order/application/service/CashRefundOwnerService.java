package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.port.CashRefundOwnerPort;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.ReturnOwnerMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;

/** Order Owner 现金退款服务；退款事实、班次现金效果、审计与 Outbox 同事务提交。 */
@Service
@RequiredArgsConstructor
public class CashRefundOwnerService implements CashRefundOwnerPort {

    private final ReturnOwnerMapper mapper;
    private final OrderMapper orderMapper;
    private final ReturnOrderSnapshotPort orders;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final OrderJournalService journal;
    private final UlidGenerator ulids;

    @Override
    @Transactional
    public CashRefundResult refund(CashRefundCommand command) {
        validate(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.storeId());
        ReturnOwnerMapper.CashRefund existing = mapper.findCashRefund(principal.tenantId(), command.refundId());
        if (existing != null) {
            if (!existing.requestSha256().equals(command.requestSha256())) {
                throw new ServiceException("CASH-REFUND-IDEM-001: 同一退款标识对应不同内容", 409);
            }
            return new CashRefundResult(existing.cashRefundId(), existing.refundId(), existing.amountMinor(),
                existing.status(), true);
        }
        ReturnOrderSnapshotPort.ReturnOrderSnapshot order = orders.requireSnapshot(command.orderId());
        if (!order.storeId().equals(command.storeId()) || order.cashPaymentId() == null
            || !order.cashPaymentId().equals(command.originalCashPaymentId())) {
            throw new ServiceException("CASH-REFUND-ORDER-001: 原订单或现金收款身份不一致", 409);
        }
        ReturnOwnerMapper.CashPayment payment = mapper.lockCashPayment(principal.tenantId(),
            command.originalCashPaymentId());
        if (payment == null || !payment.orderId().equals(command.orderId()) || !"SUCCEEDED".equals(payment.status())) {
            throw new ServiceException("CASH-REFUND-PAYMENT-001: 原现金收款不存在或不可退款", 409);
        }
        long refunded = mapper.sumSucceededCashRefund(principal.tenantId(), payment.cashPaymentId());
        if (command.amountMinor() <= 0 || refunded > payment.netAmountMinor() - command.amountMinor()) {
            throw new ServiceException("CASH-REFUND-LIMIT-001: 累计现金退款超过原收款", 409);
        }
        ShiftView shift = orderMapper.lockShift(principal.tenantId(), command.refundShiftId());
        if (shift == null || !"OPEN".equals(shift.status()) || !shift.storeId().equals(command.storeId())
            || !shift.terminalId().equals(command.terminalId()) || !shift.cashierUserId().equals(principal.userId())
            || !shift.businessDate().equals(command.businessDate())) {
            throw new ServiceException("CASH-REFUND-SHIFT-001: 当前退款班次与可信上下文不一致", 409);
        }
        LocalDateTime at = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        mapper.insertCashRefund(command.eventId(), principal.tenantId(), command.refundId(), command.orderId(),
            command.originalCashPaymentId(), command.refundShiftId(), command.storeId(), command.terminalId(),
            command.businessDate(), command.amountMinor(), command.requestSha256(), command.correlationId(),
            principal.userId(), at);
        mapper.insertCashRefundLedger(ulids.next(), principal.tenantId(), command.refundShiftId(), command.orderId(),
            command.originalCashPaymentId(), command.eventId(), command.amountMinor(), command.businessDate(), at);
        if (orderMapper.addShiftCash(principal.tenantId(), command.refundShiftId(), -command.amountMinor()) != 1) {
            throw new ServiceException("CASH-REFUND-SHIFT-002: 退款落账时班次状态冲突", 409);
        }
        journal.audit(principal.tenantId(), "CASH_REFUND_SUCCEEDED", "CASH_REFUND", command.refundId(),
            principal.userId(), null, command.eventId(), "REQUESTED", "SUCCEEDED", command.amountMinor(),
            command.requestSha256(), "ORIGINAL_RETURN", at);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("schemaVersion", "1.0"); payload.put("refundId", command.refundId());
        payload.put("orderId", command.orderId()); payload.put("cashPaymentId", command.originalCashPaymentId());
        payload.put("amountMinor", command.amountMinor()); payload.put("currency", "CNY");
        payload.put("status", "SUCCEEDED");
        journal.appendEvent(principal.tenantId(), "order.command", "cash.refund.succeeded.v1", "CASH_REFUND",
            command.refundId(), 1, command.correlationId(),
            com.jingshanghui.pos.foundation.domain.CanonicalJson.from(payload).json(), at);
        return new CashRefundResult(command.eventId(), command.refundId(), command.amountMinor(),
            "SUCCEEDED", false);
    }

    private void validate(CashRefundCommand command) {
        OrderRules.requireUlid(command.eventId(), "eventId");
        OrderRules.requireUlid(command.refundId(), "refundId");
        OrderRules.requireUlid(command.orderId(), "orderId");
        OrderRules.requireUlid(command.originalCashPaymentId(), "originalCashPaymentId");
        OrderRules.requireUlid(command.refundShiftId(), "refundShiftId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        if (command.storeId() == null || command.storeId() <= 0 || command.businessDate() == null
            || command.amountMinor() <= 0 || command.occurredAt() == null
            || command.requestSha256() == null || !command.requestSha256().matches("^[a-f0-9]{64}$")
            || command.correlationId() == null
            || !command.correlationId().matches("^[0-9A-HJKMNP-TV-Z]{26}$")) {
            throw new ServiceException("CASH-REFUND-INPUT-001: 现金退款命令字段非法", 409);
        }
    }
}
