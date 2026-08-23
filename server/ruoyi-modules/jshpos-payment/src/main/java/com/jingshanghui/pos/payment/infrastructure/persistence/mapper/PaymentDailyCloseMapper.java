package com.jingshanghui.pos.payment.infrastructure.persistence.mapper;

import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort.DailyPaymentFacts;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** Payment Owner 的 Provider 无关日结 XML 汇总。 */
public interface PaymentDailyCloseMapper {
    DailyPaymentFacts aggregate(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                @Param("fromUtc") LocalDateTime fromUtc, @Param("toUtc") LocalDateTime toUtc);
}
