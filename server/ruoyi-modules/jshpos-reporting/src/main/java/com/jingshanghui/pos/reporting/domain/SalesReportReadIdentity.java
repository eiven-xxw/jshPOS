package com.jingshanghui.pos.reporting.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 销售分页和批量导出的规范读取身份，绑定租户、投影版本、日期、门店与可选筛选。 */
public final class SalesReportReadIdentity {
    private SalesReportReadIdentity() {
    }

    public static String filterSha256(String tenantId, String projectionVersion, LocalDate fromDate,
                                      LocalDate toDate, List<Long> storeIds, String terminalId, Long cashierId) {
        String stores = String.join(",", storeIds.stream().sorted().map(String::valueOf).toList());
        return CanonicalReportHash.sha256("SALES|" + tenantId + "|" + projectionVersion + "|" + fromDate
            + "|" + toDate + "|" + stores + "|" + Objects.toString(terminalId, "") + "|"
            + Objects.toString(cashierId, ""));
    }
}
