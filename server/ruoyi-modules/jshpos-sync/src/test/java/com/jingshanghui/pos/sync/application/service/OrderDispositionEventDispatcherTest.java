package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.port.OrderDispositionSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderDispositionEventDispatcherTest {

    private static final String DISPOSITION = "01K2A000000000000000000072";
    private static final String ORDER = "01K2A000000000000000000051";
    private static final String TERMINAL = "01K2A000000000000000000011";
    private static final String SHIFT = "01K2A000000000000000000021";
    private static final String HASH = "a".repeat(64);
    private static final DeviceContext CONTEXT = new DeviceContext(
        "TENANT_A", "01K2A000000000000000000009", 1101L, TERMINAL, 101L, "1.0");

    private final OrderDispositionSubmissionPort port = mock(OrderDispositionSubmissionPort.class);
    private final OrderDispositionEventDispatcher dispatcher = new OrderDispositionEventDispatcher(port);

    @Test
    void dispatchesCancellationThroughTrustedDeviceScope() {
        EventEnvelope event = event("order.cancelled.v1", cancellationPayload());

        dispatcher.apply(CONTEXT, event);

        verify(port).record(DISPOSITION, DISPOSITION, ORDER, 1101L, TERMINAL, SHIFT, 101L,
            LocalDate.of(2026, 8, 21), "CANCEL_BEFORE_COMPLETION", "DRAFT", "CANCELLED",
            "CUSTOMER_CANCEL", "虚构顾客付款前取消", null, HASH, HASH, 2,
            LocalDateTime.of(2026, 8, 21, 3, 0));
    }

    @Test
    void rejectsClientStoreAndCashierReplacementBeforeOwnerCall() {
        Map<String, Object> payload = cancellationPayload();
        payload.put("storeId", "2202");
        payload.put("cashierId", "999");

        assertThatThrownBy(() -> dispatcher.apply(CONTEXT, event("order.cancelled.v1", payload)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CONTEXT_MISMATCH");

        verifyNoInteractions(port);
    }

    @Test
    void rejectsEventTypeAndDispositionTypeMismatch() {
        assertThatThrownBy(() -> dispatcher.apply(CONTEXT,
            event("order.reversal-routed.v1", cancellationPayload())))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不得伪装为取消");
    }

    private EventEnvelope event(String eventType, Map<String, Object> payload) {
        return new EventEnvelope(DISPOSITION, "order.command", eventType, 1, DISPOSITION, 1,
            CONTEXT.deviceId(), "1101", TERMINAL, 7, Instant.parse("2026-08-21T03:00:00Z"),
            "gate7b-order-cancel-0001", "01K2A000000000000000000031", HASH, payload);
    }

    private Map<String, Object> cancellationPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("dispositionId", DISPOSITION);
        payload.put("orderId", ORDER);
        payload.put("storeId", "1101");
        payload.put("terminalId", TERMINAL);
        payload.put("cashierId", "101");
        payload.put("shiftId", SHIFT);
        payload.put("businessDate", "2026-08-21");
        payload.put("dispositionType", "CANCEL_BEFORE_COMPLETION");
        payload.put("fromStatus", "DRAFT");
        payload.put("effectiveStatus", "CANCELLED");
        payload.put("reasonCode", "CUSTOMER_CANCEL");
        payload.put("reasonText", "虚构顾客付款前取消");
        payload.put("orderSnapshotSha256", HASH);
        payload.put("requestSha256", HASH);
        payload.put("aggregateVersion", 2);
        return payload;
    }
}
