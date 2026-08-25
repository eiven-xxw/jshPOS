package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.model.OrderCommands.RecordCashMovement;
import com.jingshanghui.pos.order.application.model.OrderCommands.CloseSyncedShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.RequestNoSaleDrawer;
import com.jingshanghui.pos.order.application.port.ShiftSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** POS010 同步转换只接受与可信设备上下文一致的班次现金和钱箱事实。 */
class ShiftEventDispatcherTest {
    private final ShiftSubmissionPort shifts = mock(ShiftSubmissionPort.class);
    private final ShiftEventDispatcher dispatcher = new ShiftEventDispatcher(shifts);
    private final DeviceContext trusted = new DeviceContext("TENANT_A", "DEVICE_A", 1101L,
        "01K2A000000000000000000011", 101L, "1.1");

    @Test
    void mapsCashMovementWithFrozenIdentityDirectionAndPriorVersion() {
        Map<String, Object> payload = basePayload();
        payload.put("movementId", "01K2A000000000000000000095");
        payload.put("movementType", "SAFE_DROP");
        payload.put("signedAmountMinor", -200);
        payload.put("reasonCode", "SAFE_DROP");
        payload.put("reasonText", "Synthetic safe drop");
        payload.put("authorizationRef", "SESSION_AUTH_REF_123456");
        payload.put("expectedVersion", 1);

        dispatcher.apply(trusted, event("shift.cash-movement.recorded.v1", payload));

        ArgumentCaptor<RecordCashMovement> captor = ArgumentCaptor.forClass(RecordCashMovement.class);
        verify(shifts).recordCashMovement(captor.capture());
        assertThat(captor.getValue().amountMinor()).isEqualTo(200);
        assertThat(captor.getValue().expectedVersion()).isEqualTo(1);
        assertThat(captor.getValue().movementType()).isEqualTo("SAFE_DROP");
    }

    @Test
    void mapsDrawerRequestButPreservesBlockedExternalState() {
        Map<String, Object> payload = basePayload();
        payload.put("drawerEventId", "01K2A000000000000000000096");
        payload.put("reasonCode", "CHANGE_REQUEST");
        payload.put("reasonText", "Synthetic drawer request");
        payload.put("authorizationRef", "SESSION_AUTH_REF_123456");
        payload.put("deviceExecutionStatus", "BLOCKED_EXTERNAL");
        payload.put("expectedVersion", 1);

        dispatcher.apply(trusted, event("shift.drawer-requested.v1", payload));

        ArgumentCaptor<RequestNoSaleDrawer> captor = ArgumentCaptor.forClass(RequestNoSaleDrawer.class);
        verify(shifts).requestNoSaleDrawer(captor.capture());
        assertThat(captor.getValue().expectedVersion()).isEqualTo(1);
        assertThat(captor.getValue().reasonCode()).isEqualTo("CHANGE_REQUEST");
    }

    @Test
    void mapsCloseToTheDedicatedSyncedCommandWithFrozenPriorVersion() {
        Map<String, Object> payload = basePayload();
        payload.put("actualCashMinor", 10990);

        dispatcher.apply(trusted, event("shift.closed.v1", payload));

        ArgumentCaptor<CloseSyncedShift> captor = ArgumentCaptor.forClass(CloseSyncedShift.class);
        verify(shifts).closeSynced(captor.capture());
        assertThat(captor.getValue().localExpectedVersion()).isEqualTo(1);
        assertThat(captor.getValue().actualCashMinor()).isEqualTo(10990);
    }

    @Test
    void rejectsStoreReplacementBeforeCallingOrderOwner() {
        Map<String, Object> payload = basePayload();
        payload.put("storeId", "2101");
        payload.put("drawerEventId", "01K2A000000000000000000096");
        payload.put("reasonCode", "CHANGE_REQUEST");
        payload.put("reasonText", "Synthetic drawer request");
        payload.put("authorizationRef", "SESSION_AUTH_REF_123456");
        payload.put("deviceExecutionStatus", "BLOCKED_EXTERNAL");
        payload.put("expectedVersion", 1);

        assertThatThrownBy(() -> dispatcher.apply(trusted, event("shift.drawer-requested.v1", payload)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("SYNC_CONTEXT_MISMATCH");
        verifyNoInteractions(shifts);
    }

    private Map<String, Object> basePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shiftId", "01K2A000000000000000000021");
        payload.put("storeId", "1101");
        payload.put("terminalId", trusted.terminalId());
        payload.put("cashierId", "101");
        payload.put("businessDate", "2026-08-21");
        return payload;
    }

    private EventEnvelope event(String type, Map<String, Object> payload) {
        return new EventEnvelope("01K2A000000000000000000201", "shift.event", type, 1,
            "01K2A000000000000000000021", 2, "DEVICE_A", "1101", trusted.terminalId(), 1,
            Instant.parse("2026-08-21T03:00:00Z"), "shift-operation-key-001",
            "01K2A000000000000000000211", "a".repeat(64), payload);
    }
}
