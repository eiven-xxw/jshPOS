package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.port.PaymentOrderSnapshotPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单领域对支付开放的最小只读快照服务。
 *
 * <p>tenant_id 始终来自可信上下文，找不到或跨租户时统一返回不可见。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderPaymentSnapshotService implements PaymentOrderSnapshotPort {

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public OrderPaymentSnapshot requireSnapshot(String orderId) {
        OrderRules.requireUlid(orderId, "orderId");
        String tenantId = tenantContext.requireTenantId();
        OrderView order = mapper.findOrder(tenantId, orderId);
        if (order == null) {
            throw new ServiceException("PAY-ORDER-001: 原订单不存在或不可见", 404);
        }
        var lines = mapper.findPaymentLines(tenantId, orderId).stream()
            .map(line -> new LineSnapshot(line.lineId(), line.quantity(), line.payableAmountMinor()))
            .toList();
        if (lines.isEmpty()) {
            throw new ServiceException("PAY-ORDER-002: 原订单缺少不可变行快照", 409);
        }
        return new OrderPaymentSnapshot(order.orderId(), order.storeId(), order.terminalId(), order.status(),
            order.paymentStatus(), order.currency(), order.receivableAmountMinor(), lines);
    }
}
