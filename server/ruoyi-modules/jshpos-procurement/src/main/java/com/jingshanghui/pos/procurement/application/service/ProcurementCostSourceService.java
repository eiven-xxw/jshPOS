package com.jingshanghui.pos.procurement.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ProcurementMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只读解析已确认采购事实，禁止把草稿价格或调用方输入交给成本模块。 */
@Service
@RequiredArgsConstructor
public class ProcurementCostSourceService implements ProcurementCostSourcePort {

    private final ProcurementMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public ReceiptCostSource requireReceiptLine(String receiptLineId) {
        ReceiptCostSource source = mapper.findConfirmedReceiptCostSource(
            tenantContext.requireTenantId(), receiptLineId);
        if (source == null) {
            throw new ServiceException("CST-SOURCE-MISSING: 已确认采购收货成本来源不存在或不可见", 409);
        }
        return source;
    }

    @Override
    @Transactional(readOnly = true)
    public ReturnCostSource requireReturnLine(String returnLineId) {
        ReturnCostSource source = mapper.findPostedReturnCostSource(
            tenantContext.requireTenantId(), returnLineId);
        if (source == null) {
            throw new ServiceException("CST-SOURCE-MISSING: 已入账采购退货成本来源不存在或不可见", 409);
        }
        return source;
    }
}
