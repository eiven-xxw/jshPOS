package com.jingshanghui.pos.transfer.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort;
import com.jingshanghui.pos.transfer.infrastructure.persistence.mapper.TransferMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只读解析已入账调拨事实，拒绝草稿、跨租户或调用方伪造的成本来源。 */
@Service
@RequiredArgsConstructor
public class TransferCostSourceService implements TransferCostSourcePort {
    private final TransferMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public DispatchCostSource requireDispatchLine(String dispatchLineId) {
        DispatchCostSource source = mapper.findPostedDispatchCostSource(
            tenantContext.requireTenantId(), dispatchLineId);
        if (source == null) throw new ServiceException("CST-SOURCE-MISSING: 已入账调拨发出成本来源不存在或不可见", 409);
        return source;
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptCostSource requireReceiptLine(String receiptLineId) {
        ReceiptCostSource source = mapper.findPostedReceiptCostSource(
            tenantContext.requireTenantId(), receiptLineId);
        if (source == null) throw new ServiceException("CST-SOURCE-MISSING: 已入账调拨收货成本来源不存在或不可见", 409);
        return source;
    }
}
