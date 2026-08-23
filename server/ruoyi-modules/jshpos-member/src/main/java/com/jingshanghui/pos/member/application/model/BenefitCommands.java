package com.jingshanghui.pos.member.application.model;

import java.time.Instant;
import java.util.List;

/** T2-MEM-003 会员权益控制面和快照命令；刻意不携带 tenant_id。 */
public final class BenefitCommands {
    public record LevelRule(String levelCode, boolean memberPriceEligible, boolean stackingAllowed) { }
    public record CreateDraft(String commandId, String policyId, String versionId, String policyCode,
                              String displayName, List<LevelRule> levelRules, List<Long> storeIds,
                              String correlationId) { }
    public record VersionAction(String commandId, String policyId, String versionId, String contentSha256,
                                Instant effectiveAt, Instant expiresAt, String reasonCode, String reason,
                                String correlationId) { }
    public record IssueEntitlement(String commandId, String snapshotId, String memberId, Long storeId,
                                   Instant quoteAt, String correlationId) { }
    private BenefitCommands() { }
}
