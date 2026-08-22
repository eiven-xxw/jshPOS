package com.jingshanghui.pos.onboarding.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingRulesTest {
    private static final String ULID = "01K3M000000000000000000001";
    private static final String HASH = "a".repeat(64);

    @Test
    void normalizesAndAcceptsFrozenIdentifiers() {
        assertThat(OnboardingRules.ulid(ULID, "planId")).isEqualTo(ULID);
        assertThat(OnboardingRules.hash(HASH, "hash")).isEqualTo(HASH);
        assertThat(OnboardingRules.key("  onb.key-001  ")).isEqualTo("onb.key-001");
        assertThat(OnboardingRules.correlation("trace:001")).isEqualTo("trace:001");
        assertThat(OnboardingRules.reason("  开店审批通过  ")).isEqualTo("开店审批通过");
        assertThat(OnboardingRules.positive(1L, "id")).isEqualTo(1L);
        assertThat(OnboardingRules.requestHash(Map.of("b", 2, "a", 1))).matches("[a-f0-9]{64}");
        OnboardingRules.requireSameHash(HASH, HASH);
    }

    @Test
    void rejectsInvalidIdentifiersAndText() {
        assertBad(() -> OnboardingRules.ulid("bad", "planId"));
        assertBad(() -> OnboardingRules.hash("A".repeat(64), "hash"));
        assertBad(() -> OnboardingRules.key("short"));
        assertBad(() -> OnboardingRules.key("1234567/unsafe"));
        assertBad(() -> OnboardingRules.key("a".repeat(65)));
        assertBad(() -> OnboardingRules.correlation("bad value"));
        assertBad(() -> OnboardingRules.correlation(""));
        assertBad(() -> OnboardingRules.reason("x"));
        assertBad(() -> OnboardingRules.reason("a\nb"));
        assertBad(() -> OnboardingRules.reason("a\rb"));
        assertBad(() -> OnboardingRules.positive(0L, "id"));
        assertBad(() -> OnboardingRules.positive(null, "id"));
    }

    @Test
    void sameKeyDifferentContentIsRejected() {
        assertThatThrownBy(() -> OnboardingRules.requireSameHash(HASH, "b".repeat(64)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("同一幂等键请求内容不同");
        assertBad(() -> OnboardingRules.requireSameHash("bad", HASH));
    }

    private static void assertBad(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(ServiceException.class);
    }
}
