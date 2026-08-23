package com.jingshanghui.pos.saas.application.model;

import java.time.LocalDateTime;
import java.util.List;

/** T2-SAA-001 应用层不可变命令与只读投影。 */
public final class SaasModels {
    private SaasModels() { }

    /** @param applicationId 申请 ULID @param applicationCode 业务申请号 @param tenantId 服务端分配租户号 */
    public record ApplicationRecord(String applicationId, String applicationCode, String tenantId,
        Long technicalTenantId, String companyName, String industry, Long planId, String state,
        Long submitterUserId, Long approverUserId, Integer recordVersion, String contentSha256,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }

    /** @param versionId 权益版本 ULID @param planId 套餐主键 @param versionNo 只增版本号 */
    public record EntitlementVersionRecord(String versionId, Long planId, Integer versionNo, String state,
        LocalDateTime effectiveAt, LocalDateTime expiresAt, String contentSha256, Long creatorUserId,
        Long approverUserId, Integer recordVersion, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    /** @param featureCode 功能代码 @param enabled 是否启用 @param quotaLimit 配额上限，空表示非配额权益 */
    public record EntitlementItemRecord(String itemId, String versionId, String featureCode,
        Boolean enabled, Long quotaLimit, String itemSha256) { }

    /** @param tenantId 可信租户 @param versionId 已绑定权益版本 @param lifecycleState 商业生命周期 */
    public record TenantEntitlementRecord(String tenantId, Long planId, String versionId,
        String lifecycleState, Integer lifecycleVersion, LocalDateTime updatedAt) { }

    /**
     * SaaS Owner 维护的订阅访问投影。
     * @param tenantId 可信技术租户号
     * @param subscriptionId Subscription Owner 的稳定订阅标识
     * @param accessMode NORMAL/GRACE/RECOVERY_ONLY/TERMINATED_RECOVERY
     * @param sourceVersion Subscription 只追加状态版本
     * @param sourceSha256 来源状态事实摘要
     * @param recordVersion SaaS 投影乐观锁版本
     * @param updatedAt 最近切换时间 UTC
     */
    public record SubscriptionAccessRecord(String tenantId, String subscriptionId, String accessMode,
        Integer sourceVersion, String sourceSha256, Integer recordVersion, LocalDateTime updatedAt) { }

    /** @param commandId 命令事实 @param requestSha256 请求摘要 @param resultRef 稳定结果引用 */
    public record CommandRecord(String commandId, String authorityScope, String operation,
        String idempotencyKey, String requestSha256, String resultRef, String resultState) { }

    /** @param application 申请头 @param checkpoints 初始化检查点 @param lifecycle 商业生命周期 */
    public record ApplicationDetail(ApplicationRecord application, List<String> checkpoints,
        TenantEntitlementRecord lifecycle) { }

    /** @param allowed 服务端授权结论 @param reasonCode 可解释原因 @param versionId 冻结权益版本 */
    public record EntitlementDecision(boolean allowed, String reasonCode, String versionId,
        Long quotaLimit, Long quotaUsed) { }

    /** 创建商户申请命令。 */
    public record CreateApplication(String applicationCode, String companyName, String industry,
        Long planId, String idempotencyKey, String correlationId) { }

    /** 对申请执行具名操作。 */
    public record ApplicationCommand(String applicationId, String reason, String idempotencyKey,
        String correlationId) { }

    /** 一次性技术租户初始化输入；敏感字段不得持久化或记录日志。 */
    public record ProvisionCommand(String applicationId, String contactName, String contactPhone,
        String bootstrapUsername, char[] bootstrapPassword, String idempotencyKey, String correlationId) { }

    /** 创建套餐主数据。 */
    public record CreatePlan(String planCode, String planName, Long platformPackageId,
        Long accountLimit, String idempotencyKey, String correlationId) { }

    /** 创建权益版本。 */
    public record CreateEntitlementVersion(Long planId, Integer versionNo, LocalDateTime effectiveAt,
        LocalDateTime expiresAt, List<EntitlementItemInput> items, String idempotencyKey, String correlationId) { }

    /** 权益条目输入。 */
    public record EntitlementItemInput(String featureCode, Boolean enabled, Long quotaLimit) { }

    /** 对权益版本执行具名操作。 */
    public record EntitlementCommand(String versionId, String idempotencyKey, String correlationId) { }

    /** 对技术租户执行受控生命周期命令。 */
    public record LifecycleCommand(String tenantId, String reason, String idempotencyKey, String correlationId) { }
}
