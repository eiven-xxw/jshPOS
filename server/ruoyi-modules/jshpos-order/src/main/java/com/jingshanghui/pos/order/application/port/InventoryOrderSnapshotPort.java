package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 向库存领域提供已成交订单的最小只读快照。
 *
 * <p>订单仍是唯一 Owner；库存调用方不得通过本端口修改订单或自报行数量。</p>
 */
public interface InventoryOrderSnapshotPort {

    InventoryOrderSnapshot requireSnapshot(String orderId);

    /** 销售出库与退货交叉校验使用的不可变订单快照。 */
    record InventoryOrderSnapshot(String orderId, Long storeId, String status, String paymentStatus,
                                  LocalDate businessDate, List<InventoryLineSnapshot> lines) {
        public InventoryOrderSnapshot {
            lines = List.copyOf(lines);
        }
    }

    /** 数量为成交基础单位精确值，不允许浮点换算。 */
    record InventoryLineSnapshot(String orderLineId, Long skuId, Long unitId, BigDecimal quantity) {
    }
}
