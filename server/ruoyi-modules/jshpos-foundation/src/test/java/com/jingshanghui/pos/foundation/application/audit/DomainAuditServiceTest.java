package com.jingshanghui.pos.foundation.application.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.AuditEventEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.AuditEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DomainAuditServiceTest {

    private final AuditEventMapper mapper = mock(AuditEventMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final DomainAuditService service = new DomainAuditService(
        mapper, context, new AuditSanitizer(new ObjectMapper().findAndRegisterModules()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void appendsSanitizedTenantScopedEventWithCorrelationId() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 10L, 20L, null));
        MDC.put("correlationId", "corr-gate0-001");

        service.append("STORE_UPDATED", "STORE", 101L,
            Map.of("password", "before-secret"), Map.of("name", "Synthetic"),
            Map.of("phone", "13800000000"));

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(mapper).insert(captor.capture());
        AuditEventEntity event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo("TENANT_A");
        assertThat(event.getActorName()).isEqualTo("unknown");
        assertThat(event.getCorrelationId()).isEqualTo("corr-gate0-001");
        assertThat(event.getBeforeSha256()).matches("[a-f0-9]{64}");
        assertThat(event.getAfterSha256()).matches("[a-f0-9]{64}");
        assertThat(event.getSummaryJson()).doesNotContain("13800000000").contains("***");
        assertThat(event.getOccurredAt()).isNotNull();
    }

    @Test
    void appendsNullPayloadsAndUsesInternalCorrelationFallback() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_B", 11L, null, "bob"));

        service.append("ORG_CREATED", "ORG_UNIT", 201L, null, null, null);

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(mapper).insert(captor.capture());
        AuditEventEntity event = captor.getValue();
        assertThat(event.getActorName()).isEqualTo("bob");
        assertThat(event.getCorrelationId()).isEqualTo("internal-no-http-context");
        assertThat(event.getBeforeSha256()).isNull();
        assertThat(event.getAfterSha256()).isNull();
        assertThat(event.getSummaryJson()).isNull();
    }

    @Test
    void listsAuditEventsWithSafeCursorAndLimit() {
        AuditEventEntity entity = new AuditEventEntity();
        entity.setAuditId(99L);
        entity.setCorrelationId("corr-99");
        entity.setActionCode("CONFIG_VERSION_PUBLISHED");
        entity.setTargetType("CONFIG_VERSION");
        entity.setTargetId("1001");
        entity.setResult("SUCCESS");
        entity.setOccurredAt(LocalDateTime.parse("2026-08-16T00:00:00"));
        entity.setBeforeSha256("a".repeat(64));
        entity.setAfterSha256("b".repeat(64));
        entity.setSummaryJson("{\"version\":1}");
        when(mapper.selectList(any())).thenReturn(List.of(entity));

        var result = service.list(Instant.parse("2026-08-17T00:00:00Z"), 1000);

        assertThat(result).singleElement().satisfies(view -> {
            assertThat(view.auditId()).isEqualTo(99L);
            assertThat(view.summary()).containsEntry("version", 1);
        });
        verify(context).requirePrincipal();
    }
}
