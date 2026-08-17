package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.DifferenceTransition;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.DifferenceView;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.infrastructure.id.ReportingIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportingDifferenceServiceTest {
    @Test void recordsListsAndTransitionsOnlyReportingDifference() {
        ReportingPersistencePort persistence=mock(ReportingPersistencePort.class);
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        ScopeAuthorizationService auth=mock(ScopeAuthorizationService.class);
        DomainAuditService audit=mock(DomainAuditService.class);
        ReportingIdGenerator ids=mock(ReportingIdGenerator.class);
        Instant now=Instant.parse("2026-08-17T03:00:00Z");
        when(context.requireTenantId()).thenReturn("tenant_alpha");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("tenant_alpha",7L,1L,"synthetic"));
        when(ids.next()).thenReturn("01ARZ3NDEKTSV4RRFFQ69G5FAV");
        var service=new ReportingDifferenceService(persistence,context,auth,audit,ids,Clock.fixed(now,ZoneOffset.UTC));
        assertThat(service.record("SEQUENCE_GAP","01ARZ3NDEKTSV4RRFFQ69G5FAW","detail").state()).isEqualTo("OPEN");
        DifferenceView open=new DifferenceView("01ARZ3NDEKTSV4RRFFQ69G5FAV","SEQUENCE_GAP",
            "01ARZ3NDEKTSV4RRFFQ69G5FAW","OPEN","a".repeat(64),null,now,0);
        when(persistence.listDifferences("tenant_alpha",200)).thenReturn(List.of(open));
        assertThat(service.list(999)).containsExactly(open);
        DifferenceView acknowledged=new DifferenceView(open.differenceId(),open.differenceType(),open.sourceEventId(),
            "ACKNOWLEDGED",open.detailSha256(),7L,now,1);
        when(persistence.findDifference("tenant_alpha",open.differenceId())).thenReturn(open,acknowledged);
        when(persistence.transitionDifference(eq("tenant_alpha"),eq(open.differenceId()),eq("OPEN"),
            eq("ACKNOWLEDGED"),eq(7L),anyString(),eq(0),eq(now))).thenReturn(1);
        assertThat(service.transition(new DifferenceTransition(open.differenceId(),"ACKNOWLEDGED","reviewed",0,
            "01ARZ3NDEKTSV4RRFFQ69G5FAX")).state()).isEqualTo("ACKNOWLEDGED");
        verify(auth).requireTenantAdministrator();
    }
}
