package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 商品 Owner 对库存/采购公开的受控只读实现。 */
@Service
@RequiredArgsConstructor
public class InventoryCatalogSnapshotService implements InventoryCatalogSnapshotPort {

    private final CatalogMapper mapper;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional(readOnly = true)
    public SkuUnitSnapshot requirePrimaryUnit(Long skuId) {
        return require(mapper.findInventoryPrimaryUnit(tenantContext.requireTenantId(), skuId), skuId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public SkuUnitSnapshot requireUnit(Long skuId, Long unitId) {
        return require(mapper.findInventorySkuUnit(tenantContext.requireTenantId(), skuId, unitId), skuId, unitId);
    }

    private SkuUnitSnapshot require(SkuUnitSnapshot snapshot, Long skuId, Long unitId) {
        if (snapshot == null) {
            throw new ServiceException("CAT-SNAPSHOT-001: SKU 或单位不存在、未启用或不可见", 404);
        }
        if (snapshot.numerator() <= 0 || snapshot.denominator() <= 0) {
            throw new ServiceException("CAT-SNAPSHOT-002: 商品单位换算非法", 409);
        }
        if ((unitId != null && !unitId.equals(snapshot.unitId())) || !skuId.equals(snapshot.skuId())) {
            throw new ServiceException("CAT-SNAPSHOT-003: 商品单位快照主体不一致", 409);
        }
        return snapshot;
    }
}
