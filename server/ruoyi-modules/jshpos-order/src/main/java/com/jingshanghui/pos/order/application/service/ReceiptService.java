package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.port.ReceiptSubmissionPort;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** 收据文档与补打事实唯一写入服务；物理打印由仍处于阻断状态的设备边界负责。 */
@Service
@RequiredArgsConstructor
public class ReceiptService implements ReceiptSubmissionPort {
    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public void freeze(String sourceEventId, String documentId, String printJobId, String orderId,
                       Long storeId, String terminalId, Long cashierId,
                       String documentType, String templateVersion, int templateSchemaVersion,
                       String semanticPayloadJson, String contentSha256, long orderAggregateVersion,
                       LocalDateTime occurredAt) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireIdentifiers(sourceEventId, documentId, printJobId, orderId, terminalId);
        String serverPrintJobId = mapper.findPrintJobId(principal.tenantId(), orderId);
        if (!"SALE_RECEIPT".equals(documentType) || templateSchemaVersion != 1
            || orderAggregateVersion < 1 || !isHash(contentSha256)
            || templateVersion == null || templateVersion.isBlank() || templateVersion.length() > 32
            || semanticPayloadJson == null
            || semanticPayloadJson.getBytes(StandardCharsets.UTF_8).length > 1_048_576
            || !contentSha256.equals(sha256(semanticPayloadJson))
            || occurredAt == null
            || !principal.userId().equals(cashierId) || storeId == null || terminalId == null
            || !printJobId.equals(serverPrintJobId)) {
            throw new ServiceException("RECEIPT_SOURCE_INVALID: 收据来源、版本或摘要无效", 409);
        }
        OrderView order = requireOrderScope(principal, orderId, storeId, terminalId, cashierId, true);
        requireSemanticDocument(order, documentType, templateVersion, templateSchemaVersion,
            semanticPayloadJson, orderAggregateVersion);
        mapper.insertReceiptDocument(principal.tenantId(), sourceEventId, documentId, orderId,
            storeId, terminalId, cashierId, documentType,
            templateVersion, templateSchemaVersion, semanticPayloadJson, contentSha256,
            orderAggregateVersion, occurredAt);
        mapper.insertAudit(principal.tenantId(), ulids.next(), "RECEIPT_DOCUMENT_FROZEN", "RECEIPT",
            documentId, principal.userId(), null, sourceEventId, sourceEventId, null,
            "BLOCKED_EXTERNAL", null, null, contentSha256, "RECEIPT_FROZEN", occurredAt);
    }

    @Override
    @Transactional
    public void requestReprint(String sourceEventId, String printRequestId, String printJobId,
                               String documentId, String orderId, int reprintNo, String authorizationRef,
                               Long storeId, String terminalId, Long cashierId,
                               String reasonCode, String reasonText, String requestSha256,
                               String documentSha256, LocalDateTime occurredAt) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireIdentifiers(sourceEventId, printRequestId, printJobId, documentId, orderId, terminalId);
        if (reprintNo < 1 || reprintNo > 999 || reasonCode == null || !reasonCode.matches("^[A-Z][A-Z0-9_]{1,31}$")
            || "ORDER_COMPLETED".equals(reasonCode) || reasonText == null || reasonText.isBlank()
            || reasonText.length() > 256 || authorizationRef == null || authorizationRef.length() < 16
            || authorizationRef.length() > 128 || occurredAt == null
            || !isHash(requestSha256) || !isHash(documentSha256)
            || !requestSha256.equals(reprintRequestHash(orderId, reasonCode, reasonText.trim(),
                authorizationRef, cashierId))
            || !principal.userId().equals(cashierId)
            || storeId == null || terminalId == null
            || mapper.countReceiptSource(principal.tenantId(), orderId, documentId, documentSha256) != 1) {
            throw new ServiceException("RECEIPT_REPRINT_INVALID: 补打来源、授权或摘要无效", 409);
        }
        requireOrderScope(principal, orderId, storeId, terminalId, cashierId, false);
        String serverPrintJobId = mapper.findPrintJobId(principal.tenantId(), orderId);
        if (!printJobId.equals(serverPrintJobId)) {
            throw new ServiceException("RECEIPT_REPRINT_INVALID: 原始打印任务身份不匹配", 409);
        }
        mapper.insertPrintRequest(principal.tenantId(), sourceEventId, printRequestId, serverPrintJobId,
            documentId, orderId, storeId, terminalId, cashierId,
            authorizationRef, reprintNo, reasonCode, reasonText, requestSha256, documentSha256,
            occurredAt);
        mapper.insertAudit(principal.tenantId(), ulids.next(), "RECEIPT_REPRINT_REQUESTED", "PRINT_REQUEST",
            printRequestId, principal.userId(), null, sourceEventId, sourceEventId, null,
            "BLOCKED_EXTERNAL", null, null, requestSha256, reasonCode, occurredAt);
    }

    private OrderView requireOrderScope(TrustedPrincipal principal, String orderId, Long storeId,
                                        String terminalId, Long cashierId, boolean requireOriginalCashier) {
        authorizationService.requireStoreAccess(storeId);
        OrderView order = mapper.findOrder(principal.tenantId(), orderId);
        if (order == null || !storeId.equals(order.storeId()) || !terminalId.equals(order.terminalId())
            || (requireOriginalCashier && !cashierId.equals(order.cashierUserId()))
            || !"COMPLETED".equals(order.status())) {
            throw new ServiceException("RECEIPT_CONTEXT_MISMATCH: 收据与原成交可信范围不一致", 403);
        }
        return order;
    }

    /** 服务端按原成交事实复核语义收据，避免客户端用自洽摘要提交伪造内容。 */
    private void requireSemanticDocument(OrderView order, String documentType, String templateVersion,
                                         int templateSchemaVersion, String semanticPayloadJson,
                                         long orderAggregateVersion) {
        try {
            JsonNode root = objectMapper.readTree(semanticPayloadJson);
            JsonNode lines = root == null ? null : root.get("lines");
            if (root == null || !root.isObject() || !linesValid(lines)
                || integral(root, "schemaVersion") != templateSchemaVersion
                || !documentType.equals(text(root, "documentType"))
                || !order.orderId().equals(text(root, "orderId"))
                || !order.localOrderNo().equals(text(root, "localOrderNo"))
                || !order.storeId().toString().equals(text(root, "storeId"))
                || !order.terminalId().equals(text(root, "terminalId"))
                || !order.shiftId().equals(text(root, "shiftId"))
                || !order.cashierUserId().toString().equals(text(root, "cashierId"))
                || !order.businessDate().toString().equals(text(root, "businessDate"))
                || !order.currency().equals(text(root, "currency"))
                || !templateVersion.equals(text(root, "templateVersion"))
                || order.recordVersion() != orderAggregateVersion) {
                throw invalidSemanticReceipt();
            }
            long gross = integral(root, "grossAmountMinor");
            long discount = integral(root, "discountAmountMinor");
            long surcharge = integral(root, "surchargeAmountMinor");
            long receivable = integral(root, "receivableAmountMinor");
            if (gross < 0 || discount < 0 || surcharge < 0 || receivable < 0
                || !BigInteger.valueOf(gross).subtract(BigInteger.valueOf(discount))
                    .add(BigInteger.valueOf(surcharge)).equals(BigInteger.valueOf(receivable))
                || gross != order.grossAmountMinor() || receivable != order.receivableAmountMinor()) {
                throw invalidSemanticReceipt();
            }
            requireLineSums(lines, gross, discount, surcharge, receivable);
        } catch (JsonProcessingException | ArithmeticException exception) {
            throw invalidSemanticReceipt();
        }
    }

    private boolean linesValid(JsonNode lines) {
        return lines != null && lines.isArray() && !lines.isEmpty() && lines.size() <= 500;
    }

    private void requireLineSums(JsonNode lines, long gross, long discount, long surcharge, long receivable) {
        BigInteger grossSum = BigInteger.ZERO;
        BigInteger discountSum = BigInteger.ZERO;
        BigInteger surchargeSum = BigInteger.ZERO;
        BigInteger receivableSum = BigInteger.ZERO;
        Set<Long> lineNumbers = new HashSet<>();
        for (JsonNode line : lines) {
            if (!line.isObject()) throw invalidSemanticReceipt();
            long lineNo = integral(line, "lineNo");
            long lineGross = integral(line, "grossAmountMinor");
            long lineDiscount = integral(line, "discountAmountMinor");
            long lineSurcharge = integral(line, "surchargeAmountMinor");
            long lineReceivable = integral(line, "payableAmountMinor");
            if (lineNo < 1 || lineNo > 500 || !lineNumbers.add(lineNo)
                || lineGross < 0 || lineDiscount < 0 || lineSurcharge < 0 || lineReceivable < 0
                || !BigInteger.valueOf(lineGross).subtract(BigInteger.valueOf(lineDiscount))
                    .add(BigInteger.valueOf(lineSurcharge)).equals(BigInteger.valueOf(lineReceivable))) {
                throw invalidSemanticReceipt();
            }
            grossSum = grossSum.add(BigInteger.valueOf(lineGross));
            discountSum = discountSum.add(BigInteger.valueOf(lineDiscount));
            surchargeSum = surchargeSum.add(BigInteger.valueOf(lineSurcharge));
            receivableSum = receivableSum.add(BigInteger.valueOf(lineReceivable));
        }
        if (!grossSum.equals(BigInteger.valueOf(gross))
            || !discountSum.equals(BigInteger.valueOf(discount))
            || !surchargeSum.equals(BigInteger.valueOf(surcharge))
            || !receivableSum.equals(BigInteger.valueOf(receivable))) {
            throw invalidSemanticReceipt();
        }
    }

    private long integral(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) throw invalidSemanticReceipt();
        return value.bigIntegerValue().longValueExact();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidSemanticReceipt();
        }
        return value.textValue();
    }

    private ServiceException invalidSemanticReceipt() {
        return new ServiceException("RECEIPT_SOURCE_INVALID: 语义收据与原成交事实不一致", 409);
    }

    private String reprintRequestHash(String orderId, String reasonCode, String reasonText,
                                      String authorizationRef, Long cashierId) {
        return sha256(canonical(orderId, reasonCode, reasonText, authorizationRef, cashierId));
    }

    private String canonical(Object... values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text).append(';');
        }
        return result.toString();
    }

    private void requireIdentifiers(String... values) {
        for (String value : values) {
            OrderRules.requireUlid(value, "receiptIdentifier");
        }
    }

    private boolean isHash(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
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
