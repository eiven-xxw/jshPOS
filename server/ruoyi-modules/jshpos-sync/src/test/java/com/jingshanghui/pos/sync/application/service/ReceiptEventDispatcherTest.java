package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.application.port.ReceiptSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.domain.SyncHash;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ReceiptEventDispatcherTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ReceiptSubmissionPort port = mock(ReceiptSubmissionPort.class);
    private final ReceiptEventDispatcher dispatcher = new ReceiptEventDispatcher(port, json);
    private final DeviceContext context = new DeviceContext("TENANT_A", id(11), 1101L, id(11), 101L, "1.0");

    @Test
    void verifiesSemanticHashAndTrustedContextBeforeFreezing() throws Exception {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("schemaVersion", 1);
        semantic.put("orderId", id(31));
        String digest = sha(json.writeValueAsString(semantic));
        Map<String, Object> payload = base();
        payload.put("documentId", id(61));
        payload.put("printJobId", id(62));
        payload.put("orderId", id(31));
        payload.put("documentType", "SALE_RECEIPT");
        payload.put("templateVersion", "CONVENIENCE.1");
        payload.put("templateSchemaVersion", 1);
        payload.put("contentSha256", digest);
        payload.put("semanticPayload", semantic);
        payload.put("orderAggregateVersion", 4);
        payload.put("executionStatus", "BLOCKED_EXTERNAL");
        EventEnvelope event = event("receipt.document-frozen.v1", id(61), payload);

        dispatcher.apply(context, event);

        verify(port).freeze(anyString(), anyString(), anyString(), anyString(), anyLong(), anyString(),
            anyLong(), anyString(), anyString(), anyInt(), anyString(), anyString(), anyLong(), any());
        payload.put("contentSha256", "f".repeat(64));
        assertThatThrownBy(() -> dispatcher.apply(context,
            event("receipt.document-frozen.v1", id(61), payload)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("HASH_MISMATCH");
    }

    @Test
    void rejectsCrossStoreReprintAndNeverUpgradesExternalStatus() {
        Map<String, Object> payload = base();
        payload.put("storeId", "2101");
        payload.put("printRequestId", id(71));
        payload.put("printJobId", id(62));
        payload.put("documentId", id(61));
        payload.put("orderId", id(31));
        payload.put("requestKind", "REPRINT");
        payload.put("reprintNo", 1);
        payload.put("authorizationRef", "session:synthetic:0001");
        payload.put("reasonCode", "CUSTOMER_COPY");
        payload.put("reasonText", "虚构补打");
        payload.put("requestSha256", "a".repeat(64));
        payload.put("documentSha256", "b".repeat(64));
        payload.put("executionStatus", "BLOCKED_EXTERNAL");
        payload.put("requestedAt", "2026-08-21T08:00:00Z");

        assertThatThrownBy(() -> dispatcher.apply(context,
            event("receipt.reprint-requested.v1", id(71), payload)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CONTEXT_MISMATCH");
    }

    private Map<String, Object> base() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("storeId", "1101");
        payload.put("terminalId", id(11));
        payload.put("cashierId", "101");
        return payload;
    }

    private EventEnvelope event(String type, String aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(id(81), "order.command", type, 1, aggregateId, 1,
            id(11), "1101", id(11), 1, Instant.parse("2026-08-21T08:00:00Z"),
            aggregateId, id(91), SyncHash.payload(json, payload), payload);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String id(int suffix) {
        return "01K2A0000000000000000000" + String.format("%02d", suffix);
    }
}
