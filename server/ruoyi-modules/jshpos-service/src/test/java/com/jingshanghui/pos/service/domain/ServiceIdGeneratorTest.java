package com.jingshanghui.pos.service.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

/** 服务事实 ULID 形态和同毫秒唯一性回归。 */
class ServiceIdGeneratorTest {
    @Test
    void shouldCreateOpaqueSortableShapeWithoutBusinessIdentity() {
        ServiceIdGenerator generator = new ServiceIdGenerator(Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
        String first = generator.next();
        String second = generator.next();
        assertAll(
            () -> assertTrue(first.matches("^[0-9A-HJKMNP-TV-Z]{26}$")),
            () -> assertEquals(first.substring(0, 10), second.substring(0, 10)),
            () -> assertNotEquals(first, second),
            () -> assertFalse(first.contains("tenant"))
        );
    }
}
