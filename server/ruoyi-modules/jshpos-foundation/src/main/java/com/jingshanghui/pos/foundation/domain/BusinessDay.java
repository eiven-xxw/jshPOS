package com.jingshanghui.pos.foundation.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 门店业务日纯领域算法，可用固定时间向量跨平台验证。
 */
public final class BusinessDay {

    private BusinessDay() {
    }

    public static ZoneId requireZoneId(String zoneId) {
        try {
            return ZoneId.of(zoneId);
        } catch (Exception exception) {
            throw new ServiceException("FND-ORG-006: IANA 时区无效", 400);
        }
    }

    public static LocalDate calculate(Instant instant, ZoneId zoneId, LocalTime businessDayStart) {
        ZonedDateTime local = instant.atZone(zoneId);
        LocalDate date = local.toLocalDate();
        return local.toLocalTime().isBefore(businessDayStart) ? date.minusDays(1) : date;
    }
}
