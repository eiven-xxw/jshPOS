package com.jingshanghui.pos.payment.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Payment Owner 对 Return Saga 暴露的 Provider 无关退款核心端口；本端口不执行网络调用。 */
public interface ReturnPaymentRefundPort {

    RefundState request(RefundCommand command);

    RefundState find(String refundId);

    /**
     * @param eventId 稳定退款命令ULID
     * @param idempotencyKey 稳定幂等键
     * @param refundId Payment Owner退款ULID
     * @param paymentId 原成功支付ULID
     * @param orderId 原订单ULID
     * @param amountMinor 按促销快照恢复后的退款金额分
     * @param lines 行数量与退款金额
     * @param reasonCode 原因码
     * @param occurredAt 发生时间UTC
     */
    record RefundCommand(String eventId, String idempotencyKey, String refundId, String paymentId,
                         String orderId, long amountMinor, List<RefundLine> lines,
                         String reasonCode, Instant occurredAt) {
        public RefundCommand { lines = List.copyOf(lines); }
    }

    /** 原成交行退款输入。 */
    record RefundLine(String orderLineId, BigDecimal quantity, long amountMinor) { }

    /** Provider 无关退款状态投影。UNKNOWN 只能继续查询或接收可信观察。 */
    record RefundState(String refundId, String paymentId, String status,
                       long amountMinor, String currency, boolean duplicate) { }
}
