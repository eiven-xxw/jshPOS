package com.jingshanghui.pos.sync.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SyncRulesTest {

    @Test
    void validatesIdentifiersStreamsEventsHashesAndLimits() {
        assertThat(SyncRules.requireUlid("01K2A000000000000000000001", "eventId")).hasSize(26);
        assertThat(SyncRules.requireStream("sync.control")).isEqualTo("sync.control");
        assertThat(SyncRules.requireEventType("order.completed.v1")).endsWith(".v1");
        assertThat(SyncRules.requireHash("a".repeat(64), "hash")).hasSize(64);
        SyncRules.requirePositive(1, "version");
        assertThat(SyncRules.supportsPosFact("order.completed.v1")).isTrue();
        assertThat(SyncRules.supportsPosFact("shift.cash-movement.recorded.v1")).isTrue();
        assertThat(SyncRules.supportsPosFact("shift.drawer-requested.v1")).isTrue();
        assertThat(SyncRules.supportsPosFact("payment.completed.v1")).isFalse();
        assertThat(SyncRules.clampPullLimit(-1)).isEqualTo(1);
        assertThat(SyncRules.clampPullLimit(100)).isEqualTo(100);
        assertThat(SyncRules.clampPullLimit(1000)).isEqualTo(500);
        assertThat(SyncRules.isSuccessfulAck("ACCEPTED")).isTrue();
        assertThat(SyncRules.isSuccessfulAck("ACCEPTED_PENDING")).isTrue();
        assertThat(SyncRules.isSuccessfulAck("DUPLICATE")).isTrue();
        assertThat(SyncRules.isSuccessfulAck("CONFLICT")).isFalse();
    }

    @Test
    void rejectsEveryMalformedBoundary() {
        assertThatThrownBy(() -> SyncRules.requireUlid("uuid", "eventId")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireUlid(null, "eventId")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireStream("Sync Control")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireStream(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireEventType("order.completed")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireEventType(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireHash("ABC", "hash")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requireHash(null, "hash")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> SyncRules.requirePositive(0, "version")).isInstanceOf(ServiceException.class);
    }
}
