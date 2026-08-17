package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 退货退款编排读取 Order Owner 原成交事实的受控只读端口。 */
public interface ReturnOrderSnapshotPort {

    /** 在可信租户内读取已完成订单；不存在、未完成或不可见时失败关闭。 */
    ReturnOrderSnapshot requireSnapshot(String orderId);

    /** 原成交订单、促销绑定与收款身份快照。 */
    record ReturnOrderSnapshot(String orderId, Long storeId, String terminalId, LocalDate businessDate,
                               String status, String paymentStatus, String currency,
                               long grossAmountMinor, long discountAmountMinor, long surchargeAmountMinor,
                               long receivableAmountMinor, String promotionSnapshotId,
                               String promotionSnapshotSha256, String cashPaymentId, List<ReturnOrderLine> lines) {
        public ReturnOrderSnapshot { lines = List.copyOf(lines); }
    }

    /** 原成交行；数量为精确六位小数，金额为最小货币单位分。 */
    record ReturnOrderLine(String lineId, Long skuId, Long unitId, BigDecimal quantity,
                           long grossAmountMinor, long discountAmountMinor,
                           long surchargeAmountMinor, long payableAmountMinor) { }
}
