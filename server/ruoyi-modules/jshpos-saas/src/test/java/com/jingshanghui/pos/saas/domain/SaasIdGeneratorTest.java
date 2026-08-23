package com.jingshanghui.pos.saas.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;

/** ULID 格式、唯一性和时间越界保护。 */
class SaasIdGeneratorTest {
    @Test void createsUppercaseUlids() {
        SaasIdGenerator generator=new SaasIdGenerator(Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));
        String first=generator.next(),second=generator.next();
        assertThat(first).matches("^[0-9A-HJKMNP-TV-Z]{26}$");assertThat(second).isNotEqualTo(first);
    }
    @Test void rejectsNegativeClock(){SaasIdGenerator generator=new SaasIdGenerator(Clock.fixed(Instant.ofEpochMilli(-1),ZoneOffset.UTC));assertThatThrownBy(generator::next).isInstanceOf(IllegalStateException.class).hasMessageContaining("SAA-ID-001");}
}
