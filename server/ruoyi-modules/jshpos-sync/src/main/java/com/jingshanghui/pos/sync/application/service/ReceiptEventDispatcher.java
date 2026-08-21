package com.jingshanghui.pos.sync.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.order.application.port.ReceiptSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;

/** 将收据冻结与补打 Outbox 交给 Order Owner；所有身份均与可信设备上下文交叉校验。 */
@Component
@RequiredArgsConstructor
public class ReceiptEventDispatcher {
    private final ReceiptSubmissionPort receipts;
    private final ObjectMapper objectMapper;

    public void apply(DeviceContext context, EventEnvelope event) {
        if ("receipt.document-frozen.v1".equals(event.eventType())) {
            freeze(context, event);
        } else if ("receipt.reprint-requested.v1".equals(event.eventType())) {
            reprint(context, event);
        }
    }

    private void freeze(DeviceContext context, EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        requireContext(context, payload);
        if (!event.aggregateId().equals(text(payload, "documentId"))
            || !"BLOCKED_EXTERNAL".equals(text(payload, "executionStatus"))) {
            throw new ServiceException("RECEIPT_CONTEXT_MISMATCH: 收据身份或外设边界无效", 403);
        }
        String semanticJson = json(payload.get("semanticPayload"));
        String contentHash = text(payload, "contentSha256");
        if (!sha256(semanticJson).equals(contentHash)) {
            throw new ServiceException("RECEIPT_HASH_MISMATCH: 收据语义内容摘要不匹配", 409);
        }
        receipts.freeze(event.eventId(), event.aggregateId(), text(payload, "printJobId"),
            text(payload, "orderId"), context.storeId(), context.terminalId(), context.userId(),
            text(payload, "documentType"), text(payload, "templateVersion"),
            integer(payload, "templateSchemaVersion"), semanticJson, contentHash,
            number(payload, "orderAggregateVersion"),
            LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    private void reprint(DeviceContext context, EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        requireContext(context, payload);
        if (!event.aggregateId().equals(text(payload, "printRequestId"))
            || !"REPRINT".equals(text(payload, "requestKind"))
            || !"BLOCKED_EXTERNAL".equals(text(payload, "executionStatus"))) {
            throw new ServiceException("RECEIPT_CONTEXT_MISMATCH: 补打身份或外设边界无效", 403);
        }
        receipts.requestReprint(event.eventId(), event.aggregateId(), text(payload, "printJobId"),
            text(payload, "documentId"), text(payload, "orderId"), integer(payload, "reprintNo"),
            text(payload, "authorizationRef"), context.storeId(), context.terminalId(), context.userId(),
            text(payload, "reasonCode"), text(payload, "reasonText"),
            text(payload, "requestSha256"), text(payload, "documentSha256"),
            LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    private void requireContext(DeviceContext context, Map<String, Object> payload) {
        if (!context.storeId().toString().equals(text(payload, "storeId"))
            || !context.terminalId().equals(text(payload, "terminalId"))
            || !context.userId().toString().equals(text(payload, "cashierId"))) {
            throw new ServiceException("RECEIPT_CONTEXT_MISMATCH: 客户端试图覆盖可信设备上下文", 403);
        }
    }

    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new ServiceException("RECEIPT_PAYLOAD_INVALID: " + field + " 不能为空", 400);
        }
        return result;
    }

    private long number(Map<String, Object> payload, String field) {
        try {
            return new BigDecimal(String.valueOf(payload.get(field))).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ServiceException("RECEIPT_PAYLOAD_INVALID: " + field + " 必须为整数", 400);
        }
    }

    private int integer(Map<String, Object> payload, String field) {
        return Math.toIntExact(number(payload, field));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("RECEIPT_PAYLOAD_INVALID: semanticPayload 无法规范序列化", 400);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
