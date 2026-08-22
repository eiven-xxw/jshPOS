package com.jingshanghui.pos.procurement.application.port;

import java.math.BigDecimal;

/** Replenishment Owner 使用的 Procurement 权威只读端口。 */
public interface ReplenishmentProcurementSnapshotPort {

    SupplierSnapshot requireActiveSupplier(String supplierId);

    BigDecimal confirmedInTransitBase(String warehouseId, Long skuId, String supplierId);

    record SupplierSnapshot(String supplierId, String code, String name, String status) {
    }
}
