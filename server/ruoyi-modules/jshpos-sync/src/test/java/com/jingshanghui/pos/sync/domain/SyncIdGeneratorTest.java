package com.jingshanghui.pos.sync.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncIdGeneratorTest {

    @Test
    void createsCanonicalUlids() {
        SyncIdGenerator generator = new SyncIdGenerator(
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC), new SecureRandom());
        assertThat(generator.next()).matches("^[0-9A-HJKMNP-TV-Z]{26}$");
    }

    @Test
    void rejectsTimestampOutsideUlidRange() {
        SyncIdGenerator generator = new SyncIdGenerator(
            Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC), new SecureRandom());
        assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class);
    }
}
