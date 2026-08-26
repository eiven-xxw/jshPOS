package com.jingshanghui.pos.reporting.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 库存成本分页和批量导出的规范读取身份，绑定租户、投影版本、日期、门店和可选筛选。 */
public final class InventoryCostReportReadIdentity {
    private InventoryCostReportReadIdentity() {
    }

    public static String filterSha256(String tenantId, String projectionVersion, LocalDate fromDate,
                                      LocalDate toDate, List<Long> storeIds, String warehouseId, Long skuId) {
        String stores = String.join(",", storeIds.stream().sorted().map(String::valueOf).toList());
        return CanonicalReportHash.sha256("INVENTORY_COST|" + tenantId + "|" + projectionVersion + "|"
            + fromDate + "|" + toDate + "|" + stores + "|" + Objects.toString(warehouseId, "") + "|"
            + Objects.toString(skuId, ""));
    }
}
