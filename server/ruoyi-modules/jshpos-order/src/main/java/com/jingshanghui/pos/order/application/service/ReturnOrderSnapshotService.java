package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.ReturnOwnerMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order Owner 对 Return Owner 提供的最小只读成交快照。 */
@Service
@RequiredArgsConstructor
public class ReturnOrderSnapshotService implements ReturnOrderSnapshotPort {

    private final ReturnOwnerMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;

    @Override
    @Transactional(readOnly = true)
    public ReturnOrderSnapshot requireSnapshot(String orderId) {
        String tenantId = tenantContext.requireTenantId();
        ReturnOwnerMapper.ReturnOrderHeader header = mapper.findReturnOrder(tenantId, orderId);
        if (header == null || !"COMPLETED".equals(header.status()) || !"PAID".equals(header.paymentStatus())
            || header.promotionSnapshotId() == null || header.promotionSnapshotSha256() == null) {
            throw new ServiceException("RET-ORDER-001: 原订单未完成、不可见或缺少成交促销快照", 409);
        }
        authorizationService.requireStoreAccess(header.storeId());
        var lines = mapper.listReturnOrderLines(tenantId, orderId);
        if (lines.isEmpty()) throw new ServiceException("RET-ORDER-002: 原订单缺少不可变成交行", 409);
        return new ReturnOrderSnapshot(header.orderId(), header.storeId(), header.terminalId(),
            header.businessDate(), header.status(), header.paymentStatus(), header.currency(),
            header.grossAmountMinor(), header.discountAmountMinor(), header.surchargeAmountMinor(),
            header.receivableAmountMinor(), header.promotionSnapshotId(), header.promotionSnapshotSha256(),
            header.cashPaymentId(), lines);
    }
}
