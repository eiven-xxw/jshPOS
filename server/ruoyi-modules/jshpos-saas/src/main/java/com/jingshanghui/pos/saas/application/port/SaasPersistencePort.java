package com.jingshanghui.pos.saas.application.port;

import com.jingshanghui.pos.saas.application.model.SaasModels.*;

import java.time.LocalDateTime;
import java.util.List;

/** SaaS Owner 持久化端口；复杂状态、幂等、配额和只追加事实由 XML 实现。 */
public interface SaasPersistencePort {
    ApplicationRecord findApplication(String applicationId);
    ApplicationRecord findApplicationByCode(String applicationCode);
    ApplicationRecord lockApplication(String applicationId);
    void insertApplication(ApplicationWrite write);
    int changeApplication(ApplicationChange change);
    void appendApplicationState(StateEventWrite write);

    EntitlementVersionRecord findVersion(String versionId);
    EntitlementVersionRecord lockVersion(String versionId);
    EntitlementVersionRecord findVersionByPlanNo(Long planId, Integer versionNo);
    EntitlementVersionRecord findEffectiveVersion(Long planId, LocalDateTime at);
    int countOverlappingVersions(Long planId, String versionId, LocalDateTime effectiveAt, LocalDateTime expiresAt);
    void insertVersion(VersionWrite write);
    void insertItem(ItemWrite write);
    List<EntitlementItemRecord> listItems(String versionId);
    int changeVersion(VersionChange change);

    TenantEntitlementRecord findTenantEntitlement(String tenantId);
    void bindTenant(TenantBindingWrite write);
    void seedQuota(TenantQuotaWrite write);
    int changeLifecycle(LifecycleChange change);
    void appendLifecycle(LifecycleEventWrite write);
    void insertCheckpoint(CheckpointWrite write);
    List<String> listCheckpoints(String applicationId);

    CommandRecord findCommand(String scope, String operation, String idempotencyKey);
    void insertCommand(CommandWrite write);
    void appendAudit(AuditWrite write);
    void appendOutbox(OutboxWrite write);
    Long quotaUsed(String tenantId, String featureCode);
    int consumeQuota(QuotaWrite write);
    SubscriptionAccessRecord findSubscriptionAccess(String tenantId);
    void insertSubscriptionAccess(SubscriptionAccessWrite write);
    int changeSubscriptionAccess(SubscriptionAccessChange change);
    void appendSubscriptionAccessEvent(SubscriptionAccessEventWrite write);

    /** 商户申请写入参数。 */
    record ApplicationWrite(String applicationId, String applicationCode, String companyName, String industry,
        Long planId, String state, Long submitterUserId, String contentSha256, LocalDateTime at) { }
    /** 商户申请条件状态更新。 */
    record ApplicationChange(String applicationId, String fromState, String toState, Integer expectedVersion,
        String tenantId, Long technicalTenantId, Long approverUserId, LocalDateTime at) { }
    /** 申请状态只追加事实。 */
    record StateEventWrite(String eventId, String applicationId, String tenantId, String fromState, String toState,
        String requestSha256, String correlationId, Long actorUserId, LocalDateTime at) { }
    /** 权益版本写入参数。 */
    record VersionWrite(String versionId, Long planId, Integer versionNo, String state, LocalDateTime effectiveAt,
        LocalDateTime expiresAt, String contentSha256, Long creatorUserId, LocalDateTime at) { }
    /** 权益条目只追加事实。 */
    record ItemWrite(String itemId, String versionId, String featureCode, Boolean enabled, Long quotaLimit,
        String itemSha256) { }
    /** 权益版本条件状态更新。 */
    record VersionChange(String versionId, String fromState, String toState, Integer expectedVersion,
        Long approverUserId, LocalDateTime at) { }
    /** 技术租户与权益版本绑定。 */
    record TenantBindingWrite(String tenantId, Long planId, String versionId, String lifecycleState,
        String bindingSha256, LocalDateTime at) { }
    /** 绑定权益时初始化的租户配额投影。 */
    record TenantQuotaWrite(String tenantId, String featureCode, Long quotaLimit, LocalDateTime at) { }
    /** 商业生命周期条件状态更新。 */
    record LifecycleChange(String tenantId, String fromState, String toState, Integer expectedVersion,
        LocalDateTime at) { }
    /** 商业生命周期只追加事实。 */
    record LifecycleEventWrite(String eventId, String tenantId, String fromState, String toState, String reason,
        String requestSha256, String correlationId, Long actorUserId, LocalDateTime at) { }
    /** 初始化 Saga 检查点。 */
    record CheckpointWrite(String checkpointId, String applicationId, String tenantId, String stepCode,
        String resultSha256, LocalDateTime at) { }
    /** 幂等命令结果。 */
    record CommandWrite(String commandId, String authorityScope, String operation, String idempotencyKey,
        String requestSha256, String resultRef, String resultState, LocalDateTime at) { }
    /** SaaS 审计事实。 */
    record AuditWrite(String auditId, String tenantId, String aggregateType, String aggregateId, String actionCode,
        String result, String requestSha256, String correlationId, Long actorUserId, String maskedSummary,
        LocalDateTime at) { }
    /** SaaS Outbox 事实。 */
    record OutboxWrite(String outboxId, String tenantId, String aggregateType, String aggregateId, String eventType,
        String payloadJson, String payloadSha256, String correlationId, LocalDateTime at) { }
    /** 配额原子消费参数。 */
    record QuotaWrite(String tenantId, String featureCode, Long delta, Long quotaLimit, LocalDateTime at) { }
    /** 首次建立订阅访问投影。 */
    record SubscriptionAccessWrite(String tenantId, String subscriptionId, String accessMode,
        Integer sourceVersion, String sourceSha256, LocalDateTime at) { }
    /** 以来源版本和乐观锁受控切换订阅访问模式。 */
    record SubscriptionAccessChange(String tenantId, String subscriptionId, String accessMode,
        Integer sourceVersion, String sourceSha256, Integer expectedRecordVersion, LocalDateTime at) { }
    /** SaaS Owner 的订阅访问模式只追加事实。 */
    record SubscriptionAccessEventWrite(String eventId, String tenantId, String subscriptionId,
        String fromMode, String toMode, Integer sourceVersion, String sourceSha256,
        String correlationId, LocalDateTime at) { }
}
