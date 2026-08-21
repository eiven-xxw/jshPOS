package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReceiptServiceTest {
    private static final String EVENT = "01K2A000000000000000000051";
    private static final String DOCUMENT = "01K2A000000000000000000052";
    private static final String REQUEST = "01K2A000000000000000000053";
    private static final String JOB = "01K2A000000000000000000054";
    private static final String ORDER = "01K2A000000000000000000055";
    private static final String TERMINAL = "01K2A000000000000000000011";
    private static final String REQUEST_HASH =
        "287d422874b4bb260194728477d43726f97f0e97e8e8eeffb14164ec8e78f5d3";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 8, 0);

    private final OrderMapper mapper = mock(OrderMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final ReceiptService service = new ReceiptService(mapper, context, authorization,
        new UlidGenerator(Clock.fixed(Instant.parse("2026-08-21T08:00:00Z"), ZoneOffset.UTC)),
        new ObjectMapper());

    @Test
    void freezesOnlyTheOriginalCompletedOrderInTrustedScopeAndAudits() {
        arrangeTrustedOrder();
        String semantic = semanticReceipt(900);

        service.freeze(EVENT, DOCUMENT, JOB, ORDER, 1101L, TERMINAL, 101L,
            "SALE_RECEIPT", "CONVENIENCE_V1", 1, semantic, sha256(semantic), 3, NOW);

        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertReceiptDocument("TENANT_A", EVENT, DOCUMENT, ORDER, 1101L,
            TERMINAL, 101L, "SALE_RECEIPT", "CONVENIENCE_V1", 1, semantic,
            sha256(semantic), 3, NOW);
        verify(mapper).insertAudit(eq("TENANT_A"), any(), eq("RECEIPT_DOCUMENT_FROZEN"),
            eq("RECEIPT"), eq(DOCUMENT), eq(101L), eq(null), eq(EVENT), eq(EVENT), eq(null),
            eq("BLOCKED_EXTERNAL"), eq(null), eq(null), eq(sha256(semantic)),
            eq("RECEIPT_FROZEN"), eq(NOW));
    }

    @Test
    void rejectsReceiptHashTamperingBeforeAnyFactIsWritten() {
        arrangeTrustedOrder();
        String semantic = semanticReceipt(900);

        assertThatThrownBy(() -> service.freeze(EVENT, DOCUMENT, JOB, ORDER, 1101L, TERMINAL, 101L,
            "SALE_RECEIPT", "CONVENIENCE_V1", 1, semantic, REQUEST_HASH, 3, NOW))
            .hasMessageContaining("RECEIPT_SOURCE_INVALID");

        verify(mapper, never()).insertReceiptDocument(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(Integer.class), any(), any(), any(Long.class), any());
    }

    @Test
    void appendsAuditedReprintButKeepsExecutionBlocked() {
        arrangeTrustedOrder();
        String documentHash = sha256(semanticReceipt(900));
        when(mapper.countReceiptSource("TENANT_A", ORDER, DOCUMENT, documentHash)).thenReturn(1);

        service.requestReprint(EVENT, REQUEST, JOB, DOCUMENT, ORDER, 1, "SESSION_AUTH_REF_123456",
            1101L, TERMINAL, 101L, "CUSTOMER_COPY", "顾客要求补打", REQUEST_HASH,
            documentHash, NOW);

        verify(mapper).insertPrintRequest("TENANT_A", EVENT, REQUEST, JOB, DOCUMENT, ORDER,
            1101L, TERMINAL, 101L, "SESSION_AUTH_REF_123456", 1, "CUSTOMER_COPY",
            "顾客要求补打", REQUEST_HASH, documentHash, NOW);
        verify(mapper).insertAudit(eq("TENANT_A"), any(), eq("RECEIPT_REPRINT_REQUESTED"),
            eq("PRINT_REQUEST"), eq(REQUEST), eq(101L), eq(null), eq(EVENT), eq(EVENT), eq(null),
            eq("BLOCKED_EXTERNAL"), eq(null), eq(null), eq(REQUEST_HASH), eq("CUSTOMER_COPY"), eq(NOW));
    }

    @Test
    void rejectsCrossStoreReceiptEvenInsideTheSameTenant() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "cashier"));
        when(mapper.findPrintJobId("TENANT_A", ORDER)).thenReturn(JOB);
        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(order(2202L));
        String semantic = semanticReceipt(900);

        assertThatThrownBy(() -> service.freeze(EVENT, DOCUMENT, JOB, ORDER, 1101L, TERMINAL, 101L,
            "SALE_RECEIPT", "CONVENIENCE_V1", 1, semantic, sha256(semantic), 3, NOW))
            .hasMessageContaining("RECEIPT_CONTEXT_MISMATCH");

        verify(mapper, never()).insertReceiptDocument(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(Integer.class), any(), any(), any(Long.class), any());
    }

    @Test
    void rejectsSelfConsistentReceiptThatChangesTheAuthoritativeAmount() {
        arrangeTrustedOrder();
        String tampered = semanticReceipt(901);

        assertThatThrownBy(() -> service.freeze(EVENT, DOCUMENT, JOB, ORDER, 1101L, TERMINAL, 101L,
            "SALE_RECEIPT", "CONVENIENCE_V1", 1, tampered, sha256(tampered), 3, NOW))
            .hasMessageContaining("RECEIPT_SOURCE_INVALID");

        verify(mapper, never()).insertReceiptDocument(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(Integer.class), any(), any(), any(Long.class), any());
    }

    @Test
    void rejectsClientPrintJobIdentityThatDoesNotMatchTheOrder() {
        arrangeTrustedOrder();
        String semantic = semanticReceipt(900);
        String anotherJob = "01K2A000000000000000000099";

        assertThatThrownBy(() -> service.freeze(EVENT, DOCUMENT, anotherJob, ORDER, 1101L, TERMINAL, 101L,
            "SALE_RECEIPT", "CONVENIENCE_V1", 1, semantic, sha256(semantic), 3, NOW))
            .hasMessageContaining("RECEIPT_SOURCE_INVALID");

        verify(mapper, never()).insertReceiptDocument(any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(Integer.class), any(), any(), any(Long.class), any());
    }

    private void arrangeTrustedOrder() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "cashier"));
        when(mapper.findPrintJobId("TENANT_A", ORDER)).thenReturn(JOB);
        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(order(1101L));
    }

    private OrderView order(Long storeId) {
        return new OrderView(ORDER, "SYN-001", storeId, TERMINAL,
            "01K2A000000000000000000056", 101L, LocalDate.of(2026, 8, 21), "COMPLETED",
            "PAID", "CNY", 1000, 900, 900, REQUEST_HASH, "{}", 3, NOW);
    }

    private String semanticReceipt(long receivable) {
        return "{\"schemaVersion\":1,\"documentType\":\"SALE_RECEIPT\","
            + "\"orderId\":\"" + ORDER + "\",\"localOrderNo\":\"SYN-001\","
            + "\"storeId\":\"1101\",\"terminalId\":\"" + TERMINAL + "\","
            + "\"shiftId\":\"01K2A000000000000000000056\",\"cashierId\":\"101\","
            + "\"cashierName\":\"虚构收银员\",\"businessDate\":\"2026-08-21\","
            + "\"currency\":\"CNY\",\"templateVersion\":\"CONVENIENCE_V1\","
            + "\"grossAmountMinor\":1000,\"discountAmountMinor\":" + (1000 - receivable) + ","
            + "\"surchargeAmountMinor\":0,\"receivableAmountMinor\":" + receivable + ","
            + "\"lines\":[{\"lineNo\":1,\"grossAmountMinor\":1000,"
            + "\"discountAmountMinor\":" + (1000 - receivable) + ",\"surchargeAmountMinor\":0,"
            + "\"payableAmountMinor\":" + receivable + "}]}";
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
