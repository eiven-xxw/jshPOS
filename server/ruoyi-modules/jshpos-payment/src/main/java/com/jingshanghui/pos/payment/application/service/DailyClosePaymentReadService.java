package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentDailyCloseMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 使用可信租户与冻结 UTC 时间窗读取支付 Owner 日事实。 */
@Service
@RequiredArgsConstructor
public class DailyClosePaymentReadService implements DailyClosePaymentReadPort {
    private final TrustedTenantContext tenantContext;
    private final PaymentDailyCloseMapper mapper;

    @Override
    public DailyPaymentFacts read(Long storeId, LocalDateTime fromUtc, LocalDateTime toUtc) {
        if (storeId == null || storeId <= 0 || fromUtc == null || toUtc == null || !toUtc.isAfter(fromUtc)) {
            throw new ServiceException("OPS-PAY-001: 支付日结时间窗无效", 400);
        }
        return mapper.aggregate(tenantContext.requireTenantId(), storeId, fromUtc, toUtc);
    }
}
