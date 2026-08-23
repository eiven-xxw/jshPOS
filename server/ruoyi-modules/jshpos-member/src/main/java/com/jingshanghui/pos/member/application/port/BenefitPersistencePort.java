package com.jingshanghui.pos.member.application.port;

import com.jingshanghui.pos.member.application.model.BenefitViews.EntitlementSnapshotView;
import com.jingshanghui.pos.member.application.model.BenefitViews.PolicyVersionView;

import java.time.LocalDateTime;
import java.util.List;

/** 会员权益版本、快照、命令、审计和 Outbox 的 XML 持久化端口。 */
public interface BenefitPersistencePort {
    record PolicyWrite(String tenantId, String policyId, String policyCode, String displayName, Long createdBy) { }
    record VersionWrite(String tenantId, String versionId, String policyId, int versionNo,
                        String defaultCombinationPolicy, boolean allowStacking, String contentSha256,
                        Long createdBy, LocalDateTime createdAt) { }
    record ScopeWrite(String tenantId, String scopeId, String versionId, Long storeId, String contentSha256,
                      LocalDateTime createdAt) { }
    record MappingWrite(String tenantId, String mappingId, String versionId, String levelCode,
                        boolean memberPriceEligible, boolean stackingAllowed, String contentSha256,
                        LocalDateTime createdAt) { }
    record VersionTransition(String tenantId, String versionId, String fromState, String toState,
                             int expectedVersion, Long actorUserId, Long approvedBy, LocalDateTime effectiveAt,
                             LocalDateTime expiresAt, long revocationEpoch, LocalDateTime changedAt) { }
    record StateEventWrite(String tenantId, String eventId, String versionId, String fromState, String toState,
                           String reasonCode, String reasonSha256, Long actorUserId, String correlationId,
                           LocalDateTime occurredAt, String contentSha256) { }
    record SnapshotWrite(String tenantId, String snapshotId, String memberId, String memberRefHash,
                         String levelHistoryId, String levelCode, String benefitVersionId, Long storeId,
                         boolean memberPriceEligible, boolean stackingAllowed, LocalDateTime effectiveAt,
                         LocalDateTime expiresAt, long revocationEpoch, String rightsDigest,
                         String contentSha256, LocalDateTime issuedAt) { }
    record StoredCommand(String requestSha256, String aggregateId, String resultSha256, String resultJson) { }
    record CommandWrite(String tenantId, String commandId, String commandType, String idempotencyKey,
                        String requestSha256, String aggregateId, String resultSha256, String resultJson,
                        LocalDateTime createdAt) { }
    record AuditWrite(String tenantId, String auditId, String action, String targetId, Long actorUserId,
                      String reasonCode, String summarySha256, String correlationId, LocalDateTime occurredAt) { }
    record OutboxWrite(String tenantId, String eventId, String eventType, String aggregateId, int aggregateVersion,
                       String payloadJson, String payloadSha256, LocalDateTime occurredAt) { }

    int insertPolicy(PolicyWrite value);
    int insertVersion(VersionWrite value);
    int insertScopes(List<ScopeWrite> values);
    int insertMappings(List<MappingWrite> values);
    PolicyVersionView findVersion(String tenantId, String policyId, String versionId);
    PolicyVersionView lockVersion(String tenantId, String policyId, String versionId);
    int transitionVersion(VersionTransition value);
    int insertStateEvent(StateEventWrite value);
    PolicyVersionView findActiveVersion(String tenantId, Long storeId, String levelCode, LocalDateTime at);
    int insertSnapshot(SnapshotWrite value);
    EntitlementSnapshotView findSnapshot(String tenantId, String snapshotId);
    StoredCommand findCommand(String tenantId, String commandType, String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertAudit(AuditWrite value);
    int insertOutbox(OutboxWrite value);
}
