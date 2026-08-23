package com.jingshanghui.pos.subscription.application.model;

import java.time.LocalDateTime;
import java.util.List;

/** T2-SUB-001 应用层不可变命令和只读投影。 */
public final class SubscriptionModels {
    private SubscriptionModels() { }

    /** 当前订阅受控投影，历史期限和状态另行只追加保存。 */
    public record SubscriptionRecord(String subscriptionId, String tenantId, Long planId,
        String entitlementVersionId, String contractRef, String externalOrderRef, String state,
        Integer stateVersion, Integer currentTermVersion, LocalDateTime startsAt, LocalDateTime endsAt,
        LocalDateTime graceEndsAt, String businessTimeZone, String degradationPolicyVersion,
        String contentSha256, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    /** @param termVersion 单调期限版本 @param termSha256 期限内容摘要 */
    public record TermRecord(String termId, String subscriptionId, Integer termVersion,
        LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime graceEndsAt,
        String businessTimeZone, String contractRef, String externalOrderRef,
        String termSha256, LocalDateTime createdAt) { }

    /** 订阅详情只返回脱敏不透明引用，不包含计费或资金信息。 */
    public record SubscriptionDetail(SubscriptionRecord subscription, List<TermRecord> terms,
        String accessMode, List<String> retainedCapabilities) { }

    /** 平台创建订阅命令；targetTenantId 仅在平台鉴权后作为目标并由 SaaS 交叉校验。 */
    public record CreateSubscription(String targetTenantId, String contractRef, String externalOrderRef,
        LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime graceEndsAt,
        String businessTimeZone, String degradationPolicyVersion, String idempotencyKey,
        String correlationId) { }

    /** 对订阅执行无新期限的具名命令。 */
    public record SubscriptionCommand(String subscriptionId, String reason,
        String idempotencyKey, String correlationId) { }

    /** 续期或恢复使用的新只追加期限。 */
    public record NewTermCommand(String subscriptionId, String contractRef, String externalOrderRef,
        LocalDateTime startsAt, LocalDateTime endsAt, LocalDateTime graceEndsAt,
        String businessTimeZone, String idempotencyKey, String correlationId) { }

    /** 到期扫描结果。 */
    public record ScanResult(String runnerId, int inspected, int transitioned, LocalDateTime scannedAt) { }
}
