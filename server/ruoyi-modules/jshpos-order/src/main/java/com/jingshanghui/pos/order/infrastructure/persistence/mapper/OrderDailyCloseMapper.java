package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort.DailyOrderFacts;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;

/** Order Owner 的门店业务日复杂汇总；SQL 仅允许位于 XML。 */
public interface OrderDailyCloseMapper {
    DailyOrderFacts aggregate(@Param("tenantId") String tenantId,
                              @Param("storeId") Long storeId,
                              @Param("businessDate") LocalDate businessDate);
}
