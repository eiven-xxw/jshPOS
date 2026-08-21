package com.jingshanghui.pos.order.application.port;

import java.time.LocalDate;

/** ReturnOrchestration Owner 观察新销售权威状态的最小只读端口。 */
public interface ExchangeOrderSnapshotPort {

    /**
     * 在可信租户和门店范围内查找预分配的新订单；尚未同步时返回 null。
     * 客户端提交的成功状态、金额或摘要不得进入此端口。
     */
    ExchangeOrderSnapshot find(String orderId);

    /** 新销售权威头、促销绑定和冻结摘要；不暴露 Order 私有 Mapper。 */
    record ExchangeOrderSnapshot(String orderId, Long storeId, String terminalId, LocalDate businessDate,
                                 String status, String paymentStatus, String currency,
                                 long receivableAmountMinor, String quoteFingerprint,
                                 String settlementFingerprint, String orderSnapshotSha256) { }
}
