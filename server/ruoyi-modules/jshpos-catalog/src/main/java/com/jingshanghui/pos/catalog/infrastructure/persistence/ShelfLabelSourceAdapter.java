package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.application.port.ShelfLabelSourcePort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.ShelfLabelSourceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 只读消费 Pricing/Catalog 权威快照，不拥有或修改来源表。 */
@Repository
@RequiredArgsConstructor
public class ShelfLabelSourceAdapter implements ShelfLabelSourcePort {

    private final ShelfLabelSourceMapper mapper;

    @Override
    public List<PriceSource> listPriceSources(String tenantId, Long priceBookId) {
        return mapper.listPriceSources(tenantId, priceBookId);
    }

    @Override
    public Long resolveAmount(String tenantId, Long skuId, Long unitId, Long storeId,
                              Instant effectiveAt, Long excludedPriceBookId) {
        return mapper.resolveAmount(tenantId, skuId, unitId, storeId,
            LocalDateTime.ofInstant(effectiveAt, ZoneOffset.UTC), excludedPriceBookId);
    }
}
