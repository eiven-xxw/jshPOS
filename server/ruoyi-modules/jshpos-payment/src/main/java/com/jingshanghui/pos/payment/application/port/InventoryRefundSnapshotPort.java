package com.jingshanghui.pos.payment.application.port;

import java.math.BigDecimal;
import java.util.List;

/**
 * 向库存领域提供原单退款结果的最小只读快照。
 *
 * <p>退款状态与数量仍由支付 Owner 管理；库存只消费已确认成功事实。</p>
 */
public interface InventoryRefundSnapshotPort {

    InventoryRefundSnapshot requireSnapshot(String refundId);

    record InventoryRefundSnapshot(String refundId, String orderId, Long storeId, String status,
                                   List<InventoryRefundLine> lines) {
        public InventoryRefundSnapshot {
            lines = List.copyOf(lines);
        }
    }

    /** 退款冻结的原订单行和退货数量。 */
    record InventoryRefundLine(String orderLineId, BigDecimal quantity) {
    }
}
