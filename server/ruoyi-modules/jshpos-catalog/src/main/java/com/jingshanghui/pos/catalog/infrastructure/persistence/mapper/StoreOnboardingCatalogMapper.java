package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import org.apache.ibatis.annotations.Param;

/** 门店开通只读投影；复杂价格覆盖查询统一位于 XML。 */
public interface StoreOnboardingCatalogMapper {
    int countActiveSku(@Param("tenantId") String tenantId);
    int countPricedSku(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
    PackageView findLatestPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
}
