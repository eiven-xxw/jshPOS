package com.jingshanghui.pos.sync.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.sync.application.model.SyncModels.AckCommand;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.model.SyncModels.PushCommand;
import com.jingshanghui.pos.sync.application.service.PosSyncService;
import com.jingshanghui.pos.sync.interfaces.rest.dto.SyncRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pos/v1/sync")
public class PosSyncController {

    private final PosSyncService service;

    @PostMapping("/bootstrap")
    @SaCheckPermission("pos:sync:operate")
    public R<?> bootstrap(@RequestHeader("X-Device-Id") @Pattern(regexp = SyncRequests.ULID) String deviceId) {
        return R.ok(service.bootstrap(deviceId));
    }

    @PostMapping("/push")
    @SaCheckPermission("pos:sync:operate")
    public R<?> push(@RequestHeader("X-Device-Id") @Pattern(regexp = SyncRequests.ULID) String deviceId,
                     @Valid @RequestBody SyncRequests.Push request) {
        var events = request.events().stream().map(event -> new EventEnvelope(
            event.eventId(), event.stream(), event.eventType(), event.eventVersion(), event.aggregateId(),
            event.aggregateVersion(), event.deviceId(), event.storeId(), event.terminalId(), event.sequenceNo(),
            event.occurredAt(), event.idempotencyKey(), event.correlationId(), event.payloadHash(), event.payload()
        )).toList();
        return R.ok(service.push(deviceId, new PushCommand(request.protocolVersion(), request.batchId(), events)));
    }

    @GetMapping("/results/{eventId}")
    @SaCheckPermission("pos:sync:operate")
    public R<?> result(@RequestHeader("X-Device-Id") @Pattern(regexp = SyncRequests.ULID) String deviceId,
                       @PathVariable @Pattern(regexp = SyncRequests.ULID) String eventId) {
        return R.ok(service.result(deviceId, eventId));
    }

    @GetMapping("/pull")
    @SaCheckPermission("pos:sync:operate")
    public R<?> pull(@RequestHeader("X-Device-Id") @Pattern(regexp = SyncRequests.ULID) String deviceId,
                     @RequestParam @Pattern(regexp = "^[a-z][a-z0-9.-]{0,63}$") String stream,
                     @RequestParam(required = false) @Pattern(regexp = SyncRequests.ULID) String cursor,
                     @RequestParam(defaultValue = "100") int limit) {
        return R.ok(service.pull(deviceId, stream, cursor, limit));
    }

    @PostMapping("/ack")
    @SaCheckPermission("pos:sync:operate")
    public ResponseEntity<Void> acknowledge(
        @RequestHeader("X-Device-Id") @Pattern(regexp = SyncRequests.ULID) String deviceId,
        @Valid @RequestBody SyncRequests.Ack request
    ) {
        service.acknowledge(deviceId,
            new AckCommand(request.stream(), request.cursor(), request.appliedChangeIds(), request.pageDigest()));
        return ResponseEntity.noContent().build();
    }
}
