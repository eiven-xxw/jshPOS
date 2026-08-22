package com.jingshanghui.pos.migration.application.port;

import java.time.LocalDateTime;
import java.util.List;

/** Migration Owner 持久化端口；其他 Owner 不得依赖本端口或 mig_* 表。 */
public interface BusinessMigrationPersistencePort {
    BatchRecord findBatch(String tenantId, String batchId);
    BatchRecord findBatchByIdempotency(String tenantId, String idempotencyKey);
    void insertBatch(BatchWrite value);
    int changeBatchState(StateChange value);
    void appendStateEvent(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);
    void insertFile(FileWrite value);
    List<FileRecord> listFiles(String tenantId, String batchId);
    void insertPreflightError(PreflightErrorWrite value);
    List<PreflightErrorRecord> listPreflightErrors(String tenantId, String batchId);
    List<PreflightErrorRecord> listPreflightErrorsPage(String tenantId, String batchId, int offset, int limit);
    int countPreflightErrors(String tenantId, String batchId);
    void insertStagingRow(StagingWrite value);
    StagingRecord findStagingRow(String tenantId, String rowId);
    List<StagingRecord> listStagingRows(String tenantId, String batchId);
    int countStagingRows(String tenantId, String batchId);
    void insertApproval(ApprovalWrite value);
    int countApprovals(String tenantId, String batchId);
    boolean hasApproval(String tenantId, String batchId, Long userId);
    ApprovalRecord findApprovalByIdempotency(String tenantId, String batchId, String idempotencyKey);
    CheckpointRecord findCheckpoint(String tenantId, String batchId, String rowId);
    void insertCheckpoint(CheckpointWrite value);
    List<CheckpointSummary> listCheckpointSummaries(String tenantId, String batchId);
    List<CheckpointDigest> listCheckpointDigests(String tenantId, String batchId);
    int countAppliedCheckpoints(String tenantId, String batchId);
    void insertReconciliation(ReconciliationWrite value);
    ReconciliationRecord latestReconciliation(String tenantId, String batchId);
    int clearStaging(String tenantId, String batchId, LocalDateTime at);

