package com.jingshanghui.pos.reporting.infrastructure.persistence.mapper;

import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort.DailyReportingFacts;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/** Reporting Owner 的门店业务日投影与血缘 XML 汇总。 */
public interface ReportingDailyCloseMapper {
    DailyReportingFacts aggregate(@Param("tenantId") String tenantId,
                                  @Param("storeId") Long storeId,
                                  @Param("businessDate") LocalDate businessDate);
}
