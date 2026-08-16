package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OrderJournalService {

    private final OrderMapper mapper;
    private final UlidGenerator ulids;

    @Transactional(propagation = Propagation.MANDATORY)
    public String appendEvent(String tenantId, String stream, String eventType, String aggregateType,
                              String aggregateId, long version, String correlationId, String payloadJson,
                              LocalDateTime at) {
        String eventId = ulids.next();
        mapper.insertOutbox(tenantId, eventId, stream, eventType, aggregateType, aggregateId, version,
            correlationId, payloadJson, CanonicalHash.sha256(payloadJson), at);
        return eventId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void audit(String tenantId, String action, String aggregateType, String aggregateId,
                      Long actorId, Long approverId, String commandId, String beforeStatus, String afterStatus,
                      Long amountMinor, String requestHash, String reasonCode, LocalDateTime at) {
        String traceId = MDC.get("traceId");
        mapper.insertAudit(tenantId, ulids.next(), action, aggregateType, aggregateId, actorId, approverId,
            commandId, traceId == null || traceId.isBlank() ? commandId : traceId, beforeStatus, afterStatus,
            amountMinor, amountMinor == null ? null : "CNY", requestHash, reasonCode, at);
    }
}
