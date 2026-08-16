package com.jingshanghui.pos.payment.application.service;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.domain.PaymentHash;
import com.jingshanghui.pos.payment.infrastructure.persistence.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/** 支付状态、审计和 Outbox 的同事务追加服务。 */
@Service
@RequiredArgsConstructor
public class PaymentJournalService {

    private final PaymentMapper mapper;
    private final UlidGenerator ulids;

    @Transactional(propagation = Propagation.MANDATORY)
    public void history(String tenantId, String aggregateType, String aggregateId, String commandId,
                        String before, String after, long version, Long actorId, String reason,
                        LocalDateTime at) {
        mapper.insertHistory(tenantId, ulids.next(), aggregateType, aggregateId, commandId,
            before, after, version, actorId, reason, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void audit(String tenantId, Long storeId, String action, String aggregateType, String aggregateId,
                      Long actorId, Long approverId, String commandId, String before, String after,
                      Long amount, String currency, String requestHash, String reason, LocalDateTime at) {
        String traceId = MDC.get("traceId");
        mapper.insertAudit(tenantId, ulids.next(), storeId, action, aggregateType, aggregateId,
            actorId, approverId, commandId, traceId == null || traceId.isBlank() ? commandId : traceId,
            before, after, amount, currency, requestHash, reason, at);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void event(String tenantId, String eventType, String aggregateType, String aggregateId,
                      long version, String correlationId, Map<String, Object> payload, LocalDateTime at) {
        String json = CanonicalJson.from(payload).json();
        mapper.insertOutbox(tenantId, ulids.next(), "payment.domain", eventType, aggregateType,
            aggregateId, version, correlationId, json, PaymentHash.sha256(json), at);
    }
}
