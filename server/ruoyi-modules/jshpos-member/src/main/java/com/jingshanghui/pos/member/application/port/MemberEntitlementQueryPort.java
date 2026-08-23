package com.jingshanghui.pos.member.application.port;

import java.time.Instant;

/** Pricing/Promotion 只能通过该无 PII 端口观察已发行权益快照。 */
public interface MemberEntitlementQueryPort {
    record EntitlementQuote(String snapshotId, String memberRefHash, String levelCode,
                            String benefitVersionId, Long storeId, boolean memberPriceEligible,
                            boolean stackingAllowed, Instant effectiveAt, Instant expiresAt,
                            long revocationEpoch, String rightsDigest, String contentSha256) { }
    EntitlementQuote resolve(String snapshotId, Long storeId, Instant quoteAt);
}
