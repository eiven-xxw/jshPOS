package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * 向支付领域提供原单不可变成交快照的只读端口。
 *
 * <p>订单仍是该数据的唯一 Owner；支付模块不得通过本端口修改订单状态或金额。</p>
 */
public interface PaymentOrderSnapshotPort {

    OrderPaymentSnapshot requireSnapshot(String orderId);

    /** 支付和退款校验所需的最小原单快照。 */
    record OrderPaymentSnapshot(String orderId, Long storeId, String terminalId, String status,
                                String paymentStatus, String currency, long receivableAmountMinor,
                                List<LineSnapshot> lines) {
        public OrderPaymentSnapshot {
            lines = List.copyOf(lines);
        }
    }

    /** 原订单行的成交数量和可退金额上限；数量使用精确六位小数。 */
    record LineSnapshot(String lineId, BigDecimal quantity, long payableAmountMinor) {
    }
}
