package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.sync.domain.SyncRules;
import com.jingshanghui.pos.sync.infrastructure.persistence.mapper.SyncMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class SyncRepairService {

    private final TrustedTenantContext tenantContext;
    private final SyncMapper mapper;
    private final DomainAuditService auditService;

    @Transactional
    public void reopenDeadLetter(String eventId) {
        SyncRules.requireUlid(eventId, "eventId");
        String tenantId = tenantContext.requireTenantId();
        if (mapper.markDeadLetterRetrying(tenantId, eventId) != 1 || mapper.reopenDeadLetter(tenantId, eventId) != 1) {
            throw new ServiceException("SYNC_DEAD_LETTER_NOT_REPAIRABLE: event is not an open dead letter", 409);
        }
        auditService.append("SYNC_DEAD_LETTER_REOPENED", "SYNC_EVENT", eventId, null,
            Map.of("status", "RECEIVED"), Map.of("repairMode", "ORIGINAL_EVENT_RETRY_REQUIRED"));
    }
}
