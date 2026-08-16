package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundView;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort;
import com.jingshanghui.pos.payment.domain.PaymentRules;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 支付 Owner 对库存开放的成功原单退款只读适配器。 */
@Service
@RequiredArgsConstructor
public class RefundInventorySnapshotService implements InventoryRefundSnapshotPort {

    private final PaymentMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public InventoryRefundSnapshot requireSnapshot(String refundId) {
        PaymentRules.requireUlid(refundId, "refundId");
        String tenantId = tenantContext.requireTenantId();
        RefundView refund = mapper.findRefund(tenantId, refundId);
        if (refund == null) {
            throw new ServiceException("INV-REFUND-001: 原退款不存在或不可见", 404);
        }
        var lines = mapper.findRefundLines(tenantId, refundId).stream()
            .map(line -> new InventoryRefundLine(line.orderLineId(), line.quantity())).toList();
        if (lines.isEmpty()) {
            throw new ServiceException("INV-REFUND-002: 原退款缺少行数量快照", 409);
        }
        return new InventoryRefundSnapshot(refund.refundId(), refund.orderId(), refund.storeId(),
            refund.status(), lines);
    }
}
