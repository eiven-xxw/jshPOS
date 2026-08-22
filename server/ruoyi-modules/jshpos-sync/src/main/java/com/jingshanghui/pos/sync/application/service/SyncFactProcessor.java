package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.BusinessFactRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventAck;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.port.PosTenderCommandPort;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SyncFactProcessor {

    private final SyncMapper mapper;
    private final ObjectMapper objectMapper;
    private final SyncIdGenerator ids;
    private final Clock clock;
    private final PromotedOrderEventDispatcher promotedOrderEvents;
    private final ShiftEventDispatcher shiftEvents;
    private final ReceiptEventDispatcher receiptEvents;
    private final OrderDispositionEventDispatcher orderDispositionEvents;
    private final PosTenderCommandPort tenderCommands;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventAck apply(DeviceContext context, EventEnvelope event) {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!SyncRules.supportsPosFact(event.eventType())) {
            mapper.updateInboxResult(context.tenantId(), event.eventId(), "FINAL_REJECTED",
                "EVENT_VERSION_UNSUPPORTED", now);
            return new EventAck(event.eventId(), event.payloadHash(), "REJECTED_FINAL",
                "EVENT_VERSION_UNSUPPORTED", null);
        }
        BusinessFactRecord existing = mapper.findBusinessFact(context.tenantId(), event.aggregateId(),
            event.aggregateVersion(), event.eventType());
        if (existing != null) {
            if (existing.sourceEventId().equals(event.eventId()) && existing.payloadHash().equals(event.payloadHash())) {
                mapper.updateInboxResult(context.tenantId(), event.eventId(), "APPLIED", "DUPLICATE", now);
                return new EventAck(event.eventId(), event.payloadHash(), "DUPLICATE", "BUSINESS_EFFECT_EXISTS", null);
            }
            mapper.updateInboxResult(context.tenantId(), event.eventId(), "CONFLICT",
                "AGGREGATE_VERSION_CONFLICT", now);
            return new EventAck(event.eventId(), event.payloadHash(), "CONFLICT",
                "AGGREGATE_VERSION_CONFLICT", null);
        }
        if (event.eventType().startsWith("shift.")) {
            shiftEvents.apply(context, event);
        } else if ("order.submitted.v2".equals(event.eventType())) {
            promotedOrderEvents.apply(context, event);
        } else if (event.eventType().startsWith("receipt.")) {
            receiptEvents.apply(context, event);
        } else if (event.eventType().equals("order.cancelled.v1")
            || event.eventType().equals("order.reversal-routed.v1")) {
            orderDispositionEvents.apply(context, event);
        } else if (event.eventType().equals("tender.plan-frozen.v1")) {
            tenderCommands.apply(context, event);
        }
        mapper.insertBusinessFact(ids.next(), context.tenantId(), event.eventId(), event.stream(), event.eventType(),
            event.aggregateId(), event.aggregateVersion(), serialize(event), event.payloadHash(), now);
        mapper.updateInboxResult(context.tenantId(), event.eventId(), "APPLIED", "APPLIED", now);
        return new EventAck(event.eventId(), event.payloadHash(), "ACCEPTED", "APPLIED", null);
    }

    private String serialize(EventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SYNC_PAYLOAD_INVALID", exception);
        }
    }
}
