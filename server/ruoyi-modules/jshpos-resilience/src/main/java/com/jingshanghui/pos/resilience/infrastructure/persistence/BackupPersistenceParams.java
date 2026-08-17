package com.jingshanghui.pos.resilience.infrastructure.persistence;

import java.time.Instant;

/** BAK Owner XML Mapper 的具名参数与只读行模型。 */
public final class BackupPersistenceParams {
    private BackupPersistenceParams() {
    }

    /** @param backupId 备份ULID @param environmentCode 环境 @param tenantIdsCsv 排序租户集合 @param tenantScopeSha256 范围摘要 @param pointInTime 恢复点 @param latestIncludedFactAt 最后事实 @param schemaVersion Schema版本 @param applicationVersion 应用版本 @param keyVersion 密钥版本 @param immutableUntil 保留截止 @param requestSha256 请求摘要 @param actorId 操作者 @param correlationId 关联ULID */
    public record InsertBackup(String backupId, String environmentCode, String tenantIdsCsv,
                               String tenantScopeSha256, Instant pointInTime, Instant latestIncludedFactAt,
                               String schemaVersion, String applicationVersion, String keyVersion,
                               Instant immutableUntil, String requestSha256, long actorId, String correlationId) { }

    /** @param backupId 备份ULID @param objectId 对象ULID @param dataClass 数据类别 @param logicalName 逻辑路径 @param mediaType 媒体类型 @param tenantScopeSha256 范围摘要 @param plaintextSizeBytes 明文长度 @param plaintextSha256 明文摘要 @param ciphertextSizeBytes 密文长度 @param ciphertextSha256 密文摘要 @param keyVersion 密钥版本 @param nonceBase64 GCM nonce @param objectKey 只追加对象键 */
    public record InsertObject(String backupId, String objectId, String dataClass, String logicalName,
                               String mediaType, String tenantScopeSha256, long plaintextSizeBytes,
                               String plaintextSha256, long ciphertextSizeBytes, String ciphertextSha256,
                               String keyVersion, String nonceBase64, String objectKey) { }

    /** @param backupId 备份ULID @param expectedState 期望状态 @param targetState 目标状态 @param manifestSha256 清单摘要 @param manifestJson 规范清单 @param failureSha256 失败摘要 @param actorId 操作者 @param correlationId 关联ULID */
    public record TransitionBackup(String backupId, String expectedState, String targetState,
                                   String manifestSha256, String manifestJson, String failureSha256,
                                   long actorId, String correlationId) { }

    /** @param drillId 演练ULID @param backupId 备份ULID @param requestSha256 请求摘要 @param state 状态 @param startedAt 开始时刻 @param actorId 操作者 @param correlationId 关联ULID */
    public record InsertDrill(String drillId, String backupId, String requestSha256, String state,
                              Instant startedAt, long actorId, String correlationId) { }

    /** @param drillId 演练ULID @param expectedState 期望状态 @param targetState 目标状态 @param endedAt 结束时刻 @param rpoSeconds RPO秒 @param rtoSeconds RTO秒 @param evidenceSha256 证据摘要 @param actorId 操作者 @param correlationId 关联ULID */
    public record FinishDrill(String drillId, String expectedState, String targetState, Instant endedAt,
                              long rpoSeconds, long rtoSeconds, String evidenceSha256,
                              long actorId, String correlationId) { }

    /** @param checkId 校验ULID @param drillId 演练ULID @param checkCode 校验码 @param result 结果 @param evidenceSha256 证据摘要 */
    public record InsertCheck(String checkId, String drillId, String checkCode, String result,
                              String evidenceSha256) { }

    /** @param auditId 审计ULID @param backupId 可选备份ULID @param drillId 可选演练ULID @param actionCode 动作码 @param actorId 操作者 @param correlationId 关联ULID @param evidenceSha256 去敏证据摘要 */
    public record InsertAudit(String auditId, String backupId, String drillId, String actionCode,
                              long actorId, String correlationId, String evidenceSha256) { }

    /** @param backupId 备份ULID @param environmentCode 环境 @param tenantIdsCsv 排序租户集合 @param tenantScopeSha256 范围摘要 @param pointInTime 恢复点 @param latestIncludedFactAt 最后事实 @param schemaVersion Schema版本 @param applicationVersion 应用版本 @param keyVersion 密钥版本 @param immutableUntil 保留截止 @param requestSha256 请求摘要 @param manifestSha256 清单摘要 @param manifestJson 清单JSON @param state 状态 */
    public record BackupRow(String backupId, String environmentCode, String tenantIdsCsv,
                            String tenantScopeSha256, Instant pointInTime, Instant latestIncludedFactAt,
                            String schemaVersion, String applicationVersion, String keyVersion,
                            Instant immutableUntil, String requestSha256, String manifestSha256,
                            String manifestJson, String state) { }

    /** @param objectId 对象ULID @param dataClass 数据类别 @param logicalName 逻辑路径 @param mediaType 媒体类型 @param tenantScopeSha256 范围摘要 @param plaintextSizeBytes 明文长度 @param plaintextSha256 明文摘要 @param ciphertextSizeBytes 密文长度 @param ciphertextSha256 密文摘要 @param keyVersion 密钥版本 @param nonceBase64 GCM nonce @param objectKey 对象键 */
    public record ObjectRow(String objectId, String dataClass, String logicalName, String mediaType,
                            String tenantScopeSha256, long plaintextSizeBytes, String plaintextSha256,
                            long ciphertextSizeBytes, String ciphertextSha256, String keyVersion,
                            String nonceBase64, String objectKey) { }

    /** @param drillId 演练ULID @param backupId 备份ULID @param requestSha256 请求摘要 @param startedAt 开始 @param endedAt 结束 @param rpoSeconds RPO秒 @param rtoSeconds RTO秒 @param state 状态 @param evidenceSha256 证据摘要 */
    public record DrillRow(String drillId, String backupId, String requestSha256, Instant startedAt,
                           Instant endedAt, long rpoSeconds, long rtoSeconds, String state,
                           String evidenceSha256) { }

    /** @param checkCode 校验码 @param result 结果 @param evidenceSha256 证据摘要 */
    public record CheckRow(String checkCode, String result, String evidenceSha256) { }
}
