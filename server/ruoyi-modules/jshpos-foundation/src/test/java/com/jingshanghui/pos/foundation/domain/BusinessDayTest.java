package com.jingshanghui.pos.foundation.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessDayTest {

    @ParameterizedTest
    @CsvSource({
        "2026-08-16T21:59:59Z, Asia/Shanghai, 06:00, 2026-08-16",
        "2026-08-16T22:00:00Z, Asia/Shanghai, 06:00, 2026-08-17",
        "2026-03-08T06:59:59Z, America/New_York, 04:00, 2026-03-07",
        "2026-03-08T08:00:00Z, America/New_York, 04:00, 2026-03-08",
        "2026-11-01T07:30:00Z, America/New_York, 04:00, 2026-10-31",
        "2026-11-01T09:00:00Z, America/New_York, 04:00, 2026-11-01"
    })
    void calculatesAcrossMidnightAndDst(String instant, String zone, String start, String expected) {
        assertThat(BusinessDay.calculate(
            Instant.parse(instant), ZoneId.of(zone), LocalTime.parse(start)
        )).isEqualTo(LocalDate.parse(expected));
    }

    @org.junit.jupiter.api.Test
    void rejectsUnknownZone() {
        assertThatThrownBy(() -> BusinessDay.requireZoneId("Mars/Store"))
            .hasMessageContaining("FND-ORG-006");
    }
}
