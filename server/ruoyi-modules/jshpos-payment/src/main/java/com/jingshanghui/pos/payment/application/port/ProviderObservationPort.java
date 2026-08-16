package com.jingshanghui.pos.payment.application.port;

import com.jingshanghui.pos.payment.application.model.PaymentCommands.PaymentObservation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationResult;

/**
 * Provider 适配器进入核心的唯一观察端口。
 *
 * <p>调用方必须先完成渠道鉴权、验签、重放检查和敏感报文脱敏。Gate 3A 没有生产实现。</p>
 */
public interface ProviderObservationPort {

    ObservationResult acceptPayment(PaymentObservation observation);

    ObservationResult acceptRefund(RefundObservation observation);
}
