package com.jingshanghui.pos.inventory.application.port;

import java.math.BigDecimal;

/**
 * Replenishment Owner 使用的库存权威只读端口。
 *
 * <p>调用方不得提交 tenant_id；实现必须从可信上下文解析租户和门店数据范围。</p>
 */
public interface ReplenishmentInventorySnapshotPort {

    /** 读取当前可重建余额和最后库存流水检查点，不锁定或修改库存事实。 */
    InventorySnapshot requireReplenishmentSnapshot(String warehouseId, Long skuId);

    /** 补货计算输入快照；所有数量固定六位小数。 */
    record InventorySnapshot(Long storeId, String warehouseId, Long skuId,
                             BigDecimal onHandQuantity, BigDecimal reservedQuantity,
                             BigDecimal frozenQuantity, BigDecimal safetyStockQuantity,
                             BigDecimal availableQuantity, long lastLedgerSequence,
                             long balanceVersion) {
    }
}