    /**
     * 批次持久化投影。
     * @param batchId 批次 ULID
     * @param tenantId 可信上下文租户
     * @param requestedTypes 必需资料类型 JSON
     * @param state 当前状态
     * @param idempotencyKey 创建幂等键
     * @param requestSha256 创建请求摘要
     * @param correlationId 创建关联标识
     * @param version 乐观并发版本
     * @param createdAt UTC 创建时间
     */
    record BatchRecord(String batchId, String tenantId, String requestedTypes, String state,
                       String idempotencyKey, String requestSha256, String correlationId,
                       int version, LocalDateTime createdAt) { }
    /**
     * 新建批次写入参数。
     * @param batchId 批次 ULID
     * @param tenantId 可信上下文租户
     * @param requestedTypes 必需资料类型 JSON
     * @param state 初始状态
     * @param idempotencyKey 创建幂等键
     * @param requestSha256 创建请求摘要
     * @param correlationId 全链路关联标识
     * @param creatorUserId 创建员工主键
     * @param createdAt UTC 创建时间
     */
    record BatchWrite(String batchId, String tenantId, String requestedTypes, String state,
                      String idempotencyKey, String requestSha256, String correlationId,
                      Long creatorUserId, LocalDateTime createdAt) { }
    /**
     * 带原状态与版本条件的批次迁移参数。
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param fromState 期望原状态
     * @param toState 目标状态
     * @param expectedVersion 期望乐观版本
     * @param at UTC 变更时间
     */
    record StateChange(String tenantId, String batchId, String fromState, String toState,
                       int expectedVersion, LocalDateTime at) { }
    /**
     * 只追加状态事件参数。
     * @param eventId 状态事件 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param fromState 原状态
     * @param toState 目标状态
     * @param batchVersion 迁移后批次版本
     * @param actorUserId 操作员工主键
     * @param correlationId 全链路关联标识
     * @param at UTC 发生时间
     */
    record StateEventWrite(String eventId, String tenantId, String batchId, String fromState,
                           String toState, int batchVersion, Long actorUserId,
                           String correlationId, LocalDateTime at) { }
    /**
     * 只追加迁移审计参数。
     * @param auditId 审计 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param action 动作代码
     * @param actorUserId 操作员工主键
     * @param summarySha256 脱敏操作摘要
     * @param correlationId 全链路关联标识
     * @param at UTC 发生时间
     */
    record AuditWrite(String auditId, String tenantId, String batchId, String action,
                      Long actorUserId, String summarySha256, String correlationId, LocalDateTime at) { }
    /**
     * 迁移状态 Outbox 写入参数。
     * @param outboxId Outbox ULID
     * @param tenantId 可信上下文租户
     * @param batchId 聚合批次 ULID
     * @param eventType 版本化事件类型
     * @param aggregateVersion 批次版本
     * @param payloadJson 规范事件 JSON
     * @param payloadSha256 事件内容摘要
     * @param correlationId 全链路关联标识
     * @param at UTC 可投递时间
     */
    record OutboxWrite(String outboxId, String tenantId, String batchId, String eventType,
                       int aggregateVersion, String payloadJson, String payloadSha256,
                       String correlationId, LocalDateTime at) { }
    /**
     * 不包含原文件字节的文件登记参数。
     * @param fileId 文件登记 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param dataType 资料类型
     * @param mappingVersion 冻结映射版本
     * @param sourceSha256 原文件 SHA-256
     * @param safeFilename 安全逻辑文件名
     * @param charset CSV 字符集或 XLSX 标识
     * @param rowCount 有效行数
     * @param errorCount 错误数
     * @param state 文件预检状态
     * @param sourceSystem 来源系统说明
     * @param custodyReference 受控保管引用
     * @param fileBytes 原文件字节数
     * @param uploaderUserId 上传员工主键
     * @param at UTC 登记时间
     */
    record FileWrite(String fileId, String tenantId, String batchId, String dataType,
                     String mappingVersion, String sourceSha256, String safeFilename, String charset,
                     int rowCount, int errorCount, String state, String sourceSystem,
                     String custodyReference, long fileBytes, Long uploaderUserId, LocalDateTime at) { }
    /**
     * 文件登记读取投影。
     * @param fileId 文件登记 ULID
     * @param batchId 批次 ULID
     * @param dataType 资料类型
     * @param mappingVersion 冻结映射版本
     * @param sourceSha256 原文件摘要
     * @param safeFilename 安全逻辑文件名
     * @param charset CSV 字符集或 XLSX 标识
     * @param rowCount 有效行数
     * @param errorCount 错误数
     * @param state 文件状态
     * @param sourceSystem 来源系统说明
     * @param custodyReference 受控保管引用
     */
    record FileRecord(String fileId, String batchId, String dataType, String mappingVersion,
                      String sourceSha256, String safeFilename, String charset, int rowCount,
                      int errorCount, String state, String sourceSystem, String custodyReference) { }
    /**
     * 只追加脱敏预检错误参数。
     * @param errorId 错误 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param fileId 文件登记 ULID
     * @param dataType 资料类型
     * @param rowNumber 文件行号
     * @param fieldName 字段名
     * @param errorCode 稳定错误码
     * @param maskedMessage 脱敏错误说明
     * @param at UTC 发生时间
     */
    record PreflightErrorWrite(String errorId, String tenantId, String batchId, String fileId,
                               String dataType, int rowNumber, String fieldName, String errorCode,
                               String maskedMessage, LocalDateTime at) { }
    /**
     * 脱敏预检错误读取投影。
     * @param errorId 错误 ULID
     * @param dataType 资料类型
     * @param rowNumber 文件行号
     * @param fieldName 字段名
     * @param errorCode 稳定错误码
     * @param maskedMessage 脱敏错误说明
     */
    record PreflightErrorRecord(String errorId, String dataType, int rowNumber, String fieldName,
                                String errorCode, String maskedMessage) { }
    /**
     * 加密 staging 行写入参数。
     * @param rowId 迁移行 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param fileId 文件登记 ULID
     * @param dataType 资料类型
     * @param rowNumber 原文件行号
     * @param rowSha256 规范行摘要
     * @param cipherText AES-256-GCM 密文
     * @param keyVersion 密钥版本标识
     * @param contentHmac 防替换摘要
     * @param expiresAt UTC 清理到期时间
     * @param at UTC 创建时间
     */
    record StagingWrite(String rowId, String tenantId, String batchId, String fileId, String dataType,
                        int rowNumber, String rowSha256, String cipherText, String keyVersion,
                        String contentHmac, LocalDateTime expiresAt, LocalDateTime at) { }
    /**
     * 加密 staging 行读取投影。
     * @param rowId 迁移行 ULID
     * @param batchId 批次 ULID
     * @param fileId 文件登记 ULID
     * @param dataType 资料类型
     * @param rowNumber 原文件行号
     * @param rowSha256 规范行摘要
     * @param cipherText 加密内容
     * @param keyVersion 密钥版本标识
     * @param contentHmac 防替换摘要
     * @param state staging 状态
     */
    record StagingRecord(String rowId, String batchId, String fileId, String dataType, int rowNumber,
                         String rowSha256, String cipherText, String keyVersion, String contentHmac,
                         String state) { }
    /**
     * 只追加双人审批参数。
     * @param approvalId 审批 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param approverUserId 审批员工主键
     * @param reasonSha256 审批原因摘要
     * @param idempotencyKey 审批幂等键
     * @param correlationId 全链路关联标识
     * @param at UTC 审批时间
     */
    record ApprovalWrite(String approvalId, String tenantId, String batchId, Long approverUserId,
                         String reasonSha256, String idempotencyKey, String correlationId, LocalDateTime at) { }
    /**
     * 审批幂等读取投影。
     * @param approvalId 审批 ULID
     * @param batchId 批次 ULID
     * @param approverUserId 审批员工主键
     * @param reasonSha256 审批原因摘要
     * @param idempotencyKey 审批幂等键
     */
    record ApprovalRecord(String approvalId, String batchId, Long approverUserId,
                          String reasonSha256, String idempotencyKey) { }
    /**
     * 单行 Owner 检查点读取投影。
     * @param checkpointId 检查点 ULID
     * @param batchId 批次 ULID
     * @param rowId 冻结行 ULID
     * @param ownerType 数据 Owner
     * @param dataType 资料类型
     * @param commandId Owner 稳定命令标识
     * @param requestSha256 Owner 请求摘要
     * @param resultSha256 Owner 结果摘要
     * @param state 检查点状态
     */
    record CheckpointRecord(String checkpointId, String batchId, String rowId, String ownerType,
                            String dataType, String commandId, String requestSha256,
                            String resultSha256, String state) { }
    /**
     * 只追加 Owner 检查点参数。
     * @param checkpointId 检查点 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param rowId 冻结行 ULID
     * @param ownerType 数据 Owner
     * @param dataType 资料类型
     * @param commandId Owner 稳定命令标识
     * @param requestSha256 Owner 请求摘要
     * @param resultSha256 Owner 结果摘要
     * @param state 检查点状态
     * @param correlationId 全链路关联标识
     * @param at UTC 创建时间
     */
    record CheckpointWrite(String checkpointId, String tenantId, String batchId, String rowId,
                           String ownerType, String dataType, String commandId, String requestSha256,
                           String resultSha256, String state, String correlationId, LocalDateTime at) { }
    /**
     * Owner 检查点聚合投影。
     * @param ownerType 数据 Owner
     * @param dataType 资料类型
     * @param appliedCount 已应用行数
     * @param failedCount 失败行数
     * @param resultSha256 完整有序结果摘要
     * @param state 聚合状态
     */
    record CheckpointSummary(String ownerType, String dataType, int appliedCount, int failedCount,
                             String resultSha256, String state) { }
    /**
     * 流式对账摘要的有序检查点输入。
     * @param rowId 冻结行 ULID
     * @param ownerType 数据 Owner
     * @param dataType 资料类型
     * @param requestSha256 Owner 请求摘要
     * @param resultSha256 Owner 结果摘要
     * @param state 检查点状态
     */
    record CheckpointDigest(String rowId, String ownerType, String dataType, String requestSha256,
                            String resultSha256, String state) { }
    /**
     * 只追加对账事实参数。
     * @param reconciliationId 对账 ULID
     * @param tenantId 可信上下文租户
     * @param batchId 批次 ULID
     * @param expectedRows staging 期望行数
     * @param appliedRows Owner 检查点行数
     * @param differenceCount 差异数量
     * @param resultSha256 完整对账结果摘要
     * @param state 对账状态
     * @param actorUserId 对账员工主键
     * @param correlationId 全链路关联标识
     * @param at UTC 对账时间
     */
    record ReconciliationWrite(String reconciliationId, String tenantId, String batchId,
                               int expectedRows, int appliedRows, int differenceCount,
                               String resultSha256, String state, Long actorUserId,
                               String correlationId, LocalDateTime at) { }
    /**
     * 最新对账读取投影。
     * @param reconciliationId 对账 ULID
     * @param batchId 批次 ULID
     * @param expectedRows staging 期望行数
     * @param appliedRows Owner 检查点行数
     * @param differenceCount 差异数量
     * @param resultSha256 完整对账结果摘要
     * @param state 对账状态
     */
    record ReconciliationRecord(String reconciliationId, String batchId, int expectedRows,
                                int appliedRows, int differenceCount, String resultSha256, String state) { }
}
