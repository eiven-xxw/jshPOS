package com.jingshanghui.pos.sync.application.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class SyncModels {

    private SyncModels() {
    }

    public record DeviceRecord(String deviceId, String tenantId, Long storeId, String terminalId,
                               Long boundUserId, String status, String minProtocolVersion,
                               String maxProtocolVersion, Long recordVersion) {
    }

    public record DeviceContext(String tenantId, String deviceId, Long storeId, String terminalId,
                                Long userId, String protocolVersion) {
    }

    public record EventEnvelope(String eventId, String stream, String eventType, int eventVersion, String aggregateId,
                                long aggregateVersion, String deviceId, String storeId, String terminalId,
                                long sequenceNo, Instant occurredAt, String idempotencyKey,
                                String correlationId, String payloadHash, Map<String, Object> payload) {
    }

    public record PushCommand(String protocolVersion, String batchId, List<EventEnvelope> events) {
    }

    public record EventAck(String eventId, String payloadHash, String status,
                           String resultCode, Long retryAfterMs) {
    }

    public record PushResult(String batchId, List<EventAck> acks, Instant serverTime) {
    }

    public record EventResult(String eventId, String status, String payloadHash,
                              String resultCode, Long retryAfterMs, Instant updatedAt) {
    }

    public record InboxRecord(String eventId, String tenantId, String deviceId, String eventType,
                              String aggregateId, Long aggregateVersion, String payloadHash,
                              String payloadJson, String processingStatus, String resultCode,
                              Integer processingAttempts, LocalDateTime updatedAt) {
    }

    public record BusinessFactRecord(String sourceEventId, String payloadHash) {
    }

    public enum ReceiptDisposition {NEW, DUPLICATE, PROCESSABLE_REPLAY, DEVICE_BLOCKED}

    public record InboxReceipt(ReceiptDisposition disposition, InboxRecord inbox, EventAck ack) {
    }

    public record ChangeRecord(Long changeSequence, String changeId, String streamCode, String eventType,
                               String aggregateId, Long aggregateVersion, String payloadJson,
                               String payloadHash, LocalDateTime publishedAt) {
    }

    public record Change(String changeId, String eventType, String aggregateId, long aggregateVersion,
                         String payloadHash, Map<String, Object> payload, Instant publishedAt) {
    }

    public record PullPage(String stream, List<Change> changes, String nextCursor,
                           String pageDigest, boolean hasMore) {
    }

    public record PullPageRecord(String cursorToken, String tenantId, String deviceId, String streamCode,
                                 Long fromSequence, Long toSequence, String changeIdsJson,
                                 String pageSha256, String status) {
    }

    public record CursorRecord(String streamCode, Long ackedSequence, String ackedCursorToken,
                               String pageSha256) {
    }

    public record AckCommand(String stream, String cursor, List<String> appliedChangeIds,
                             String pageDigest) {
    }

    public record BootstrapResult(String deviceId, String storeId, String terminalId,
                                  String protocolVersion, List<String> compatibleClientVersions,
                                  SyncLimits limits, Map<String, String> cursors) {
    }

    public record SyncLimits(int maxBatchEvents, int maxBatchBytes, int maxEventBytes) {
    }
}
