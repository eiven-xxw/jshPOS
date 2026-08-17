package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/** 促销规则版本显式状态机。 */
public final class PromotionStates {
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
        "DRAFT", Set.of("VALIDATED", "REJECTED"),
        "VALIDATED", Set.of("APPROVED", "REJECTED"),
        "APPROVED", Set.of("PUBLISHED", "REJECTED"),
        "PUBLISHED", Set.of("PAUSED", "RETIRED"),
        "PAUSED", Set.of("PUBLISHED", "RETIRED")
    );

    private PromotionStates() {
    }

    /** 校验并返回目标状态。 */
    public static String requireTransition(String current, String requested) {
        if (current == null || requested == null
            || !TRANSITIONS.getOrDefault(current, Set.of()).contains(requested)) {
            throw new ServiceException("PRM-STATE-001: 非法规则版本状态迁移 " + current + " -> " + requested, 409);
        }
        return requested;
    }
}
