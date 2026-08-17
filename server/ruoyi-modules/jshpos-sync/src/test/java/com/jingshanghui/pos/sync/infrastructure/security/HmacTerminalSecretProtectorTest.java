package com.jingshanghui.pos.sync.infrastructure.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacTerminalSecretProtectorTest {
    private final HmacTerminalSecretProtector protector =
        new HmacTerminalSecretProtector("synthetic-test-pepper-32-characters-minimum");

    @Test
    void scopesAndMatchesSecretsWithConstantTimeDigestComparison() {
        String digest = protector.digest("activation:01", "synthetic-secret");
        assertThat(digest).matches("^[a-f0-9]{64}$");
        assertThat(protector.matches("activation:01", "synthetic-secret", digest)).isTrue();
        assertThat(protector.matches("credential:01", "synthetic-secret", digest)).isFalse();
        assertThat(protector.matches("activation:01", "wrong-secret", digest)).isFalse();
        assertThat(protector.matches("activation:01", "synthetic-secret", null)).isFalse();
    }

    @Test
    void rejectsMissingPepperPurposeAndSecret() {
        assertThatThrownBy(() -> new HmacTerminalSecretProtector("short")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.digest("", "secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> protector.digest("purpose", "")).isInstanceOf(IllegalArgumentException.class);
    }
}
