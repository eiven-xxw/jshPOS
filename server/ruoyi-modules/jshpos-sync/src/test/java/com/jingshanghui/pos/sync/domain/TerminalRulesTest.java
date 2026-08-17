package com.jingshanghui.pos.sync.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TerminalRulesTest {
    @Test
    void comparesNumericVersionSegmentsInsteadOfLexicalText() {
        assertThat(TerminalRules.compareVersion("1.10.0", "1.2.9")).isPositive();
        assertThat(TerminalRules.compareVersion("1.2", "1.2.0")).isZero();
        assertThat(TerminalRules.compareVersion("2", "10")).isNegative();
    }

    @Test
    void validatesHashesCodesKeysVersionsAndReasons() {
        assertThat(TerminalRules.requireSha256("a".repeat(64), "hash")).hasSize(64);
        assertThat(TerminalRules.requireCode("ANDROID_POS_V1", "profile")).isEqualTo("ANDROID_POS_V1");
        assertThat(TerminalRules.requireIdempotencyKey("terminal-command-0001")).isNotBlank();
        assertThat(TerminalRules.requireVersion("1.20.3", "version")).isEqualTo("1.20.3");
        assertThat(TerminalRules.requireSupportedProtocol("1.0")).isEqualTo("1.0");
        assertThat(TerminalRules.requireReason("  安全复核通过  ")).isEqualTo("安全复核通过");
        assertThatThrownBy(() -> TerminalRules.requireSha256("ABC", "hash")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TerminalRules.requireCode("bad code", "profile")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TerminalRules.requireIdempotencyKey("short")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TerminalRules.requireVersion("1.beta", "version")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TerminalRules.requireSupportedProtocol("2.0"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_PROTOCOL_UNSUPPORTED");
        assertThatThrownBy(() -> TerminalRules.requireReason("no")).isInstanceOf(ServiceException.class);
    }

    @Test
    void acceptsOnlyExplicitTerminalTransitions() {
        assertThatCode(() -> TerminalRules.requireTransition("ACTIVE", "BLOCKED")).doesNotThrowAnyException();
        assertThatCode(() -> TerminalRules.requireTransition("BLOCKED", "ACTIVE")).doesNotThrowAnyException();
        assertThatCode(() -> TerminalRules.requireTransition("ACTIVE", "REVOKED")).doesNotThrowAnyException();
        assertThatCode(() -> TerminalRules.requireTransition("REVOKED", "RETIRED")).doesNotThrowAnyException();
        assertThatThrownBy(() -> TerminalRules.requireTransition("REVOKED", "ACTIVE"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_STATE_CONFLICT");
        assertThatThrownBy(() -> TerminalRules.requireStatus("UNKNOWN")).isInstanceOf(ServiceException.class);
    }

    @Test
    void failsClosedForClockSkewOverFiveMinutes() {
        Instant server = Instant.parse("2026-08-17T00:00:00Z");
        assertThat(TerminalRules.clockSkewSeconds(server.plusSeconds(299), server)).isEqualTo(299);
        assertThatCode(() -> TerminalRules.requireClockSkew(-300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> TerminalRules.requireClockSkew(301))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_CLOCK_SKEW");
        assertThatThrownBy(() -> TerminalRules.clockSkewSeconds(null, server)).isInstanceOf(ServiceException.class);
    }
}
