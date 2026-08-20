package com.jingshanghui.pos.release.domain;

import java.time.Instant;
import java.util.Set;

/**
 * Gate 6B 发布治理领域模型。发布物身份在签名通过后冻结，发布事件和审计只追加。
 */
public final class ReleaseModels {
    private ReleaseModels() { }

    /** 发布物类型；不把厂商安装协议带入核心状态机。 */
    public enum ArtifactType { SERVER, WEB, MYSQL_SCHEMA, SQLITE_SCHEMA, TEMPLATE_PACKAGE, DATA_PACKAGE, APK }
    /** 发布通道。 */
    public enum Channel { INTERNAL, CANARY, STABLE, EMERGENCY }
    /** 发布物登记状态。 */
    public enum ReleaseState { DRAFT, SIGNED, STAGED, REVOKED }
    /** 灰度批次状态。 */
    public enum RolloutState { PLANNED, CANARY, ROLLING, PAUSED, COMPLETED, FAILED }
    /** 单终端执行状态。 */
    public enum TaskState {
        PLANNED, DOWNLOADING, VERIFIED, INSTALLING, HEALTH_CHECK, SUCCEEDED,
        ROLLED_BACK, FORWARD_FIX_REQUIRED, FAILED_CLOSED
    }
    /** 软件执行观察类型；真实厂商命令不属于本 Sprint。 */
    public enum ObservationType {
        DOWNLOAD_STARTED, DOWNLOAD_RESUMED, ARTIFACT_VERIFIED, INSTALL_STARTED,
        INSTALL_SUCCEEDED, HEALTH_PASSED, HEALTH_FAILED, MIGRATION_FAILED,
        ROLLBACK_SUCCEEDED, FORWARD_FIX_REQUIRED, DIGEST_MISMATCH, SIGNATURE_INVALID
    }

    /**
     * 兼容窗口。
     * @param minAppVersion 最低应用版本
     * @param maxAppVersion 最高应用版本
     * @param minProtocolVersion 最低协议版本
     * @param maxProtocolVersion 最高协议版本
     * @param minSchemaVersion 最低Schema版本
     * @param maxSchemaVersion 最高Schema版本
     * @param minSystemVersion 最低系统版本
     * @param maxSystemVersion 最高系统版本
     * @param requiredCapabilitySha256 所需终端能力快照摘要；空表示不限定
     */
    public record CompatibilityWindow(String minAppVersion, String maxAppVersion,
                                      String minProtocolVersion, String maxProtocolVersion,
                                      String minSchemaVersion, String maxSchemaVersion,
                                      String minSystemVersion, String maxSystemVersion,
                                      String requiredCapabilitySha256) { }

    /**
     * 发布物聚合。
     * @param releaseId 服务端发布ULID
     * @param tenantId 可信租户标识
     * @param artifactType 发布物类型
     * @param version 语义版本
     * @param channel 发布通道
     * @param objectKey 租户命名空间对象键
     * @param artifactSha256 发布物SHA-256
     * @param signatureBase64 发布物签名
     * @param keyVersion 签名密钥版本引用
     * @param buildCommit 构建Git提交
     * @param sbomSha256 SBOM摘要
     * @param manifestSha256 规范发布清单摘要
     * @param compatibility 兼容窗口
     * @param targetStoreIds 受权目标门店集合
     * @param state 发布物状态
     * @param versionNo 乐观锁版本
     * @param createdAt 创建UTC时刻
     */
    public record Release(String releaseId, String tenantId, ArtifactType artifactType, String version,
                          Channel channel, String objectKey, String artifactSha256, String signatureBase64,
                          String keyVersion, String buildCommit, String sbomSha256, String manifestSha256,
                          CompatibilityWindow compatibility, Set<Long> targetStoreIds, ReleaseState state,
                          long versionNo, Instant createdAt) { }

    /**
     * 灰度批次。
     * @param rolloutId 批次ULID
     * @param tenantId 可信租户标识
     * @param releaseId 发布ULID
     * @param targetStoreIds 批次门店范围
     * @param canaryPercent 灰度百分比
     * @param state 批次状态
     * @param versionNo 乐观锁版本
     * @param createdAt 创建UTC时刻
     */
    public record Rollout(String rolloutId, String tenantId, String releaseId, Set<Long> targetStoreIds,
                          int canaryPercent, RolloutState state, long versionNo, Instant createdAt) { }

