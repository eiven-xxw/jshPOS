package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** Internal application port used by admitted modules to publish downstream device changes. */
@Service
@RequiredArgsConstructor
public class SyncChangePublisher {

    private final TrustedTenantContext tenantContext;
    private final SyncMapper mapper;
    private final SyncIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public String publish(String stream, String eventType, String aggregateId, long aggregateVersion,
                          Map<String, Object> payload) {
        String tenantId = tenantContext.requireTenantId();
        SyncRules.requireStream(stream);
        SyncRules.requireEventType(eventType);
        SyncRules.requireUlid(aggregateId, "aggregateId");
        SyncRules.requirePositive(aggregateVersion, "aggregateVersion");
        String changeId = ids.next();
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SYNC_PAYLOAD_INVALID", exception);
        }
        mapper.insertChange(changeId, tenantId, stream, eventType, aggregateId, aggregateVersion,
            payloadJson, SyncHash.payload(objectMapper, payload),
            LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        return changeId;
    }
}
