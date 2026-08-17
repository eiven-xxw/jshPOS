package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateRefund;
import com.jingshanghui.pos.payment.application.port.ReturnPaymentRefundPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Return Saga 到既有 Provider 无关退款核心的受控适配器，不包含 SDK 或 HTTP。 */
@Service
@RequiredArgsConstructor
public class ReturnPaymentRefundService implements ReturnPaymentRefundPort {

    private final RefundService refunds;

    @Override
    public RefundState request(RefundCommand command) {
        var result = refunds.create(new CreateRefund(command.eventId(), command.idempotencyKey(),
            command.refundId(), command.paymentId(), command.orderId(), command.amountMinor(), "CNY",
            command.reasonCode(), command.lines().stream().map(line -> new com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundLine(line.orderLineId(),
            line.quantity().toPlainString(), line.amountMinor())).toList(), command.occurredAt()));
        return new RefundState(result.refundId(), result.paymentId(), result.status(), result.amountMinor(),
            result.currency(), result.duplicate());
    }

    @Override
    public RefundState find(String refundId) {
        var result = refunds.find(refundId);
        return new RefundState(result.refundId(), result.paymentId(), result.status(), result.amountMinor(),
            result.currency(), false);
    }
}
