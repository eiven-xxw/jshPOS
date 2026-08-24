package com.jingshanghui.pos.service.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.service.application.model.ServiceModels.*;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort.StagedAttachment;
import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort.StoreObject;
import com.jingshanghui.pos.service.application.port.ServiceEntitlementReadPort;
import com.jingshanghui.pos.service.application.port.ServiceEntitlementReadPort.AccessDecision;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort;
import com.jingshanghui.pos.service.application.port.ServicePersistencePort.*;
import com.jingshanghui.pos.service.domain.ServiceIdGenerator;
import com.jingshanghui.pos.service.domain.ServiceRules;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** T2-SVC-001 应用编排、可信授权、幂等、附件命名空间与事件信封回归。 */
@ExtendWith(MockitoExtension.class)
class ServiceApplicationServiceTest {
    private static final String TENANT = "TENANT_A";
    private static final String TICKET = "01K00000000000000000000000";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 24, 0, 0);
    @Mock private TrustedTenantContext tenantContext;
    @Mock private ScopeAuthorizationService authorization;
    @Mock private ServiceEntitlementReadPort entitlements;
    @Mock private ServicePersistencePort persistence;
    @Mock private ServiceAttachmentStoragePort storage;
    @Mock private ServiceIdGenerator ids;
    private ServiceApplicationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);
        service = new ServiceApplicationService(tenantContext, authorization, entitlements, persistence, storage, ids, clock);
    }

    @Test
    void shouldCreateTicketAndPersistCompleteEventEnvelope() {
        allow();
        when(tenantContext.requirePrincipal()).thenReturn(principal());
        when(ids.next()).thenReturn(TICKET, "01K00000000000000000000002", "01K00000000000000000000003",
            "01K00000000000000000000004", "01K00000000000000000000005");
        when(persistence.findTicket(TENANT, TICKET)).thenReturn(ticket(TICKET));
        when(persistence.listAttachments(TENANT, TICKET)).thenReturn(List.of());

        TicketDetail result = service.createTicket(new CreateTicket(1001L, null, "IMPLEMENTATION_SUPPORT", "P2",
            "内部实施支持", "不含真实数据", 240, "ticket-create-001", "corr-ticket-001"));

        assertAll(
            () -> assertEquals(TICKET, result.ticket().ticketId()),
            () -> assertEquals("OPEN", result.ticket().state()),
            () -> assertFalse(result.overdue())
        );
        verify(authorization).requireStoreAccess(1001L);
        verify(persistence).insertTicket(argThat(value -> TENANT.equals(value.tenantId())
            && value.targetAt().equals(NOW.plusMinutes(240)) && "OPEN".equals(value.state())));
        ArgumentCaptor<OutboxWrite> event = ArgumentCaptor.forClass(OutboxWrite.class);
        verify(persistence).appendOutbox(event.capture());
        assertAll(
            () -> assertEquals("01K00000000000000000000004", event.getValue().eventId()),
            () -> assertEquals("service.ticket-created.v1", event.getValue().eventType()),
            () -> assertTrue(event.getValue().payloadJson().contains("\"tenantId\":\"TENANT_A\"")),
            () -> assertTrue(event.getValue().payloadJson().contains("\"eventId\":\"01K00000000000000000000004\"")),
            () -> assertTrue(event.getValue().payloadJson().contains("\"correlationId\":\"corr-ticket-001\""))
        );
    }

    @Test
    void shouldFailClosedWhenSaasOrSubscriptionDeniesFeature() {
        when(entitlements.decide("SERVICE_OPERATIONS")).thenReturn(new AccessDecision(false, "SUBSCRIPTION_ACCESS_DENIED"));
        ServiceException error = assertThrows(ServiceException.class, () -> service.listTickets(1001L, null, 20));
        assertTrue(error.getMessage().contains("SVC-ACCESS-001"));
        verifyNoInteractions(persistence);
    }

    @Test
    void shouldRejectSameIdempotencyKeyWithDifferentContent() {
        allow();
        when(tenantContext.requirePrincipal()).thenReturn(principal());
        when(persistence.findCommand(eq(TENANT), eq("CREATE_TICKET"), eq("ticket-create-001")))
            .thenReturn(new CommandRecord("01K00000000000000000000006", TENANT, "CREATE_TICKET", "ticket-create-001",
                "f".repeat(64), "TICKET", TICKET, "OPEN", NOW));
        ServiceException error = assertThrows(ServiceException.class, () -> service.createTicket(new CreateTicket(1001L,
            null, "IMPLEMENTATION_SUPPORT", "P2", "内部实施支持", "不含真实数据", 240,
            "ticket-create-001", "corr-ticket-001")));
        assertTrue(error.getMessage().contains("SVC-IDEM-001"));
        verify(persistence, never()).insertTicket(any());
    }

    @Test
    void shouldStoreAttachmentBodyOnlyInTrustedObjectNamespace() {
        allow();
        when(tenantContext.requireTenantId()).thenReturn(TENANT);
        when(tenantContext.requirePrincipal()).thenReturn(principal());
        byte[] content = "synthetic evidence".getBytes();
        StagedAttachment staged = mock(StagedAttachment.class);
        when(staged.sizeBytes()).thenReturn((long) content.length);
        when(staged.sha256()).thenReturn("57dc4b48ff8837ebae5d17633ab6f39d31135f34be1f073f5a9d1cafb24271a0");
        when(storage.stage(any(), eq((long) content.length), eq(ServiceRules.MAX_ATTACHMENT_BYTES))).thenReturn(staged);
        String attachment = "01K00000000000000000000010";
        when(ids.next()).thenReturn(attachment, "01K00000000000000000000011", "01K00000000000000000000012", "01K00000000000000000000013");
        when(persistence.lockTicket(TENANT, TICKET)).thenReturn(ticket(TICKET));
        when(persistence.findAttachment(TENANT, TICKET, attachment)).thenReturn(new AttachmentStoredRecord(attachment,
            TENANT, 1001L, TICKET, "service/TENANT_A/tickets/" + TICKET + "/attachments/" + attachment,
            "evidence.txt", "text/plain", (long) content.length,
            "57dc4b48ff8837ebae5d17633ab6f39d31135f34be1f073f5a9d1cafb24271a0", "STORED", NOW.plusYears(1), NOW));

        AttachmentRecord result = service.uploadAttachment(TICKET, "evidence.txt", "text/plain", content.length,
            new ByteArrayInputStream(content),
            "attachment-upload-001", "corr-attachment-001");

        ArgumentCaptor<StoreObject> stored = ArgumentCaptor.forClass(StoreObject.class);
        verify(storage).store(stored.capture());
        assertAll(
            () -> assertEquals(attachment, result.attachmentId()),
            () -> assertEquals("service/TENANT_A/tickets/" + TICKET + "/attachments/" + attachment, stored.getValue().objectKey()),
            () -> assertSame(staged, stored.getValue().content()),
            () -> assertFalse(stored.getValue().objectKey().contains("operator"))
        );
        verify(persistence).insertAttachment(argThat(value -> value.objectKey().equals(stored.getValue().objectKey())
            && value.sizeBytes() == content.length && value.retentionUntil().equals(NOW.plusYears(1))));
        verify(staged).close();
    }

    private TicketRecord ticket(String id) {
        return new TicketRecord(id, TENANT, 1001L, null, "IMPLEMENTATION_SUPPORT", "P2", "内部实施支持",
            "不含真实数据", "OPEN", null, null, null, null, null, NOW.plusMinutes(240), 0, "a".repeat(64));
    }

    private void allow() {
        when(entitlements.decide("SERVICE_OPERATIONS")).thenReturn(new AccessDecision(true, "ALLOWED"));
    }

    private TrustedPrincipal principal() {
        return new TrustedPrincipal(TENANT, 100L, 10L, "operator");
    }
}
