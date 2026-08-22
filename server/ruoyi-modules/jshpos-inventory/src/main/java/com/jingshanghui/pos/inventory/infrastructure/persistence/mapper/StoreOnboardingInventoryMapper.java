package com.jingshanghui.pos.inventory.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/** 开店检查专用只读库存投影。 */
public interface StoreOnboardingInventoryMapper {
    int countEffectiveWarehousePolicies(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
}
