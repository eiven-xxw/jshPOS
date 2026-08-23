package com.jingshanghui.pos.operations.domain;

import com.jingshanghui.pos.operations.domain.ExceptionStates.CaseState;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExceptionStatesTest {
    @Test void acceptsEveryFrozenTransition() {
        allow(CaseState.OPEN,CaseState.CLAIMED);allow(CaseState.CLAIMED,CaseState.IN_PROGRESS);
        allow(CaseState.CLAIMED,CaseState.CLAIMED);allow(CaseState.IN_PROGRESS,CaseState.WAITING_OWNER);
        allow(CaseState.IN_PROGRESS,CaseState.RESOLVED);allow(CaseState.IN_PROGRESS,CaseState.FAILED);
        allow(CaseState.IN_PROGRESS,CaseState.CLAIMED);allow(CaseState.WAITING_OWNER,CaseState.IN_PROGRESS);
        allow(CaseState.WAITING_OWNER,CaseState.RESOLVED);allow(CaseState.WAITING_OWNER,CaseState.FAILED);
        allow(CaseState.WAITING_OWNER,CaseState.CLAIMED);allow(CaseState.RESOLVED,CaseState.CLOSED);
        allow(CaseState.RESOLVED,CaseState.REOPENED);allow(CaseState.CLOSED,CaseState.REOPENED);
        allow(CaseState.REOPENED,CaseState.CLAIMED);allow(CaseState.FAILED,CaseState.REOPENED);
        allow(CaseState.FAILED,CaseState.CLAIMED);
    }
    @Test void rejectsHistoryRewriteAndNulls(){bad(CaseState.CLOSED,CaseState.OPEN);bad(CaseState.OPEN,CaseState.RESOLVED);
        bad(CaseState.RESOLVED,CaseState.IN_PROGRESS);bad(null,CaseState.OPEN);bad(CaseState.OPEN,null);}
    private void allow(CaseState a,CaseState b){ExceptionStates.requireTransition(a,b);}
    private void bad(CaseState a,CaseState b){assertThatThrownBy(()->ExceptionStates.requireTransition(a,b)).isInstanceOf(ServiceException.class);}
}
