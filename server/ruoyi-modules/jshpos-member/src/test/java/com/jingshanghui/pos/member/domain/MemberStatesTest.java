package com.jingshanghui.pos.member.domain;

import org.junit.jupiter.api.Test;
import static com.jingshanghui.pos.member.domain.MemberStates.PrivacyRequestState.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 固定隐私请求状态图，防止绕过身份核验或改写终态。 */
class MemberStatesTest {
    @Test void acceptsOnlyDeclaredPrivacyTransitions() {
        assertThat(MemberStates.canTransition(REQUESTED, IDENTITY_VERIFIED)).isTrue();
        assertThat(MemberStates.canTransition(REQUESTED, REJECTED)).isTrue();
        assertThat(MemberStates.canTransition(REQUESTED, FULFILLED)).isFalse();
        assertThat(MemberStates.canTransition(IDENTITY_VERIFIED, IN_PROGRESS)).isTrue();
        assertThat(MemberStates.canTransition(IDENTITY_VERIFIED, REJECTED)).isTrue();
        assertThat(MemberStates.canTransition(IDENTITY_VERIFIED, FULFILLED)).isFalse();
        assertThat(MemberStates.canTransition(IN_PROGRESS, FULFILLED)).isTrue();
        assertThat(MemberStates.canTransition(IN_PROGRESS, PARTIALLY_FULFILLED)).isTrue();
        assertThat(MemberStates.canTransition(IN_PROGRESS, REJECTED)).isTrue();
        assertThat(MemberStates.canTransition(IN_PROGRESS, REQUESTED)).isFalse();
        assertThat(MemberStates.canTransition(FULFILLED, REQUESTED)).isFalse();
        assertThat(MemberStates.canTransition(PARTIALLY_FULFILLED, REQUESTED)).isFalse();
        assertThat(MemberStates.canTransition(REJECTED, REQUESTED)).isFalse();
    }
}
