package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.payment.application.model.PaymentCommands.PaymentObservation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundObservation;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationResult;
import com.jingshanghui.pos.payment.application.port.ProviderObservationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Provider 标准观察端口的本地路由，不包含协议、签名或网络实现。 */
@Service
@RequiredArgsConstructor
public class ProviderObservationService implements ProviderObservationPort {

    private final PaymentCoreService paymentCoreService;
    private final RefundService refundService;

    @Override
    public ObservationResult acceptPayment(PaymentObservation observation) {
        return paymentCoreService.acceptPayment(observation);
    }

    @Override
    public ObservationResult acceptRefund(RefundObservation observation) {
        return refundService.acceptObservation(observation);
    }
}
