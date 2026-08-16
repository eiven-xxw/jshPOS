package com.jingshanghui.pos.payment.application.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付模块的查询投影和幂等命令结果。 */
public final class PaymentViews {

    private PaymentViews() {
    }

    /** 支付意图当前投影；金额为最小货币单位整数。 */
    public record PaymentView(String paymentId, String orderId, Long storeId, String terminalId,
                              String status, long amountMinor, String currency, long succeededRefundMinor,
                              long recordVersion, LocalDateTime occurredAt) {
    }

    /** 单次稳定 Provider 请求投影。 */
    public record AttemptView(String attemptId, String paymentId, String providerCode,
                              String providerRequestNo, String providerTransactionNo,
                              String status, long amountMinor, String currency, long recordVersion) {
    }

    /** 已持久化 Provider 观察的幂等证据。 */
    public record ObservationView(String observationId, String aggregateType, String aggregateId,
                                  String payloadSha256, String mergeResult) {
    }

    /** 支付模块命令幂等结果。 */
    public record IdempotencyView(String commandType, String requestSha256, String aggregateId,
                                  String resultJson) {
    }

    /** 原单退款投影；UNKNOWN 和 PROCESSING 继续占用额度。 */
    public record RefundView(String refundId, String paymentId, String orderId, Long storeId,
                             String status, long amountMinor, String currency, Long requesterUserId,
                             Long approverUserId, String providerCode, String providerRequestNo,
                             String providerRefundNo, long recordVersion) {
    }

    /** 某原订单行已经被占用的退款数量。 */
    public record ReservedQuantityView(String orderLineId, BigDecimal reservedQuantity) {
    }

    /** 单次退款申请的不可变原单行占额。 */
    public record RefundLineView(String orderLineId, BigDecimal quantity, long amountMinor) {
    }

    /** 对账使用的内部不可变支付或退款事实。 */
    public record InternalFactView(String reference, String aggregateId, String businessType,
                                   String status, long amountMinor, String currency,
                                   LocalDateTime occurredAt) {
    }

    /** 对账差异案例投影。 */
    public record ReconciliationCaseView(String caseId, String runId, String differenceType,
                                         String internalReference, String providerReference,
                                         String status, Long resolverUserId, Long approverUserId,
                                         long recordVersion) {
    }

    public record PaymentResult(String paymentId, String status, long amountMinor, String currency,
                                long recordVersion, boolean duplicate) {
    }

    public record AttemptResult(String attemptId, String paymentId, String status,
                                String providerCode, String providerRequestNo, boolean duplicate) {
    }

    public record ObservationResult(String aggregateId, String beforeStatus, String afterStatus,
                                    String outcome, boolean duplicate) {
    }

    public record RefundResult(String refundId, String paymentId, String status, long amountMinor,
                               String currency, long recordVersion, boolean duplicate) {
    }

    public record ReconciliationResult(String runId, int statementEntries, int casesOpened,
                                       boolean duplicate) {
    }
}
