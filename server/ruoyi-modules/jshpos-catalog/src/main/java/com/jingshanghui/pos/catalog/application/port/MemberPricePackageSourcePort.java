package com.jingshanghui.pos.catalog.application.port;

import java.time.LocalDateTime;
import java.util.List;

/** Pricing Owner 向离线数据包装配器暴露的只读会员价投影。 */
public interface MemberPricePackageSourcePort {
    record MemberPricePackageRow(String versionId, int versionNo, String levelCode, Long skuId,
                                 Long unitId, Long scopeStoreId, long amountMinor,
                                 LocalDateTime effectiveAt, LocalDateTime expiresAt,
                                 String contentSha256) { }

    List<MemberPricePackageRow> listForPackage(String tenantId, Long storeId,
                                               LocalDateTime windowStart, LocalDateTime windowEnd);
}
