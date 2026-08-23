package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 门店日结具名状态机；关闭事实只能通过新更正版本修正。 */
public final class DailyCloseStates {
    private DailyCloseStates() {
    }

    public enum CloseState {
        DRAFT, PREFLIGHTING, PREFLIGHT_FAILED, READY, APPROVED, CLOSING, CLOSED,
        FAILED, CORRECTION_REQUIRED, COMPENSATION_REQUIRED
    }

    public enum CheckStatus { PASS, FAIL, BLOCKED, UNAVAILABLE, WARN }

    private static final Map<CloseState, Set<CloseState>> TRANSITIONS = Map.of(
        CloseState.DRAFT, EnumSet.of(CloseState.PREFLIGHTING),
        CloseState.CORRECTION_REQUIRED, EnumSet.of(CloseState.PREFLIGHTING),
        CloseState.PREFLIGHT_FAILED, EnumSet.of(CloseState.PREFLIGHTING),
        CloseState.FAILED, EnumSet.of(CloseState.PREFLIGHTING, CloseState.COMPENSATION_REQUIRED),
        CloseState.PREFLIGHTING, EnumSet.of(CloseState.READY, CloseState.PREFLIGHT_FAILED, CloseState.FAILED),
        CloseState.READY, EnumSet.of(CloseState.PREFLIGHTING, CloseState.APPROVED),
        CloseState.APPROVED, EnumSet.of(CloseState.CLOSING, CloseState.FAILED),
        CloseState.CLOSING, EnumSet.of(CloseState.CLOSED, CloseState.FAILED, CloseState.COMPENSATION_REQUIRED),
        CloseState.CLOSED, EnumSet.noneOf(CloseState.class),
        CloseState.COMPENSATION_REQUIRED, EnumSet.noneOf(CloseState.class)
    );

    public static void requireTransition(CloseState from, CloseState to) {
        if (from == null || to == null || !TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ServiceException("OPS-STATE-001: 非法日结状态迁移 " + from + "->" + to, 409);
        }
    }

    public static boolean ready(Iterable<CheckDecision> checks) {
        boolean seenRequired = false;
        for (CheckDecision check : checks) {
            if (check.external() && check.status() == CheckStatus.PASS) {
                throw new ServiceException("OPS-CHECK-001: 外部阻断不得创建绿色占位", 409);
            }
            if (check.required()) {
                seenRequired = true;
                if (check.status() != CheckStatus.PASS) return false;
            }
        }
        return seenRequired;
    }

    public record CheckDecision(String code, boolean required, boolean external, CheckStatus status) {
    }
}
