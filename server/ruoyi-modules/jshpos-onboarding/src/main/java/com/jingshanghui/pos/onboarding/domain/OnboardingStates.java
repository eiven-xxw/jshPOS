package com.jingshanghui.pos.onboarding.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 门店开通状态、检查结果和严格迁移规则。 */
public final class OnboardingStates {
    private OnboardingStates() {
    }

    public enum PlanState {
        DRAFT, PREFLIGHTING, PREFLIGHT_FAILED, READY, APPROVED, APPLYING, APPLIED, CHECKING,
        CHECK_FAILED, READY_TO_OPEN, OPENED, FAILED, COMPENSATION_REQUIRED, CANCELLED
    }

    public enum CheckStatus { PASS, FAIL, BLOCKED, UNAVAILABLE, WARN }

    private static final Map<PlanState, Set<PlanState>> TRANSITIONS = transitions();

    public static void requireTransition(PlanState from, PlanState to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new ServiceException("ONB-STATE-001: 非法开店状态迁移 " + from + " -> " + to, 409);
        }
    }

    /** 内部失败进入 CHECK_FAILED；只有外部阻断时仍可形成内部 READY_TO_OPEN。 */
    public static PlanState checkTarget(List<CheckDecision> checks) {
        if (checks == null || checks.isEmpty()) {
            throw new ServiceException("ONB-CHECK-001: 开店检查不能为空", 409);
        }
        boolean internalFailure = checks.stream().anyMatch(value -> value.required() && !value.external()
            && value.status() != CheckStatus.PASS);
        if (internalFailure) return PlanState.CHECK_FAILED;
        boolean unknownRequired = checks.stream().anyMatch(value -> value.required() && value.external()
            && value.status() == CheckStatus.FAIL);
        return unknownRequired ? PlanState.CHECK_FAILED : PlanState.READY_TO_OPEN;
    }

    public static void requireAllRequiredPass(List<CheckDecision> checks) {
        if (checks == null || checks.isEmpty() || checks.stream().anyMatch(value -> value.required()
            && value.status() != CheckStatus.PASS)) {
            throw new ServiceException("ONB-CHECK-002: 全部必需检查未通过，禁止形成 OPENED", 409);
        }
    }

    /** @param external 是否为支付、硬件、打印或伙伴外部 P0。 */
    public record CheckDecision(String code, boolean required, boolean external, CheckStatus status) {
        public CheckDecision {
            if (code == null || code.isBlank() || status == null) {
                throw new ServiceException("ONB-CHECK-003: 检查决策字段缺失", 400);
            }
            if (external && status == CheckStatus.WARN) {
                throw new ServiceException("ONB-CHECK-004: 外部 P0 不得降级为 WARN", 409);
            }
        }
    }

    private static Map<PlanState, Set<PlanState>> transitions() {
        Map<PlanState, Set<PlanState>> value = new EnumMap<>(PlanState.class);
        value.put(PlanState.DRAFT, EnumSet.of(PlanState.PREFLIGHTING, PlanState.CANCELLED));
        value.put(PlanState.PREFLIGHTING, EnumSet.of(PlanState.PREFLIGHT_FAILED, PlanState.READY, PlanState.FAILED));
        value.put(PlanState.PREFLIGHT_FAILED, EnumSet.of(PlanState.PREFLIGHTING, PlanState.CANCELLED));
        value.put(PlanState.READY, EnumSet.of(PlanState.APPROVED, PlanState.PREFLIGHTING, PlanState.CANCELLED));
        value.put(PlanState.APPROVED, EnumSet.of(PlanState.APPLYING, PlanState.FAILED));
        value.put(PlanState.APPLYING, EnumSet.of(PlanState.APPLIED, PlanState.FAILED, PlanState.COMPENSATION_REQUIRED));
        value.put(PlanState.APPLIED, EnumSet.of(PlanState.CHECKING));
        value.put(PlanState.CHECKING, EnumSet.of(PlanState.CHECK_FAILED, PlanState.READY_TO_OPEN, PlanState.FAILED));
        value.put(PlanState.CHECK_FAILED, EnumSet.of(PlanState.CHECKING, PlanState.COMPENSATION_REQUIRED));
        value.put(PlanState.READY_TO_OPEN, EnumSet.of(PlanState.CHECKING, PlanState.OPENED));
        value.put(PlanState.FAILED, EnumSet.of(PlanState.PREFLIGHTING));
        value.put(PlanState.COMPENSATION_REQUIRED, EnumSet.of(PlanState.CHECKING));
        value.put(PlanState.OPENED, Set.of());
        value.put(PlanState.CANCELLED, Set.of());
        return Map.copyOf(value);
    }
}
