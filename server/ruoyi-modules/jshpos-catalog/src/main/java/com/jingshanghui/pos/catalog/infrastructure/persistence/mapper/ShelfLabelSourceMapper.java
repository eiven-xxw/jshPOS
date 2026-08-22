package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort.PriceSource;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Pricing/Catalog 供价签使用的只读复杂查询 Mapper。 */
public interface ShelfLabelSourceMapper {

    List<PriceSource> listPriceSources(@Param("tenantId") String tenantId,
                                       @Param("priceBookId") Long priceBookId);

    Long resolveAmount(@Param("tenantId") String tenantId, @Param("skuId") Long skuId,
                       @Param("unitId") Long unitId, @Param("storeId") Long storeId,
                       @Param("effectiveAt") LocalDateTime effectiveAt,
                       @Param("excludedPriceBookId") Long excludedPriceBookId);
}
