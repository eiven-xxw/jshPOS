package com.jingshanghui.pos.order.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UlidGeneratorTest {

    @Test
    void createsCanonicalDistinctUlidsWithTimestampPrefix() {
        SecureRandom random = new SecureRandom(new byte[]{1, 2, 3, 4});
        UlidGenerator generator = new UlidGenerator(
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC), random);
        String first = generator.next();
        String second = generator.next();
        assertThat(first).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(second).matches("^[0-9A-HJKMNP-TV-Z]{26}$").isNotEqualTo(first);
        assertThat(first.substring(0, 10)).isEqualTo(second.substring(0, 10));
    }

    @Test
    void rejectsTimestampOutsideUlidRange() {
        UlidGenerator negative = new UlidGenerator(
            Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC), new SecureRandom());
        UlidGenerator aboveMaximum = new UlidGenerator(
            Clock.fixed(Instant.ofEpochMilli(0x1000000000000L), ZoneOffset.UTC), new SecureRandom());
        assertThatThrownBy(negative::next).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(aboveMaximum::next).isInstanceOf(IllegalStateException.class);
    }
}
