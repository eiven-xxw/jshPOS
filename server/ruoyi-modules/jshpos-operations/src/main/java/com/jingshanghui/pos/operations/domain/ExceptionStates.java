package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.EnumSet;
import java.util.Map;

/** 统一异常案件状态机；所有迁移还必须写只追加状态历史。 */
public final class ExceptionStates {
    private ExceptionStates() { }

    public enum CaseState { OPEN, CLAIMED, IN_PROGRESS, WAITING_OWNER, RESOLVED, CLOSED, REOPENED, FAILED }

    private static final Map<CaseState, EnumSet<CaseState>> ALLOWED = Map.of(
        CaseState.OPEN, EnumSet.of(CaseState.CLAIMED),
        CaseState.CLAIMED, EnumSet.of(CaseState.IN_PROGRESS, CaseState.CLAIMED),
        CaseState.IN_PROGRESS, EnumSet.of(CaseState.WAITING_OWNER, CaseState.RESOLVED, CaseState.FAILED, CaseState.CLAIMED),
        CaseState.WAITING_OWNER, EnumSet.of(CaseState.IN_PROGRESS, CaseState.RESOLVED, CaseState.FAILED, CaseState.CLAIMED),
        CaseState.RESOLVED, EnumSet.of(CaseState.CLOSED, CaseState.REOPENED),
        CaseState.CLOSED, EnumSet.of(CaseState.REOPENED),
        CaseState.REOPENED, EnumSet.of(CaseState.CLAIMED),
        CaseState.FAILED, EnumSet.of(CaseState.REOPENED, CaseState.CLAIMED)
    );

    public static void requireTransition(CaseState from, CaseState to) {
        if (from == null || to == null || !ALLOWED.getOrDefault(from, EnumSet.noneOf(CaseState.class)).contains(to)) {
            throw new ServiceException("OPS-EXC-STATE-001: 异常案件状态迁移非法", 409);
        }
    }
}
