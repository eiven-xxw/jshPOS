package com.jingshanghui.pos.release.infrastructure.persistence;

import java.time.Instant;

/** Release Owner XML Mapper 的具名参数和只读行；核心实体与数据库行模型分离。 */
public final class ReleasePersistenceParams {
    private ReleasePersistenceParams() { }

    /** 发布主记录。 */
    public record ReleaseRow(String releaseId, String tenantId, String artifactType, String releaseVersion,
                             String channelCode, String objectKey, String artifactSha256, String signatureBase64,
                             String keyVersion, String buildCommit, String sbomSha256, String manifestSha256,
                             String minAppVersion, String maxAppVersion, String minProtocolVersion,
                             String maxProtocolVersion, String minSchemaVersion, String maxSchemaVersion,
                             String minSystemVersion, String maxSystemVersion, String requiredCapabilitySha256,
                             String state, long versionNo, Instant createdAt) { }
    /** 发布草稿写入参数。 */
    public record InsertRelease(String releaseId, String tenantId, String artifactType, String releaseVersion,
                                String channelCode, String objectKey, String artifactSha256, String signatureBase64,
                                String keyVersion, String buildCommit, String sbomSha256, String manifestSha256,
                                String minAppVersion, String maxAppVersion, String minProtocolVersion,
                                String maxProtocolVersion, String minSchemaVersion, String maxSchemaVersion,
                                String minSystemVersion, String maxSystemVersion, String requiredCapabilitySha256,
                                String requestSha256, long actorId, String correlationId) { }
    /** 发布范围只追加参数。 */
    public record InsertScope(String scopeId, String tenantId, String aggregateType, String aggregateId,
                              Long storeId) { }
    /** 发布物状态条件更新。 */
    public record TransitionRelease(String tenantId, String releaseId, String fromState, String toState,
                                    long expectedVersion, long actorId, String correlationId) { }

    /** 灰度批次只读行。 */
    public record RolloutRow(String rolloutId, String tenantId, String releaseId, int canaryPercent,
                             String state, long versionNo, Instant createdAt) { }
    /** 灰度批次写入参数。 */
    public record InsertRollout(String rolloutId, String tenantId, String releaseId, int canaryPercent,
                                String requestSha256, long actorId, String correlationId) { }
    /** 灰度状态条件更新。 */
    public record TransitionRollout(String tenantId, String rolloutId, String fromState, String toState,
                                    long expectedVersion, long actorId, String correlationId) { }

    /** 终端任务只读行。 */
    public record TaskRow(String taskId, String tenantId, String rolloutId, String releaseId, String deviceId,
                          Long storeId, String state, String lastEvidenceSha256, long versionNo,
                          Instant createdAt) { }
    /** 终端任务写入参数。 */
    public record InsertTask(String taskId, String tenantId, String rolloutId, String releaseId, String deviceId,
                             Long storeId, String requestSha256, String evidenceSha256,
                             long actorId, String correlationId) { }
    /** 终端任务状态条件更新。 */
    public record TransitionTask(String tenantId, String taskId, String fromState, String toState,
                                 long expectedVersion, String evidenceSha256, long actorId,
                                 String correlationId) { }

    /** 幂等命令结果只读行。 */
    public record CommandRow(String requestSha256, String aggregateId, String resultCode) { }
    /** 幂等命令结果只追加参数。 */
    public record InsertCommand(String commandId, String tenantId, String commandType, String idempotencyKey,
                                String requestSha256, String aggregateId, String resultCode,
                                long actorId, Instant occurredAt) { }
    /** 发布事件只追加参数。 */
    public record InsertEvent(String eventId, String tenantId, String aggregateType, String aggregateId,
                              String eventType, String fromState, String toState, String evidenceSha256,
                              String correlationId) { }
    /** 发布审计只追加参数。 */
    public record InsertAudit(String auditId, String tenantId, String aggregateType, String aggregateId,
                              String actionCode, String beforeState, String afterState, String evidenceSha256,
                              long actorId, String correlationId) { }
    /** 任务状态汇总行。 */
    public record SummaryRow(long succeeded, long active, long failed) { }
}
