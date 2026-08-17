package com.jingshanghui.pos.sync.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalSecretGeneratorTest {
    @Test
    void generatesIndependentUrlSafe256BitSecrets() {
        TerminalSecretGenerator generator = new TerminalSecretGenerator();
        String first = generator.next();
        String second = generator.next();
        assertThat(first).matches("^[A-Za-z0-9_-]{43}$");
        assertThat(second).isNotEqualTo(first);
    }
}
