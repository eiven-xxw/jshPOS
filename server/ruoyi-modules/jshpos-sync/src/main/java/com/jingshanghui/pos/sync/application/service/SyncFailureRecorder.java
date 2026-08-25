package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventAck;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.model.SyncModels.InboxRecord;
import com.jingshanghui.pos.sync.domain.SyncIdGenerator;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncFailureRecorder {

    private final SyncMapper mapper;
    private final SyncIdGenerator ids;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventAck record(DeviceContext context, EventEnvelope event, RuntimeException failure) {
        InboxRecord inbox = mapper.findInbox(context.tenantId(), event.eventId());
        int nextAttempt = inbox == null ? 1 : inbox.processingAttempts() + 1;
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String failureCode = "PROCESSING_" + failure.getClass().getSimpleName().toUpperCase();
        // 只记录可信身份、事件身份和异常，不记录 payload、凭据或支付敏感字段。
        log.warn("POS同步业务事实处理失败 tenantId={} deviceId={} eventId={} eventType={} failureCode={}",
            context.tenantId(), context.deviceId(), event.eventId(), event.eventType(), failureCode, failure);
        if (nextAttempt >= SyncRules.MAX_PROCESSING_ATTEMPTS) {
            mapper.updateInboxResult(context.tenantId(), event.eventId(), "DEAD_LETTER", "RETRY_BUDGET_EXHAUSTED", now);
            mapper.upsertDeadLetter(ids.next(), context.tenantId(), event.eventId(), "RETRY_BUDGET_EXHAUSTED",
                failureCode, now);
            return new EventAck(event.eventId(), event.payloadHash(), "REJECTED_FINAL",
                "DEAD_LETTER", null);
        }
        mapper.updateInboxResult(context.tenantId(), event.eventId(), "RETRY", failureCode, now);
        long retryAfter = Math.min(60_000L, 500L << Math.min(nextAttempt, 7));
        return new EventAck(event.eventId(), event.payloadHash(), "REJECTED_RETRYABLE", failureCode, retryAfter);
    }
}
