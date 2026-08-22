package com.jingshanghui.pos.inventory.application.port;

import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 开业期初库存进入不可变账本的 Owner 受控端口。 */
public interface BusinessMigrationInventoryPort {
    ApplyResult importOpeningInventory(OpeningInventoryCommand command);

    /**
     * tenant_id 不在命令中；成本由 Costing Owner 通过只读来源端口取得。
     * @param eventId 库存 Owner 的稳定幂等事件 ULID
     * @param batchId 迁移批次 ULID
     * @param rowId 期初库存来源行 ULID
     * @param warehouseId 目标仓库标识
     * @param storeId 目标门店主键
     * @param skuId Catalog SKU 主键
     * @param baseUnitId 冻结基础单位主键
     * @param quantity 基础单位精确数量
     * @param businessDate 门店业务日
     * @param correlationId 全链路关联标识
     */
    record OpeningInventoryCommand(String eventId, String batchId, String rowId, String warehouseId,
                                   Long storeId, Long skuId, Long baseUnitId, BigDecimal quantity,
                                   LocalDate businessDate, String correlationId) {
    }
}
