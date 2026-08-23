package com.jingshanghui.pos.operations.domain;

import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckDecision;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CloseState;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DailyCloseStatesTest {
    @Test
    void allowsOnlyFrozenStateMachineTransitions() {
        DailyCloseStates.requireTransition(CloseState.DRAFT, CloseState.PREFLIGHTING);
        DailyCloseStates.requireTransition(CloseState.CORRECTION_REQUIRED, CloseState.PREFLIGHTING);
        DailyCloseStates.requireTransition(CloseState.PREFLIGHT_FAILED, CloseState.PREFLIGHTING);
        DailyCloseStates.requireTransition(CloseState.FAILED, CloseState.PREFLIGHTING);
        DailyCloseStates.requireTransition(CloseState.FAILED, CloseState.COMPENSATION_REQUIRED);
        DailyCloseStates.requireTransition(CloseState.PREFLIGHTING, CloseState.READY);
        DailyCloseStates.requireTransition(CloseState.PREFLIGHTING, CloseState.PREFLIGHT_FAILED);
        DailyCloseStates.requireTransition(CloseState.PREFLIGHTING, CloseState.FAILED);
        DailyCloseStates.requireTransition(CloseState.READY, CloseState.PREFLIGHTING);
        DailyCloseStates.requireTransition(CloseState.READY, CloseState.APPROVED);
        DailyCloseStates.requireTransition(CloseState.APPROVED, CloseState.CLOSING);
        DailyCloseStates.requireTransition(CloseState.APPROVED, CloseState.FAILED);
        DailyCloseStates.requireTransition(CloseState.CLOSING, CloseState.CLOSED);
        DailyCloseStates.requireTransition(CloseState.CLOSING, CloseState.FAILED);
        DailyCloseStates.requireTransition(CloseState.CLOSING, CloseState.COMPENSATION_REQUIRED);

        assertThatThrownBy(() -> DailyCloseStates.requireTransition(CloseState.CLOSED, CloseState.DRAFT))
            .isInstanceOf(ServiceException.class).hasMessageContaining("非法日结状态迁移");
        assertThatThrownBy(() -> DailyCloseStates.requireTransition(null, CloseState.DRAFT))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> DailyCloseStates.requireTransition(CloseState.DRAFT, null))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void requiredChecksMustPassWhileExternalCannotBeGreenPlaceholder() {
        assertThat(DailyCloseStates.ready(List.of(
            new CheckDecision("INTERNAL", true, false, CheckStatus.PASS),
            new CheckDecision("EXTERNAL", false, true, CheckStatus.BLOCKED)))).isTrue();
        assertThat(DailyCloseStates.ready(List.of(
            new CheckDecision("INTERNAL", true, false, CheckStatus.FAIL)))).isFalse();
        assertThat(DailyCloseStates.ready(List.of(
            new CheckDecision("OPTIONAL", false, false, CheckStatus.WARN)))).isFalse();
        assertThatThrownBy(() -> DailyCloseStates.ready(List.of(
            new CheckDecision("EXTERNAL", false, true, CheckStatus.PASS))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("绿色占位");
    }
}
