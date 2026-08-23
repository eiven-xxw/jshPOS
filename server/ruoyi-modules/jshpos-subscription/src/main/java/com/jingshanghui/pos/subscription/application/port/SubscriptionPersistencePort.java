package com.jingshanghui.pos.subscription.application.port;

import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.*;

import java.time.LocalDateTime;
import java.util.List;

/** Subscription Owner 持久化端口；状态、期限、审计和 Outbox 只能通过具名能力变更。 */
public interface SubscriptionPersistencePort {
    SubscriptionRecord find(String subscriptionId);
    SubscriptionRecord lock(String subscriptionId);
    SubscriptionRecord findByTenant(String tenantId);
    List<TermRecord> listTerms(String subscriptionId);
    List<SubscriptionRecord> findDue(LocalDateTime now, int limit);
    void insertSubscription(SubscriptionWrite write);
    int changeState(StateChange change);
    int changeCurrentTerm(TermProjectionChange change);
    void appendTerm(TermWrite write);
    void appendState(StateEventWrite write);
    void appendNotification(NotificationWrite write);
    CommandRecord findCommand(String scope, String operation, String idempotencyKey);
    void insertCommand(CommandWrite write);
    void appendAudit(AuditWrite write);
    void appendOutbox(OutboxWrite write);
    void ensureCheckpoint(String jobCode, LocalDateTime at);
    int acquireCheckpoint(LeaseWrite write);
    int completeCheckpoint(CheckpointComplete write);

    /** 创建订阅投影。 */
    record SubscriptionWrite(String subscriptionId, String tenantId, Long planId, String entitlementVersionId,
        String contractRef, String externalOrderRef, String state, Integer stateVersion,
        Integer currentTermVersion, LocalDateTime startsAt, LocalDateTime endsAt,
        LocalDateTime graceEndsAt, String businessTimeZone, String degradationPolicyVersion,
        String contentSha256, LocalDateTime at) { }
    /** 条件状态更新。 */
    record StateChange(String subscriptionId, String fromState, String toState, Integer expectedVersion,
        String contentSha256, LocalDateTime at) { }
    /** 续期时切换到新期限投影，历史期限不修改。 */
    record TermProjectionChange(String subscriptionId, Integer expectedTermVersion, Integer newTermVersion,
        String contractRef, String externalOrderRef, LocalDateTime startsAt, LocalDateTime endsAt,
        LocalDateTime graceEndsAt, String businessTimeZone, String contentSha256, LocalDateTime at) { }
    /** 只追加期限版本。 */
    record TermWrite(String termId, String subscriptionId, Integer termVersion, LocalDateTime startsAt,
        LocalDateTime endsAt, LocalDateTime graceEndsAt, String businessTimeZone, String contractRef,
        String externalOrderRef, String termSha256, LocalDateTime at) { }
    /** 只追加状态事实。 */
    record StateEventWrite(String eventId, String tenantId, String subscriptionId, String fromState,
        String toState, Integer stateVersion, Integer termVersion, String actionCode, String reason,
        String requestSha256, String correlationId, Long actorUserId, LocalDateTime at) { }
    /** 只追加通知意图；本阶段不发送真实短信或邮件。 */
    record NotificationWrite(String intentId, String tenantId, String subscriptionId, Integer termVersion,
        String notificationType, LocalDateTime scheduledAt, String payloadSha256, LocalDateTime at) { }
    /** 幂等命令只读投影。 */
    record CommandRecord(String commandId, String authorityScope, String operation, String idempotencyKey,
        String requestSha256, String resultRef, String resultState) { }
    /** 幂等命令稳定结果。 */
    record CommandWrite(String commandId, String authorityScope, String operation, String idempotencyKey,
        String requestSha256, String resultRef, String resultState, LocalDateTime at) { }
    /** 订阅只追加审计。 */
    record AuditWrite(String auditId, String tenantId, String subscriptionId, String actionCode,
        String result, String requestSha256, String correlationId, Long actorUserId,
        String maskedSummary, LocalDateTime at) { }
    /** 订阅 Outbox 事实。 */
    record OutboxWrite(String outboxId, String tenantId, String subscriptionId, String eventType,
        String payloadJson, String payloadSha256, String correlationId, LocalDateTime at) { }
    /** 调度检查点租约。 */
    record LeaseWrite(String jobCode, String runnerId, LocalDateTime now, LocalDateTime leaseUntil) { }
    /** 完成调度扫描并单调推进检查点。 */
    record CheckpointComplete(String jobCode, String runnerId, LocalDateTime scannedAt, String resultSha256) { }
}
