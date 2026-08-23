package com.jingshanghui.pos.member.application.model;

import java.time.LocalDateTime;

/** T2-MEM-003 只暴露无 PII 的权益版本和最小快照。 */
public final class BenefitViews {
    public record PolicyVersionView(String policyId, String versionId, String policyCode, String displayName,
                                    int versionNo, String state, String defaultCombinationPolicy,
                                    boolean allowStacking, boolean memberPriceEligible,
                                    LocalDateTime effectiveAt, LocalDateTime expiresAt,
                                    long revocationEpoch, String contentSha256, Long createdBy, Long approvedBy,
                                    int version) { }
    public record EntitlementSnapshotView(String snapshotId, String memberRefHash, String levelHistoryId,
                                          String levelCode, String benefitVersionId, Long storeId,
                                          boolean memberPriceEligible, boolean stackingAllowed,
                                          LocalDateTime effectiveAt, LocalDateTime expiresAt,
                                          long revocationEpoch, String rightsDigest, String contentSha256) { }
    private BenefitViews() { }
}
