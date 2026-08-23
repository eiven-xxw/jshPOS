package com.jingshanghui.pos.member.application.port;

import java.time.LocalDateTime;
import java.util.List;

/** Member Owner 向离线数据包装配器暴露的只读、无 PII 权益版本投影。 */
public interface MemberBenefitPackageSourcePort {
    /** 已批准且与门店窗口相交的等级权益行。 */
    record BenefitPackageRow(String versionId, String levelCode, boolean memberPriceEligible,
                             boolean stackingAllowed, String defaultCombinationPolicy,
                             boolean policyAllowStacking, long revocationEpoch,
                             LocalDateTime effectiveAt, LocalDateTime expiresAt,
                             String contentSha256) { }

    List<BenefitPackageRow> listForPackage(String tenantId, Long storeId,
                                           LocalDateTime windowStart, LocalDateTime windowEnd);
}
