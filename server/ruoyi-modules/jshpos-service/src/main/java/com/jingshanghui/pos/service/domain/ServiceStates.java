package com.jingshanghui.pos.service.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 服务运营具名状态机。状态迁移只能由 Service 应用服务调用，禁止 Controller 或前端复制。
 */
public final class ServiceStates {
    private static final Map<String, Set<String>> PROJECT = Map.of(
        "DRAFT", Set.of("PREFLIGHTING", "CANCELLED"),
        "PREFLIGHTING", Set.of("PREFLIGHT_FAILED", "READY", "CANCELLED"),
        "PREFLIGHT_FAILED", Set.of("PREFLIGHTING", "CANCELLED"),
        "READY", Set.of("IN_PROGRESS", "CANCELLED"),
        "IN_PROGRESS", Set.of("BLOCKED", "READY_TO_HANDOVER", "CANCELLED"),
        "BLOCKED", Set.of("IN_PROGRESS", "CANCELLED"),
        "READY_TO_HANDOVER", Set.of("HANDED_OVER", "IN_PROGRESS"),
        "HANDED_OVER", Set.of(), "CANCELLED", Set.of());

    private static final Map<String, Set<String>> TICKET = Map.of(
        "OPEN", Set.of("ASSIGNED", "CANCELLED"),
        "ASSIGNED", Set.of("ASSIGNED", "IN_PROGRESS", "CANCELLED"),
        "IN_PROGRESS", Set.of("ASSIGNED", "WAITING_INPUT", "RESOLVED", "CANCELLED"),
        "WAITING_INPUT", Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED"),
        "RESOLVED", Set.of("CLOSED", "REOPENED", "CANCELLED"),
        "CLOSED", Set.of("REOPENED"),
        "REOPENED", Set.of("ASSIGNED", "CANCELLED"),
        "CANCELLED", Set.of());

    private ServiceStates() { }

    /** 校验实施项目迁移，非法迁移必须失败关闭。 */
    public static void requireProjectTransition(String from, String to) {
        require(PROJECT, from, to, "SVC-STATE-001: 实施项目状态迁移非法");
    }

    /** 校验工单迁移，关闭重开仍只能追加历史。 */
    public static void requireTicketTransition(String from, String to) {
        require(TICKET, from, to, "SVC-STATE-001: 服务工单状态迁移非法");
    }

    private static void require(Map<String, Set<String>> graph, String from, String to, String message) {
        if (from == null || to == null || !graph.getOrDefault(from, Set.of()).contains(to)) {
            throw new ServiceException(message, 409);
        }
    }
}
