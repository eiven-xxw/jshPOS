package com.jingshanghui.pos.member.domain;

import org.junit.jupiter.api.Test;

import static com.jingshanghui.pos.member.domain.BenefitStates.VersionState.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 穷举权益版本具名状态机的允许与拒绝边。 */
class BenefitStatesTest {
    @Test void allowsOnlyNamedForwardAndControlTransitions() {
        assertThat(BenefitStates.canTransition(DRAFT,VALIDATED)).isTrue();
        assertThat(BenefitStates.canTransition(VALIDATED,APPROVED)).isTrue();
        assertThat(BenefitStates.canTransition(APPROVED,SCHEDULED)).isTrue();
        assertThat(BenefitStates.canTransition(APPROVED,ACTIVE)).isTrue();
        assertThat(BenefitStates.canTransition(SCHEDULED,ACTIVE)).isTrue();
        assertThat(BenefitStates.canTransition(SCHEDULED,REVOKED)).isTrue();
        assertThat(BenefitStates.canTransition(ACTIVE,PAUSED)).isTrue();
        assertThat(BenefitStates.canTransition(ACTIVE,RETIRED)).isTrue();
        assertThat(BenefitStates.canTransition(ACTIVE,REVOKED)).isTrue();
        assertThat(BenefitStates.canTransition(PAUSED,ACTIVE)).isTrue();
        assertThat(BenefitStates.canTransition(PAUSED,RETIRED)).isTrue();
        assertThat(BenefitStates.canTransition(PAUSED,REVOKED)).isTrue();
        assertThat(BenefitStates.canTransition(DRAFT,ACTIVE)).isFalse();
        assertThat(BenefitStates.canTransition(RETIRED,ACTIVE)).isFalse();
        assertThat(BenefitStates.canTransition(REVOKED,ACTIVE)).isFalse();
    }
}
