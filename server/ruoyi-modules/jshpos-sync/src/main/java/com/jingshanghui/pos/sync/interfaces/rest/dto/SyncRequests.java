package com.jingshanghui.pos.sync.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SyncRequests {

    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256 = "^[a-f0-9]{64}$";

    private SyncRequests() {
    }

    public record Push(
        @Pattern(regexp = "^1\\.0$") String protocolVersion,
        @Pattern(regexp = ULID) String batchId,
        @NotEmpty @Size(max = 100) List<@Valid Event> events
    ) {
    }

    public record Event(
        @Pattern(regexp = ULID) String eventId,
        @Pattern(regexp = "^[a-z][a-z0-9.-]{0,63}$") String stream,
        @Pattern(regexp = "^[a-z][a-z0-9.-]{0,95}\\.v[1-9][0-9]*$") String eventType,
        @Min(1) int eventVersion,
        @Pattern(regexp = ULID) String aggregateId,
        @Min(1) long aggregateVersion,
        @Pattern(regexp = ULID) String deviceId,
        @Pattern(regexp = "^[1-9][0-9]{0,18}$") String storeId,
        @Pattern(regexp = ULID) String terminalId,
        @Min(1) long sequenceNo,
        @NotNull Instant occurredAt,
        @NotBlank @Size(min = 16, max = 128) String idempotencyKey,
        @NotBlank @Size(min = 16, max = 64) String correlationId,
        @Pattern(regexp = SHA256) String payloadHash,
        @NotNull Map<String, Object> payload
    ) {
    }

    public record Ack(
        @Pattern(regexp = "^[a-z][a-z0-9.-]{0,63}$") String stream,
        @Pattern(regexp = ULID) String cursor,
        @NotNull @Size(max = 500) List<@Pattern(regexp = ULID) String> appliedChangeIds,
        @Pattern(regexp = SHA256) String pageDigest
    ) {
    }

    public record PullQuery(
        @Pattern(regexp = "^[a-z][a-z0-9.-]{0,63}$") String stream,
        @Pattern(regexp = ULID) String cursor,
        @Min(1) @Max(500) int limit
    ) {
    }
}