    /**
     * 单终端发布任务。
     * @param taskId 任务ULID
     * @param tenantId 可信租户标识
     * @param rolloutId 灰度批次ULID
     * @param releaseId 发布ULID
     * @param deviceId 注册表终端ULID
     * @param storeId 注册表门店ID
     * @param state 任务状态
     * @param lastEvidenceSha256 最近去敏证据摘要
     * @param versionNo 乐观锁版本
     * @param createdAt 创建UTC时刻
     */
    public record TerminalTask(String taskId, String tenantId, String rolloutId, String releaseId,
                               String deviceId, Long storeId, TaskState state, String lastEvidenceSha256,
                               long versionNo, Instant createdAt) { }

    /**
     * 可信终端快照，只能由终端注册表适配器构造。
     * @param tenantId 注册租户
     * @param deviceId 终端ULID
     * @param storeId 绑定门店
     * @param status 注册状态
     * @param appVersion 应用版本
     * @param minProtocolVersion 最低协议
     * @param maxProtocolVersion 最高协议
     * @param schemaVersion 本地Schema
     * @param systemVersion 系统版本；未知为空
     * @param capabilitySha256 能力快照摘要
     */
    public record TrustedTerminal(String tenantId, String deviceId, Long storeId, String status,
                                  String appVersion, String minProtocolVersion, String maxProtocolVersion,
                                  String schemaVersion, String systemVersion, String capabilitySha256) { }

    /**
     * 由内部 Owner/可信遥测组合的营业保护快照。
     * @param pendingOutboxCount 待同步事件数
     * @param unknownPaymentCount UNKNOWN支付数
     * @param unknownRefundCount UNKNOWN退款数
     * @param openShift 是否处于营业班次
     * @param protectedBusinessWindow 是否处于受保护营业时段
     * @param storageHealthy 存储是否满足安装要求
     * @param clockHealthy 时钟是否可信
     */
    public record SafetySnapshot(long pendingOutboxCount, long unknownPaymentCount, long unknownRefundCount,
                                 boolean openShift, boolean protectedBusinessWindow,
                                 boolean storageHealthy, boolean clockHealthy) { }

    /** @param sha256 实际对象摘要 @param signatureValid 签名是否有效 @param keyVersion 实际验签密钥版本 @param sizeBytes 对象字节数 */
    public record ArtifactObservation(String sha256, boolean signatureValid, String keyVersion, long sizeBytes) { }
    /** @param succeeded 成功任务数 @param active 活跃任务数 @param failed 失败关闭/前向修复/回退任务数 */
    public record TaskSummary(long succeeded, long active, long failed) { }
    /** @param requestSha256 规范请求摘要 @param aggregateId 聚合ULID @param resultCode 稳定结果码 */
    public record CommandResult(String requestSha256, String aggregateId, String resultCode) { }

    /** 创建发布草稿；releaseId和tenantId由服务端生成/注入。 */
    public record CreateRelease(ArtifactType artifactType, String version, Channel channel, String objectKey,
                                String artifactSha256, String signatureBase64, String keyVersion,
                                String buildCommit, String sbomSha256, CompatibilityWindow compatibility,
                                Set<Long> targetStoreIds, String idempotencyKey) { }
    /** @param releaseId 发布ULID @param idempotencyKey 命令幂等键 */
    public record ReleaseCommand(String releaseId, String idempotencyKey) { }
    /** @param releaseId 发布ULID @param targetStoreIds 灰度范围 @param canaryPercent 灰度百分比 @param idempotencyKey 命令幂等键 */
    public record CreateRollout(String releaseId, Set<Long> targetStoreIds, int canaryPercent,
                                String idempotencyKey) { }
    /** @param rolloutId 灰度批次ULID @param deviceId 注册表终端ULID @param idempotencyKey 命令幂等键 */
    public record AssignTerminal(String rolloutId, String deviceId, String idempotencyKey) { }
    /** @param taskId 任务ULID @param type 软件观察类型 @param artifactSha256 实际发布物摘要 @param evidenceSha256 去敏证据摘要 @param idempotencyKey 命令幂等键 */
    public record RecordObservation(String taskId, ObservationType type, String artifactSha256,
                                    String evidenceSha256, String idempotencyKey) { }
}
