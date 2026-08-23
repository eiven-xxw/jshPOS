package com.jingshanghui.pos.service.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** T2-SVC-001 项目与工单状态机固定向量。 */
class ServiceStatesTest {
    @Test
    void shouldAcceptNamedProjectTransitions() {
        assertAll(
            () -> assertDoesNotThrow(() -> ServiceStates.requireProjectTransition("DRAFT", "PREFLIGHTING")),
            () -> assertDoesNotThrow(() -> ServiceStates.requireProjectTransition("IN_PROGRESS", "BLOCKED")),
            () -> assertDoesNotThrow(() -> ServiceStates.requireProjectTransition("READY_TO_HANDOVER", "HANDED_OVER"))
        );
    }

    @Test
    void shouldRejectUnknownOrTerminalProjectTransition() {
        assertAll(
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireProjectTransition(null, "READY")),
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireProjectTransition("HANDED_OVER", "DRAFT")),
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireProjectTransition("UNKNOWN", "READY"))
        );
    }

    @Test
    void shouldAcceptNamedTicketTransitions() {
        assertAll(
            () -> assertDoesNotThrow(() -> ServiceStates.requireTicketTransition("OPEN", "ASSIGNED")),
            () -> assertDoesNotThrow(() -> ServiceStates.requireTicketTransition("IN_PROGRESS", "RESOLVED")),
            () -> assertDoesNotThrow(() -> ServiceStates.requireTicketTransition("RESOLVED", "CLOSED")),
            () -> assertDoesNotThrow(() -> ServiceStates.requireTicketTransition("CLOSED", "REOPENED"))
        );
    }

    @Test
    void shouldRejectIllegalTicketTransition() {
        assertAll(
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireTicketTransition("OPEN", "CLOSED")),
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireTicketTransition("CANCELLED", "OPEN")),
            () -> assertThrows(ServiceException.class, () -> ServiceStates.requireTicketTransition("OPEN", null))
        );
    }
}
