package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort;
import com.jingshanghui.pos.reporting.infrastructure.persistence.mapper.ReportingDailyCloseMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/** 读取 Reporting 可丢弃投影用于核对，绝不将投影提升为业务事实。 */
@Service
@RequiredArgsConstructor
public class DailyCloseReportingReadService implements DailyCloseReportingReadPort {
    private final TrustedTenantContext tenantContext;
    private final ReportingDailyCloseMapper mapper;

    @Override
    public DailyReportingFacts read(Long storeId, LocalDate businessDate) {
        if (storeId == null || storeId <= 0 || businessDate == null) {
            throw new ServiceException("OPS-RPT-001: 报表日结范围无效", 400);
        }
        return mapper.aggregate(tenantContext.requireTenantId(), storeId, businessDate);
    }
}
