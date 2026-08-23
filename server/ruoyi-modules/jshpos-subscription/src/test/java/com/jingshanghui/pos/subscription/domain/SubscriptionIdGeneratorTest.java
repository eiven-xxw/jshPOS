package com.jingshanghui.pos.subscription.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionIdGeneratorTest {
    @Test void createsUppercaseUlidAtFixedTime() {
        SubscriptionIdGenerator generator=new SubscriptionIdGenerator(Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC));
        String first=generator.next(),second=generator.next();
        assertTrue(first.matches("^[0-9A-HJKMNP-TV-Z]{26}$"));assertNotEquals(first,second);
    }

    @Test void rejectsClockBeforeUlidEpoch() {
        SubscriptionIdGenerator generator=new SubscriptionIdGenerator(Clock.fixed(Instant.parse("1960-01-01T00:00:00Z"),ZoneOffset.UTC));
        assertThrows(IllegalStateException.class,generator::next);
    }

    @Test void rejectsClockAfterUlidRange() {
        SubscriptionIdGenerator generator=new SubscriptionIdGenerator(
            Clock.fixed(Instant.ofEpochMilli(0x1000000000000L),ZoneOffset.UTC));
        assertThrows(IllegalStateException.class,generator::next);
    }
}
