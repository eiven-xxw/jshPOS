package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.sync.application.model.SyncModels.AckCommand;
import com.jingshanghui.pos.sync.application.model.SyncModels.BootstrapResult;
import com.jingshanghui.pos.sync.application.model.SyncModels.Change;
import com.jingshanghui.pos.sync.application.model.SyncModels.ChangeRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.CursorRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventAck;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventResult;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxReceipt;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.PullPage;
import com.jingshanghui.pos.sync.application.model.SyncModels.PullPageRecord;
import com.jingshanghui.pos.sync.application.model.SyncModels.PushCommand;
import com.jingshanghui.pos.sync.application.model.SyncModels.PushResult;
import com.jingshanghui.pos.sync.application.model.SyncModels.ReceiptDisposition;
import com.jingshanghui.pos.sync.application.model.SyncModels.SyncLimits;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PosSyncService {

    private static final String PROTOCOL = "1.0";

    private final SyncDeviceContextService deviceContexts;
    private final SyncInboxReceiver inboxReceiver;
    private final SyncFactProcessor factProcessor;
    private final SyncFailureRecorder failureRecorder;
    private final SyncMapper mapper;
    private final ObjectMapper objectMapper;
    private final SyncIdGenerator ids;
    private final Clock clock;

    public BootstrapResult bootstrap(String deviceId) {
        DeviceContext context = deviceContexts.require(deviceId, PROTOCOL);
        Map<String, String> cursors = new LinkedHashMap<>();
        for (CursorRecord cursor : mapper.listCursors(context.tenantId(), context.deviceId())) {
            if (cursor.ackedCursorToken() != null) {
                cursors.put(cursor.streamCode(), cursor.ackedCursorToken());
            }
        }
        return new BootstrapResult(context.deviceId(), context.storeId().toString(), context.terminalId(),
            PROTOCOL, List.of("1.0"),
            new SyncLimits(SyncRules.MAX_BATCH_EVENTS, SyncRules.MAX_BATCH_BYTES, SyncRules.MAX_EVENT_BYTES),
            Map.copyOf(cursors));
    }

    public PushResult push(String deviceId, PushCommand command) {
        validateBatch(command);
        DeviceContext context = deviceContexts.require(deviceId, command.protocolVersion());
        List<EventAck> acks = new ArrayList<>();
        boolean blocked = false;
        for (EventEnvelope event : command.events()) {
            if (blocked) {
                acks.add(new EventAck(event.eventId(), event.payloadHash(), "DEVICE_BLOCKED",
                    "BATCH_ABORTED_AFTER_SECURITY_BLOCK", null));
                continue;
            }
            InboxReceipt receipt = inboxReceiver.receive(context, command.batchId(), event);
            if (receipt.disposition() == ReceiptDisposition.DEVICE_BLOCKED) {
                acks.add(receipt.ack());
                blocked = true;
                continue;
            }
            if (receipt.ack() != null) {
                acks.add(receipt.ack());
                continue;
            }
            try {
                acks.add(factProcessor.apply(context, event));
            } catch (RuntimeException failure) {
                acks.add(failureRecorder.record(context, event, failure));
            }
        }
        return new PushResult(command.batchId(), List.copyOf(acks), clock.instant());
    }

    public EventResult result(String deviceId, String eventId) {
        DeviceContext context = deviceContexts.require(deviceId, PROTOCOL);
        SyncRules.requireUlid(eventId, "eventId");
        InboxRecord inbox = mapper.findInbox(context.tenantId(), eventId);
        if (inbox == null || !context.deviceId().equals(inbox.deviceId())) {
            throw new ServiceException("SYNC_EVENT_NOT_FOUND: event is not durable for this device", 404);
        }
        String status = switch (inbox.processingStatus()) {
            case "APPLIED" -> "ACCEPTED";
            case "RECEIVED" -> "ACCEPTED_PENDING";
            case "RETRY" -> "REJECTED_RETRYABLE";
            case "CONFLICT" -> "CONFLICT";
            case "FINAL_REJECTED", "DEAD_LETTER" -> "REJECTED_FINAL";
            default -> "REJECTED_RETRYABLE";
        };
        Long retryAfter = "REJECTED_RETRYABLE".equals(status) ? 1000L : null;
        return new EventResult(eventId, status, inbox.payloadHash(), inbox.resultCode(), retryAfter,
            inbox.updatedAt().toInstant(ZoneOffset.UTC));
    }

    @Transactional
    public PullPage pull(String deviceId, String stream, String cursor, int requestedLimit) {
        DeviceContext context = deviceContexts.require(deviceId, PROTOCOL);
        SyncRules.requireStream(stream);
        long after = startingSequence(context, stream, cursor);
        int limit = SyncRules.clampPullLimit(requestedLimit);
        List<ChangeRecord> records = mapper.findChanges(context.tenantId(), stream, after, limit);
        List<String> changeIds = records.stream().map(ChangeRecord::changeId).toList();
        List<String> hashes = records.stream().map(ChangeRecord::payloadHash).toList();
        String pageHash = SyncHash.page(changeIds, hashes);
        long nextSequence = records.isEmpty() ? after : records.get(records.size() - 1).changeSequence();
        String nextCursor = ids.next();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        mapper.insertPullPage(nextCursor, context.tenantId(), context.deviceId(), stream, after, nextSequence,
            writeJson(changeIds), pageHash, now);
        List<Change> changes = records.stream().map(record -> new Change(record.changeId(), record.eventType(),
            record.aggregateId(), record.aggregateVersion(), record.payloadHash(), readMap(record.payloadJson()),
            record.publishedAt().toInstant(ZoneOffset.UTC))).toList();
        return new PullPage(stream, changes, nextCursor, pageHash,
            mapper.hasChangesAfter(context.tenantId(), stream, nextSequence));
    }

    @Transactional
    public void acknowledge(String deviceId, AckCommand command) {
        DeviceContext context = deviceContexts.require(deviceId, PROTOCOL);
        SyncRules.requireStream(command.stream());
        SyncRules.requireUlid(command.cursor(), "cursor");
        SyncRules.requireHash(command.pageDigest(), "pageDigest");
        PullPageRecord page = mapper.findPullPage(context.tenantId(), context.deviceId(), command.cursor());
        if (page == null || !command.stream().equals(page.streamCode())) {
            throw new ServiceException("SYNC_CURSOR_INVALID: cursor does not belong to this stream", 409);
        }
        List<String> offeredIds = readStringList(page.changeIdsJson());
        if (!offeredIds.equals(command.appliedChangeIds()) || !page.pageSha256().equals(command.pageDigest())) {
            throw new ServiceException("SYNC_ACK_MISMATCH: applied page evidence differs", 409);
        }
        CursorRecord current = mapper.lockCursor(context.tenantId(), context.deviceId(), command.stream());
        if (current != null && page.toSequence() < current.ackedSequence()) {
            throw new ServiceException("SYNC_CURSOR_REGRESSION: cursor must be monotonic", 409);
        }
        mapper.upsertCursor(context.tenantId(), context.deviceId(), command.stream(), page.toSequence(),
            command.cursor(), command.pageDigest());
        mapper.acknowledgePullPage(context.tenantId(), context.deviceId(), command.cursor(),
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private long startingSequence(DeviceContext context, String stream, String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            SyncRules.requireUlid(cursor, "cursor");
            PullPageRecord page = mapper.findPullPage(context.tenantId(), context.deviceId(), cursor);
            if (page == null || !stream.equals(page.streamCode()) || !"ACKED".equals(page.status())) {
                throw new ServiceException("SYNC_CURSOR_INVALID: cursor is corrupt or belongs to another stream", 409);
            }
            return page.toSequence();
        }
        CursorRecord current = mapper.lockCursor(context.tenantId(), context.deviceId(), stream);
        return current == null ? 0L : current.ackedSequence();
    }

    private void validateBatch(PushCommand command) {
        SyncRules.requireUlid(command.batchId(), "batchId");
        if (!PROTOCOL.equals(command.protocolVersion()) || command.events() == null || command.events().isEmpty()
            || command.events().size() > SyncRules.MAX_BATCH_EVENTS) {
            throw new ServiceException("SYNC_BATCH_INVALID: protocol or event count is invalid", 400);
        }
        try {
            byte[] batch = objectMapper.writeValueAsBytes(command);
            if (batch.length > SyncRules.MAX_BATCH_BYTES) {
                throw new ServiceException("SYNC_BATCH_TOO_LARGE: maximum is 2 MiB", 413);
            }
            for (EventEnvelope event : command.events()) {
                if (objectMapper.writeValueAsBytes(event).length > SyncRules.MAX_EVENT_BYTES) {
                    throw new ServiceException("SYNC_EVENT_TOO_LARGE: maximum is 256 KiB", 413);
                }
            }
        } catch (JsonProcessingException exception) {
            throw new ServiceException("SYNC_BATCH_INVALID: cannot serialize request", 400);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SYNC_JSON_INVALID", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SYNC_STORED_PAYLOAD_INVALID", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("SYNC_STORED_PAGE_INVALID", exception);
        }
    }
}
