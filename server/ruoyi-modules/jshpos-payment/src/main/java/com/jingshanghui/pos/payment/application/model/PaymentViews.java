package com.jingshanghui.pos.payment.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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

    /**
     * 组合支付计划投影；只包含 Payment Owner 的冻结事实和状态。
     * contentSha256 绑定订单快照、可信门店/终端/班次、业务日和所有份额。
     */
    public record TenderPlanView(String planId, String orderId, String orderSnapshotSha256,
                                 Long storeId, String terminalId, String shiftId, LocalDate businessDate,
                                 String status, long receivableAmountMinor, long succeededAmountMinor,
                                 long occupiedAmountMinor, String currency, int allocationCount,
                                 String contentSha256, String correlationId, long recordVersion,
                                 LocalDateTime frozenAt) {
    }

    /**
     * 单个支付份额投影；ownerFactId 只引用原 Owner 的权威资金事实。
     * allocationSha256、collectionCommandId 和 requestSha256 用于拒绝同键异内容。
     */
    public record TenderAllocationView(String allocationId, String planId, int sequenceNo,
                                       String tenderType, String status, long amountMinor, String currency,
                                       String allocationSha256, String ownerFactId, String observationRef,
                                       String collectionCommandId, String collectionRequestSha256,
                                       long recordVersion) {
    }

    /** 面向 REST/POS 的完整计划查询结果，份额顺序与冻结计划一致。 */
    public record TenderPlanResult(TenderPlanView plan, List<TenderAllocationView> allocations,
                                   boolean duplicate) {
        public TenderPlanResult {
            allocations = List.copyOf(allocations);
        }
    }

    /**
     * 份额收取结果；BLOCKED_EXTERNAL 是可审计的失败关闭，不是资金成功。
     * tenderedMinor/changeMinor 仅对现金份额有意义。
     */
    public record TenderCollectResult(String planId, String allocationId, String tenderType,
                                      String allocationStatus, String planStatus, long amountMinor,
                                      Long tenderedMinor, Long changeMinor, String ownerFactId,
                                      String outcome, boolean duplicate) {
    }
}
