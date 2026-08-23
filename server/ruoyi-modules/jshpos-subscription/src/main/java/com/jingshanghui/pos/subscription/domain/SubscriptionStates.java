package com.jingshanghui.pos.subscription.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

/** T2-SUB-001 商用订阅具名状态机；历史通过事件追加，投影只能条件前进。 */
public final class SubscriptionStates {
    public enum State {
        /** 草稿，尚未产生授权效果。 */ DRAFT,
        /** 激活事务处理中。 */ PENDING_ACTIVATION,
        /** 正常有效。 */ ACTIVE,
        /** 已到期但仍在宽限窗口。 */ GRACE_PERIOD,
        /** 人工暂停，仅保留恢复白名单。 */ SUSPENDED,
        /** 宽限结束，仅保留恢复白名单。 */ EXPIRED,
        /** 逻辑终止待确认。 */ TERMINATION_PENDING,
        /** 逻辑终止，历史仍保留。 */ TERMINATED,
        /** 恢复事务处理中。 */ RESTORED
    }

    private static final EnumMap<State, Set<State>> ALLOWED = new EnumMap<>(State.class);
    static {
        ALLOWED.put(State.DRAFT, EnumSet.of(State.PENDING_ACTIVATION));
        ALLOWED.put(State.PENDING_ACTIVATION, EnumSet.of(State.ACTIVE));
        ALLOWED.put(State.ACTIVE, EnumSet.of(State.ACTIVE, State.GRACE_PERIOD, State.EXPIRED,
            State.SUSPENDED, State.TERMINATION_PENDING));
        ALLOWED.put(State.GRACE_PERIOD, EnumSet.of(State.GRACE_PERIOD, State.EXPIRED, State.SUSPENDED,
            State.RESTORED, State.TERMINATION_PENDING));
        ALLOWED.put(State.SUSPENDED, EnumSet.of(State.RESTORED, State.TERMINATION_PENDING));
        ALLOWED.put(State.EXPIRED, EnumSet.of(State.RESTORED, State.TERMINATION_PENDING));
        ALLOWED.put(State.TERMINATION_PENDING, EnumSet.of(State.TERMINATED, State.RESTORED));
        ALLOWED.put(State.TERMINATED, EnumSet.of(State.RESTORED));
        ALLOWED.put(State.RESTORED, EnumSet.of(State.ACTIVE));
    }

    private SubscriptionStates() { }

    public static void requireTransition(String from, String to) {
        State source = parse(from); State target = parse(to);
        if (!ALLOWED.getOrDefault(source, Set.of()).contains(target)) {
            throw new ServiceException("SUB-STATE-001: 非法订阅状态迁移 " + source + " -> " + target, 409);
        }
    }

    public static State parse(String state) {
        try { return State.valueOf(SubscriptionRules.required(state, "state")); }
        catch (IllegalArgumentException ex) { throw new ServiceException("SUB-STATE-002: 未知订阅状态", 409); }
    }
}
