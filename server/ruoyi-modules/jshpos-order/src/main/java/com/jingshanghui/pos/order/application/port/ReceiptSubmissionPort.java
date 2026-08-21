package com.jingshanghui.pos.order.application.port;

import java.time.LocalDateTime;

/** POS 收据同步端口；可信租户、门店、终端和员工由服务端上下文注入。 */
public interface ReceiptSubmissionPort {
    void freeze(String sourceEventId, String documentId, String printJobId, String orderId,
                Long storeId, String terminalId, Long cashierId,
                String documentType, String templateVersion, int templateSchemaVersion,
                String semanticPayloadJson, String contentSha256, long orderAggregateVersion,
                LocalDateTime occurredAt);

    void requestReprint(String sourceEventId, String printRequestId, String printJobId,
                        String documentId, String orderId, int reprintNo, String authorizationRef,
                        Long storeId, String terminalId, Long cashierId,
                        String reasonCode, String reasonText, String requestSha256,
                        String documentSha256, LocalDateTime occurredAt);
}
