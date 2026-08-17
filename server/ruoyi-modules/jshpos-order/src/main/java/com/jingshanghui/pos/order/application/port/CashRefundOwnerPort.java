package com.jingshanghui.pos.order.application.port;

import java.time.Instant;
import java.time.LocalDate;

/** Order Owner 对退货编排暴露的现金退款落账端口，不接受 tenant_id。 */
public interface CashRefundOwnerPort {

    CashRefundResult refund(CashRefundCommand command);

    /**
     * @param eventId 稳定现金退款事件ULID
     * @param refundId Return Owner退货退款ULID
     * @param orderId 原订单ULID
     * @param originalCashPaymentId 原现金收款ULID
     * @param refundShiftId 本次实际退款OPEN班次ULID
     * @param storeId 可信门店一致性输入
     * @param terminalId 当前退款终端ULID
     * @param businessDate 本次退款业务日
     * @param amountMinor 退款金额分
     * @param requestSha256 跨Owner请求摘要
     * @param correlationId 关联ULID
     * @param occurredAt 发生时间UTC
     */
    record CashRefundCommand(String eventId, String refundId, String orderId, String originalCashPaymentId,
                             String refundShiftId, Long storeId, String terminalId, LocalDate businessDate,
                             long amountMinor, String requestSha256, String correlationId, Instant occurredAt) { }

    /** 现金退款只追加事实结果。 */
    record CashRefundResult(String cashRefundId, String refundId, long amountMinor,
                            String status, boolean duplicate) { }
}
