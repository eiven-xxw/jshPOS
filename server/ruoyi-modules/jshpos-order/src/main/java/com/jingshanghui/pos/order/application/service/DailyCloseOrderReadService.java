package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderDailyCloseMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** 从 Order Owner XML 汇总中读取日结事实；缺失或金额不守恒时失败关闭。 */
@Service
@RequiredArgsConstructor
public class DailyCloseOrderReadService implements DailyCloseOrderReadPort {
    private final TrustedTenantContext tenantContext;
    private final OrderDailyCloseMapper mapper;

    @Override
    public DailyOrderFacts read(Long storeId, LocalDate businessDate) {
        if (storeId == null || storeId <= 0 || businessDate == null) {
            throw new ServiceException("OPS-ORDER-001: 门店与业务日不能为空", 400);
        }
        DailyOrderFacts facts = mapper.aggregate(tenantContext.requireTenantId(), storeId, businessDate);
        if (facts == null || facts.grossMinor() - facts.discountMinor() + facts.surchargeMinor() != facts.receivableMinor()) {
            throw new ServiceException("OPS-ORDER-002: 订单金额不守恒", 409);
        }
        return facts;
    }
}
