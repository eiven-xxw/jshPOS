package com.jingshanghui.pos.payment.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Gate 3A 应用命令；tenant_id 和 actor 永远不由这些客户端对象提供。 */
public final class PaymentCommands {

    private PaymentCommands() {
    }

    /** 从权威订单快照创建一个且仅一个支付意图。 */
    public record CreateIntent(String commandId, String idempotencyKey, String paymentId, String orderId,
                               Long storeId, String terminalId, long amountMinor, String currency,
                               Instant occurredAt) {
    }

    /** 创建稳定 Provider 请求身份，但 Gate 3A 不执行任何网络调用。 */
    public record CreateAttempt(String commandId, String idempotencyKey, String attemptId, String paymentId,
                                String providerCode, String providerRequestNo, Instant occurredAt) {
    }

    /** 未来受信适配器完成鉴权/验签后传给核心的支付观察。 */
    public record PaymentObservation(String observationId, String paymentId, String attemptId, String source,
                                     String observedStatus, long amountMinor, String currency,
                                     String providerCode, String providerRequestNo,
                                     String providerTransactionNo, Instant observedAt, String payloadHash) {
    }

    /** 原单退款申请；行金额合计必须等于退款总额。 */
    public record CreateRefund(String commandId, String idempotencyKey, String refundId, String paymentId,
                               String orderId, long amountMinor, String currency, String reasonCode,
                               List<RefundLine> lines, Instant occurredAt) {
        public CreateRefund {
            lines = List.copyOf(lines);
        }
    }

    /** 原订单行退款数量与成交金额分摊。 */
    public record RefundLine(String orderLineId, String quantity, long amountMinor) {
    }

    /** 独立审批人把退款从待审批推进到处理态；仍不执行 Provider 网络调用。 */
    public record ApproveRefund(String commandId, String refundId, String reasonCode, Instant occurredAt) {
    }

    /** 原退款请求的可信观察，UNKNOWN 不得通过创建新 refundId 处理。 */
    public record RefundObservation(String observationId, String refundId, String source, String observedStatus,
                                    long amountMinor, String currency, String providerCode,
                                    String providerRequestNo, String providerRefundNo,
                                    Instant observedAt, String payloadHash) {
    }

    /** 受控账单来源行；Gate 3A 只允许合成数据经内部测试/管理入口提供。 */
    public record StatementEntry(String entryId, String providerTransactionNo, String businessType,
                                 String status, long amountMinor, String currency,
                                 Instant occurredAt, String payloadHash) {
    }

    /** 对一个 Provider/账单日执行最多一万行的确定性差异匹配。 */
    public record RunReconciliation(String commandId, String idempotencyKey, String runId,
                                    String providerCode, LocalDate statementDate,
                                    List<StatementEntry> entries, Instant occurredAt) {
        public RunReconciliation {
            entries = List.copyOf(entries);
        }
    }

    /** 对账案例迁移命令，不允许直接修改支付或退款资金事实。 */
    public record TransitionCase(String commandId, String caseId, String targetStatus,
                                 String reasonCode, String reasonText, Instant occurredAt) {
    }
}
