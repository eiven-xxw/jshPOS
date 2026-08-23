package com.jingshanghui.pos.inventory.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

/** Inventory Owner 仅向异常中心公开的负库存异常窄查询。 */
public interface InventoryExceptionMapper {
    List<InventoryExceptionRow> listOpen(@Param("tenantId") String tenantId,@Param("storeId") Long storeId,@Param("limit") int limit);
    InventoryExceptionRow find(@Param("tenantId") String tenantId,@Param("storeId") Long storeId,
                               @Param("anomalyId") String anomalyId);
    /**
     * 库存异常中心只读行。
     *
     * @param anomalyId 库存异常稳定标识
     * @param warehouseId 仓库业务标识
     * @param skuId 商品 SKU 平台主键
     * @param anomalyType 库存异常类型
     * @param observedQuantity 异常发生时精确数量文本
     * @param sourceEventId 产生异常的库存事件标识
     * @param occurredAt 异常发生 UTC 时间
     */
    record InventoryExceptionRow(String anomalyId,String warehouseId,Long skuId,String anomalyType,
                                 String observedQuantity,String sourceEventId,LocalDateTime occurredAt) { }
}
