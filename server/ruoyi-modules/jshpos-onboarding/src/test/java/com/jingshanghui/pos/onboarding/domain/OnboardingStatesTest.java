package com.jingshanghui.pos.onboarding.domain;

import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckDecision;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.PlanState;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnboardingStatesTest {
    @Test
    void acceptsEveryFrozenHappyAndRecoveryTransition() {
        assertAllowed(PlanState.DRAFT, PlanState.PREFLIGHTING);
        assertAllowed(PlanState.DRAFT, PlanState.CANCELLED);
        assertAllowed(PlanState.PREFLIGHTING, PlanState.PREFLIGHT_FAILED);
        assertAllowed(PlanState.PREFLIGHTING, PlanState.READY);
        assertAllowed(PlanState.PREFLIGHTING, PlanState.FAILED);
        assertAllowed(PlanState.PREFLIGHT_FAILED, PlanState.PREFLIGHTING);
        assertAllowed(PlanState.PREFLIGHT_FAILED, PlanState.CANCELLED);
        assertAllowed(PlanState.READY, PlanState.APPROVED);
        assertAllowed(PlanState.READY, PlanState.PREFLIGHTING);
        assertAllowed(PlanState.READY, PlanState.CANCELLED);
        assertAllowed(PlanState.APPROVED, PlanState.APPLYING);
        assertAllowed(PlanState.APPROVED, PlanState.FAILED);
        assertAllowed(PlanState.APPLYING, PlanState.APPLIED);
        assertAllowed(PlanState.APPLYING, PlanState.FAILED);
        assertAllowed(PlanState.APPLYING, PlanState.COMPENSATION_REQUIRED);
        assertAllowed(PlanState.APPLIED, PlanState.CHECKING);
        assertAllowed(PlanState.CHECKING, PlanState.CHECK_FAILED);
        assertAllowed(PlanState.CHECKING, PlanState.READY_TO_OPEN);
        assertAllowed(PlanState.CHECKING, PlanState.FAILED);
        assertAllowed(PlanState.CHECK_FAILED, PlanState.CHECKING);
        assertAllowed(PlanState.CHECK_FAILED, PlanState.COMPENSATION_REQUIRED);
        assertAllowed(PlanState.READY_TO_OPEN, PlanState.CHECKING);
        assertAllowed(PlanState.READY_TO_OPEN, PlanState.OPENED);
        assertAllowed(PlanState.FAILED, PlanState.PREFLIGHTING);
        assertAllowed(PlanState.COMPENSATION_REQUIRED, PlanState.CHECKING);
    }

    @Test
    void rejectsTerminalAndSkippedTransitions() {
        assertThatThrownBy(() -> OnboardingStates.requireTransition(PlanState.DRAFT, PlanState.OPENED))
            .isInstanceOf(ServiceException.class).hasMessageContaining("非法开店状态迁移");
        assertThatThrownBy(() -> OnboardingStates.requireTransition(PlanState.OPENED, PlanState.CHECKING))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OnboardingStates.requireTransition(PlanState.CANCELLED, PlanState.PREFLIGHTING))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void externalBlockedDoesNotFakePassButAllowsInternalMilestone() {
        List<CheckDecision> checks = List.of(
            new CheckDecision("INTERNAL", true, false, CheckStatus.PASS),
            new CheckDecision("PAYMENT_EXTERNAL", true, true, CheckStatus.BLOCKED));
        assertThat(OnboardingStates.checkTarget(checks)).isEqualTo(PlanState.READY_TO_OPEN);
        assertThatThrownBy(() -> OnboardingStates.requireAllRequiredPass(checks))
            .isInstanceOf(ServiceException.class).hasMessageContaining("禁止形成 OPENED");
    }

    @Test
    void internalFailureAndExternalFailureCloseTheGate() {
        assertThat(OnboardingStates.checkTarget(List.of(
            new CheckDecision("INTERNAL", true, false, CheckStatus.UNAVAILABLE))))
            .isEqualTo(PlanState.CHECK_FAILED);
        assertThat(OnboardingStates.checkTarget(List.of(
            new CheckDecision("INTERNAL", true, false, CheckStatus.PASS),
            new CheckDecision("EXTERNAL", true, true, CheckStatus.FAIL))))
            .isEqualTo(PlanState.CHECK_FAILED);
    }

    @Test
    void completePassAllowsOpenedAndOptionalWarnDoesNotBlock() {
        List<CheckDecision> checks = List.of(
            new CheckDecision("REQUIRED", true, false, CheckStatus.PASS),
            new CheckDecision("OPTIONAL", false, false, CheckStatus.WARN));
        assertThat(OnboardingStates.checkTarget(checks)).isEqualTo(PlanState.READY_TO_OPEN);
        OnboardingStates.requireAllRequiredPass(checks);
    }

    @Test
    void rejectsEmptyOrMalformedCheckSets() {
        assertThatThrownBy(() -> OnboardingStates.checkTarget(List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OnboardingStates.checkTarget(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> OnboardingStates.requireAllRequiredPass(List.of())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new CheckDecision("", true, false, CheckStatus.PASS)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new CheckDecision("A", true, false, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new CheckDecision("A", true, true, CheckStatus.WARN)).isInstanceOf(ServiceException.class);
    }

    private static void assertAllowed(PlanState from, PlanState to) {
        OnboardingStates.requireTransition(from, to);
    }
}
