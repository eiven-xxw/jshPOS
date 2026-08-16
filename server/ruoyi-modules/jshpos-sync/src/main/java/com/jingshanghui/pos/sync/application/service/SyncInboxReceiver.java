package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventAck;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxReceipt;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.ReceiptDisposition;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class SyncInboxReceiver {

    private final SyncMapper mapper;
    private final ObjectMapper objectMapper;
    private final SyncIdGenerator ids;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public InboxReceipt receive(DeviceContext context, String batchId, EventEnvelope event) {
        validateEnvelope(context, batchId, event);
        String computedHash = SyncHash.payload(objectMapper, event.payload());
        if (!computedHash.equals(event.payloadHash())) {
            throw new ServiceException("SYNC_PAYLOAD_HASH_INVALID: payload does not match payloadHash", 400);
        }
        InboxRecord existing = mapper.findInbox(context.tenantId(), event.eventId());
        if (existing != null) {
            return duplicate(context, event, existing);
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            mapper.insertInbox(context.tenantId(), context.deviceId(), batchId, event.eventId(), event.sequenceNo(),
                event.stream(), event.eventType(), event.eventVersion(), event.aggregateId(), event.aggregateVersion(),
                event.idempotencyKey(), event.correlationId(),
                LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC), serialize(event), event.payloadHash(), now);
        } catch (DuplicateKeyException concurrentDuplicate) {
            InboxRecord raced = mapper.findInbox(context.tenantId(), event.eventId());
            if (raced == null) {
                throw concurrentDuplicate;
            }
            return duplicate(context, event, raced);
        }
        InboxRecord inserted = mapper.findInbox(context.tenantId(), event.eventId());
        return new InboxReceipt(ReceiptDisposition.NEW, inserted, null);
    }

    private InboxReceipt duplicate(DeviceContext context, EventEnvelope event, InboxRecord existing) {
        if (!existing.payloadHash().equals(event.payloadHash())) {
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            mapper.blockDevice(context.tenantId(), context.deviceId(), "EVENT_HASH_MISMATCH");
            mapper.insertSecurityEvent(ids.next(), context.tenantId(), context.deviceId(), event.eventId(),
                "EVENT_HASH_MISMATCH_AND_BLOCK",
                SyncHash.evidence(existing.payloadHash(), event.payloadHash(), event.eventId()), now);
            EventAck ack = new EventAck(event.eventId(), event.payloadHash(), "DEVICE_BLOCKED",
                "EVENT_HASH_MISMATCH_AND_BLOCK", null);
            return new InboxReceipt(ReceiptDisposition.DEVICE_BLOCKED, existing, ack);
        }
        String status = existing.processingStatus();
        if ("APPLIED".equals(status)) {
            return new InboxReceipt(ReceiptDisposition.DUPLICATE, existing,
                new EventAck(event.eventId(), event.payloadHash(), "DUPLICATE", "BUSINESS_EFFECT_EXISTS", null));
        }
        if ("RECEIVED".equals(status) || "RETRY".equals(status)) {
            return new InboxReceipt(ReceiptDisposition.PROCESSABLE_REPLAY, existing, null);
        }
        String ackStatus = switch (status) {
            case "CONFLICT" -> "CONFLICT";
            case "DEAD_LETTER", "FINAL_REJECTED" -> "REJECTED_FINAL";
            default -> "REJECTED_RETRYABLE";
        };
        return new InboxReceipt(ReceiptDisposition.DUPLICATE, existing,
            new EventAck(event.eventId(), event.payloadHash(), ackStatus, existing.resultCode(),
                "REJECTED_RETRYABLE".equals(ackStatus) ? 1000L : null));
    }

    private void validateEnvelope(DeviceContext context, String batchId, EventEnvelope event) {
        SyncRules.requireUlid(batchId, "batchId");
        SyncRules.requireUlid(event.eventId(), "eventId");
        SyncRules.requireUlid(event.aggregateId(), "aggregateId");
        SyncRules.requireUlid(event.deviceId(), "deviceId");
        SyncRules.requireUlid(event.terminalId(), "terminalId");
        SyncRules.requireStream(event.stream());
        SyncRules.requireEventType(event.eventType());
        SyncRules.requireHash(event.payloadHash(), "payloadHash");
        SyncRules.requirePositive(event.eventVersion(), "eventVersion");
        SyncRules.requirePositive(event.aggregateVersion(), "aggregateVersion");
        SyncRules.requirePositive(event.sequenceNo(), "sequenceNo");
        if (event.occurredAt() == null || event.payload() == null || event.idempotencyKey() == null
            || event.idempotencyKey().length() < 16 || event.idempotencyKey().length() > 128
            || event.correlationId() == null || event.correlationId().length() < 16
            || event.correlationId().length() > 64) {
            throw new ServiceException("SYNC_INPUT_INVALID: incomplete event envelope", 400);
        }
        if (!context.deviceId().equals(event.deviceId())
            || !context.terminalId().equals(event.terminalId())
            || !context.storeId().toString().equals(event.storeId())) {
            throw new ServiceException("SYNC_DEVICE_CONTEXT_MISMATCH: trusted registry binding wins", 403);
        }
    }

    private String serialize(EventEnvelope event) {
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException exception) {
            throw new ServiceException("SYNC_PAYLOAD_INVALID: payload cannot be serialized", 400);
        }
    }
}
