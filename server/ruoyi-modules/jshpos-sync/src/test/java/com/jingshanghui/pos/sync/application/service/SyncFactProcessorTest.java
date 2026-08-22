package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.BusinessFactRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.port.PosTenderCommandPort;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyncFactProcessorTest {

    private final ObjectMapper json = new ObjectMapper();
    private final DeviceContext context = new DeviceContext("TENANT_A", id(11), 1101L, id(11), 101L, "1.0");

    @Test
    void appliesOneImmutableBusinessFact() {
        SyncMapper mapper = mock(SyncMapper.class);
        SyncFactProcessor processor = processor(mapper);
        var ack = processor.apply(context, event("order.completed.v1", id(81), 4));
        assertThat(ack.status()).isEqualTo("ACCEPTED");
        verify(mapper).insertBusinessFact(anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), any());
        verify(mapper).updateInboxResult(eq("TENANT_A"), eq(id(81)), eq("APPLIED"), eq("APPLIED"), any());
    }

    @Test
    void rejectsUnsupportedVersionAndConflictingAggregateEffect() {
        SyncMapper unsupportedMapper = mock(SyncMapper.class);
        assertThat(processor(unsupportedMapper).apply(context, event("order.completed.v3", id(82), 4)).status())
            .isEqualTo("REJECTED_FINAL");

        SyncMapper conflictMapper = mock(SyncMapper.class);
        when(conflictMapper.findBusinessFact(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString()))
            .thenReturn(new BusinessFactRecord(id(99), "b".repeat(64)));
        assertThat(processor(conflictMapper).apply(context, event("order.completed.v1", id(83), 4)).status())
            .isEqualTo("CONFLICT");
    }

    @Test
    void replayOfSameAppliedEffectIsDuplicate() {
        SyncMapper mapper = mock(SyncMapper.class);
        EventEnvelope event = event("order.completed.v1", id(84), 4);
        when(mapper.findBusinessFact(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString()))
            .thenReturn(new BusinessFactRecord(event.eventId(), event.payloadHash()));
        assertThat(processor(mapper).apply(context, event).status()).isEqualTo("DUPLICATE");
    }

    @Test
    void dispatchesSubmittedV2ToOrderOwnerBeforeMarkingFactApplied() {
        SyncMapper mapper = mock(SyncMapper.class);
        PromotedOrderEventDispatcher dispatcher = mock(PromotedOrderEventDispatcher.class);
        EventEnvelope event = event("order.submitted.v2", id(85), 2);

        assertThat(processor(mapper, dispatcher).apply(context, event).status()).isEqualTo("ACCEPTED");

        verify(dispatcher).apply(context, event);
        verify(mapper).insertBusinessFact(anyString(), eq("TENANT_A"), eq(id(85)), anyString(),
            eq("order.submitted.v2"), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString(),
            anyString(), any());
    }

    @Test
    void dispatchesTenderFreezeToPaymentOwnerPortBeforeMarkingFactApplied() {
        SyncMapper mapper = mock(SyncMapper.class);
        PosTenderCommandPort tenderPort = mock(PosTenderCommandPort.class);
        EventEnvelope event = event("tender.plan-frozen.v1", id(86), 1);
        SyncIdGenerator ids = mock(SyncIdGenerator.class);
        when(ids.next()).thenReturn(id(90));
        SyncFactProcessor processor = new SyncFactProcessor(mapper, json, ids,
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC),
            mock(PromotedOrderEventDispatcher.class), mock(ShiftEventDispatcher.class),
            mock(ReceiptEventDispatcher.class), mock(OrderDispositionEventDispatcher.class), tenderPort);

        assertThat(processor.apply(context, event).status()).isEqualTo("ACCEPTED");
        verify(tenderPort).apply(context, event);
    }

    private SyncFactProcessor processor(SyncMapper mapper) {
        return processor(mapper, mock(PromotedOrderEventDispatcher.class));
    }

    private SyncFactProcessor processor(SyncMapper mapper, PromotedOrderEventDispatcher dispatcher) {
        SyncIdGenerator ids = mock(SyncIdGenerator.class);
        when(ids.next()).thenReturn(id(90));
        return new SyncFactProcessor(mapper, json, ids,
            Clock.fixed(Instant.parse("2026-08-16T08:00:00Z"), ZoneOffset.UTC), dispatcher,
            mock(ShiftEventDispatcher.class), mock(ReceiptEventDispatcher.class),
            mock(OrderDispositionEventDispatcher.class), mock(PosTenderCommandPort.class));
    }

    private EventEnvelope event(String type, String eventId, long version) {
        Map<String, Object> payload = Map.of("orderId", id(31), "amount", 1299);
        return new EventEnvelope(eventId, "order.command", type, type.endsWith("v2") ? 2 : 1,
            id(31), version, id(11), "1101", id(11), 1,
            Instant.parse("2026-08-16T08:00:00Z"), eventId, id(71),
            SyncHash.payload(json, payload), payload);
    }

    private static String id(int suffix) {
        return "01K2A0000000000000000000" + String.format("%02d", suffix);
    }
}
