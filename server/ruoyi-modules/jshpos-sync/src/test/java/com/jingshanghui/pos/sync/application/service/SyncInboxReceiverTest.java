package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.ReceiptDisposition;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncInboxReceiverTest {

    private static final String EVENT = "01K2A000000000000000000081";
    private static final String DEVICE = "01K2A000000000000000000011";
    private static final String BATCH = "01K2A000000000000000000091";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DeviceContext context = new DeviceContext("TENANT_A", DEVICE, 1101L, DEVICE, 101L, "1.0");

    @Test
    void commitsNewInboxBeforeReturningForProcessing() {
        SyncMapper mapper = mock(SyncMapper.class);
        EventEnvelope event = event(payload());
        InboxRecord inserted = inbox(event.payloadHash(), "RECEIVED");
        when(mapper.findInbox("TENANT_A", EVENT)).thenReturn(null, inserted);
        SyncInboxReceiver receiver = receiver(mapper);

        var receipt = receiver.receive(context, BATCH, event);

        assertThat(receipt.disposition()).isEqualTo(ReceiptDisposition.NEW);
        verify(mapper).insertInbox(anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
            anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void sameIdentityAndHashIsDuplicateButDifferentHashBlocksDevice() {
        SyncMapper duplicateMapper = mock(SyncMapper.class);
        EventEnvelope event = event(payload());
        when(duplicateMapper.findInbox("TENANT_A", EVENT)).thenReturn(inbox(event.payloadHash(), "APPLIED"));
        assertThat(receiver(duplicateMapper).receive(context, BATCH, event).ack().status()).isEqualTo("DUPLICATE");

        SyncMapper attackMapper = mock(SyncMapper.class);
        when(attackMapper.findInbox("TENANT_A", EVENT)).thenReturn(inbox("b".repeat(64), "APPLIED"));
        var blocked = receiver(attackMapper).receive(context, BATCH, event);
        assertThat(blocked.disposition()).isEqualTo(ReceiptDisposition.DEVICE_BLOCKED);
        verify(attackMapper).blockDevice("TENANT_A", DEVICE, "EVENT_HASH_MISMATCH");
        verify(attackMapper).insertSecurityEvent(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
        verify(attackMapper, never()).insertInbox(anyString(), anyString(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(),
            org.mockito.ArgumentMatchers.anyInt(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
            anyString(), anyString(), any(), anyString(), anyString(), any());
    }

    @Test
    void trustedDeviceStoreAndTerminalCannotBeOverridden() {
        SyncMapper mapper = mock(SyncMapper.class);
        EventEnvelope valid = event(payload());
        EventEnvelope attack = new EventEnvelope(valid.eventId(), valid.stream(), valid.eventType(), 1,
            valid.aggregateId(), 4, "01K2B000000000000000000011", "2101",
            "01K2B000000000000000000011", 1, valid.occurredAt(), valid.idempotencyKey(),
            valid.correlationId(), valid.payloadHash(), valid.payload());
        assertThatThrownBy(() -> receiver(mapper).receive(context, BATCH, attack))
            .isInstanceOf(ServiceException.class).hasMessageContaining("trusted registry binding wins");
    }

    private SyncInboxReceiver receiver(SyncMapper mapper) {
        SyncIdGenerator ids = mock(SyncIdGenerator.class);
        when(ids.next()).thenReturn("01K2A000000000000000000099");
        return new SyncInboxReceiver(mapper, objectMapper, ids,
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC));
    }

    private EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope(EVENT, "order.command", "order.completed.v1", 1,
            "01K2A000000000000000000031", 4, DEVICE, "1101", DEVICE, 1,
            Instant.parse("2026-08-16T08:00:00Z"), EVENT,
            "01K2A000000000000000000071", SyncHash.payload(objectMapper, payload), payload);
    }

    private Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", "01K2A000000000000000000031");
        payload.put("receivableAmountMinor", 1299);
        return payload;
    }

    private InboxRecord inbox(String hash, String status) {
        return new InboxRecord(EVENT, "TENANT_A", DEVICE, "order.completed.v1",
            "01K2A000000000000000000031", 4L, hash, "{}", status, "APPLIED", 1,
            LocalDateTime.parse("2026-08-16T08:00:00"));
    }
}
