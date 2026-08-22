package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

/** 开店检查专用班次只读投影。 */
public interface StoreOnboardingShiftMapper {
    int countOpenOrClosing(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
}
