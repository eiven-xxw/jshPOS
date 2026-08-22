package com.jingshanghui.pos.order.application.port;

import java.time.Instant;

/** Payment Owner 仅在全部权威份额成功后调用的 Order Owner 完成端口。 */
public interface TenderOrderSettlementPort {

    /**
     * 追加订单结清事实。
     *
     * @param command Payment Owner 已确认全部份额成功后构造的冻结命令
     * @return Order Owner 的权威结清回执
     */
    OrderSettlementReceipt complete(OrderSettlementCommand command);

    /**
     * 所有上下文均来自冻结计划和 Order Owner 快照，不接受客户端租户或成功声明。
     *
     * @param planId 冻结支付计划 ULID
     * @param orderId 原订单 ULID
     * @param storeId 订单权威门店主键
     * @param terminalId 订单权威终端 ULID
     * @param orderSnapshotSha256 原订单冻结摘要
     * @param planContentSha256 支付计划冻结摘要
     * @param receivableAmountMinor 应收金额，单位分
     * @param currency 币种，商业 V1 固定 CNY
     * @param correlationId 跨 Owner 关联 ULID
     * @param occurredAt 权威完成时间
     */
    record OrderSettlementCommand(String planId, String orderId, Long storeId, String terminalId,
                                  String orderSnapshotSha256, String planContentSha256,
                                  long receivableAmountMinor, String currency,
                                  String correlationId, Instant occurredAt) {
    }

    /**
     * @param orderId 原订单 ULID
     * @param status 结清后的订单状态
     * @param paymentStatus 结清后的 Provider 无关支付状态
     * @param receivedAmountMinor 权威实收金额，单位分
     * @param recordVersion 订单记录版本
     * @param duplicate 是否返回原幂等结果
     */
    record OrderSettlementReceipt(String orderId, String status, String paymentStatus,
                                  long receivedAmountMinor, long recordVersion, boolean duplicate) {
    }
}
