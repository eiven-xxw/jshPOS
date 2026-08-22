package com.jingshanghui.pos.order.application.port;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Payment Owner 收取最后一个现金份额时调用的 Order/Shift Owner 端口。
 *
 * <p>实现必须在调用方事务内追加现金事实、班次流水、审计与 Outbox；Payment Mapper
 * 不得越权写入订单或班次私有表。</p>
 */
public interface TenderCashCollectionPort {

    CashTenderReceipt collect(CashTenderCommand command);

    /**
     * @param planId 冻结支付计划 ULID
     * @param allocationId 现金份额 ULID，也是 Owner 幂等自然键
     * @param orderId 原待支付订单 ULID
     * @param storeId 由订单权威快照取得的门店主键
     * @param terminalId 由订单权威快照取得的终端 ULID
     * @param shiftId 冻结订单关联的班次 ULID
     * @param businessDate 冻结业务日
     * @param amountMinor 应用到订单的现金份额，单位分
     * @param tenderedMinor 顾客实际交付金额，单位分
     * @param requestSha256 份额收取规范请求摘要
     * @param correlationId 跨 Owner 关联 ULID
     * @param occurredAt 收取时间
     */
    record CashTenderCommand(String planId, String allocationId, String orderId, Long storeId,
                             String terminalId, String shiftId, LocalDate businessDate,
                             long amountMinor, long tenderedMinor, String requestSha256,
                             String correlationId, Instant occurredAt) {
    }

    /**
     * 只追加现金事实回执；找零不进入班次净现金。
     *
     * @param cashTenderId Order Owner 现金事实 ULID
     * @param allocationId 原冻结份额 ULID
     * @param amountMinor 计入订单和班次的现金金额，单位分
     * @param tenderedMinor 顾客实际交付金额，单位分
     * @param changeMinor 找零金额，单位分
     * @param duplicate 是否返回原幂等结果
     */
    record CashTenderReceipt(String cashTenderId, String allocationId, long amountMinor,
                             long tenderedMinor, long changeMinor, boolean duplicate) {
    }
}
