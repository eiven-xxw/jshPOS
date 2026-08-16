package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 订单 Owner 对库存开放的可信只读适配器。 */
@Service
@RequiredArgsConstructor
public class OrderInventorySnapshotService implements InventoryOrderSnapshotPort {

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public InventoryOrderSnapshot requireSnapshot(String orderId) {
        OrderRules.requireUlid(orderId, "orderId");
        String tenantId = tenantContext.requireTenantId();
        OrderView order = mapper.findOrder(tenantId, orderId);
        if (order == null) {
            throw new ServiceException("INV-ORDER-001: 原订单不存在或不可见", 404);
        }
        var lines = mapper.findInventoryLines(tenantId, orderId).stream()
            .map(line -> new InventoryLineSnapshot(line.lineId(), line.skuId(), line.unitId(), line.quantity()))
            .toList();
        if (lines.isEmpty()) {
            throw new ServiceException("INV-ORDER-002: 原订单缺少库存行快照", 409);
        }
        return new InventoryOrderSnapshot(order.orderId(), order.storeId(), order.status(), order.paymentStatus(),
            order.businessDate(), lines);
    }
}
