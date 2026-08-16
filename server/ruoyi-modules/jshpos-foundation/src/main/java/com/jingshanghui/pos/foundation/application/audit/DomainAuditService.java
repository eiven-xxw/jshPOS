package com.jingshanghui.pos.foundation.application.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.AuditEventView;
import com.jingshanghui.pos.foundation.infrastructure.observability.CorrelationId;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.AuditEventEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.AuditEventMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DomainAuditService {

    private final AuditEventMapper auditEventMapper;
    private final TrustedTenantContext tenantContext;
    private final AuditSanitizer sanitizer;

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String action, String targetType, Object targetId,
                       Object before, Object after, Map<String, Object> summary) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        AuditSanitizer.SanitizedPayload beforePayload = sanitizer.sanitize(before);
        AuditSanitizer.SanitizedPayload afterPayload = sanitizer.sanitize(after);
        AuditSanitizer.SanitizedPayload summaryPayload = sanitizer.sanitize(summary);

        AuditEventEntity event = new AuditEventEntity();
        event.setTenantId(principal.tenantId());
        event.setActorUserId(principal.userId());
        event.setActorName(principal.username() == null ? "unknown" : principal.username());
        event.setCorrelationId(CorrelationId.current());
        event.setActionCode(action);
        event.setTargetType(targetType);
        event.setTargetId(String.valueOf(targetId));
        event.setResult("SUCCESS");
        event.setBeforeSha256(before == null ? null : beforePayload.sha256());
        event.setAfterSha256(after == null ? null : afterPayload.sha256());
        event.setSummaryJson(summary == null ? null : summaryPayload.json());
        event.setOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        auditEventMapper.insert(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> list(Instant occurredBefore, int limit) {
        tenantContext.requirePrincipal();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        LambdaQueryWrapper<AuditEventEntity> query = new LambdaQueryWrapper<AuditEventEntity>()
            .lt(occurredBefore != null, AuditEventEntity::getOccurredAt,
                occurredBefore == null ? null : LocalDateTime.ofInstant(occurredBefore, ZoneOffset.UTC))
            .orderByDesc(AuditEventEntity::getOccurredAt)
            .orderByDesc(AuditEventEntity::getAuditId)
            .last("LIMIT " + safeLimit);
        return auditEventMapper.selectList(query).stream().map(this::toView).toList();
    }

    @SuppressWarnings("unchecked")
    private AuditEventView toView(AuditEventEntity entity) {
        Map<String, Object> summary = entity.getSummaryJson() == null
            ? Collections.emptyMap()
            : sanitizer.parseMap(entity.getSummaryJson());
        return new AuditEventView(
            entity.getAuditId(), entity.getCorrelationId(), entity.getActionCode(),
            entity.getTargetType(), entity.getTargetId(), entity.getResult(),
            entity.getOccurredAt().toInstant(ZoneOffset.UTC), entity.getBeforeSha256(),
            entity.getAfterSha256(), summary
        );
    }
}
