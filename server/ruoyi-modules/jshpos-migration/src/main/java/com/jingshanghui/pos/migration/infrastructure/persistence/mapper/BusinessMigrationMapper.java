package com.jingshanghui.pos.migration.infrastructure.persistence.mapper;

import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Migration Owner 的受控写入、只追加事实和投影查询 Mapper。
 *
 * <p>业务 SQL 全部位于同 namespace XML；接口不继承 BaseMapper，避免向状态机、
 * staging、审计和 Outbox 暴露通用更新或删除能力。每个方法都显式接收可信 tenant_id。</p>
 */
public interface BusinessMigrationMapper {
    BatchRecord findBatch(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    BatchRecord findBatchByIdempotency(@Param("tenantId") String tenantId, @Param("key") String key);
    int insertBatch(BatchWrite value);
    int changeBatchState(StateChange value);
    int appendStateEvent(StateEventWrite value);
    int appendAudit(AuditWrite value);
    int appendOutbox(OutboxWrite value);
    int insertFile(FileWrite value);
    List<FileRecord> listFiles(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    int insertPreflightError(PreflightErrorWrite value);
    List<PreflightErrorRecord> listPreflightErrors(@Param("tenantId") String tenantId,
                                                   @Param("batchId") String batchId);
    List<PreflightErrorRecord> listPreflightErrorsPage(@Param("tenantId") String tenantId,
                                                       @Param("batchId") String batchId,
                                                       @Param("offset") int offset,
                                                       @Param("limit") int limit);
    int countPreflightErrors(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    int insertStagingRow(StagingWrite value);
    StagingRecord findStagingRow(@Param("tenantId") String tenantId, @Param("rowId") String rowId);
    List<StagingRecord> listStagingRows(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    int countStagingRows(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    int insertApproval(ApprovalWrite value);
    int countApprovals(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    boolean hasApproval(@Param("tenantId") String tenantId, @Param("batchId") String batchId,
                        @Param("userId") Long userId);
    ApprovalRecord findApprovalByIdempotency(@Param("tenantId") String tenantId,
                                              @Param("batchId") String batchId,
                                              @Param("key") String key);
    CheckpointRecord findCheckpoint(@Param("tenantId") String tenantId, @Param("batchId") String batchId,
                                    @Param("rowId") String rowId);
    int insertCheckpoint(CheckpointWrite value);
    List<CheckpointDigest> listCheckpointDigests(@Param("tenantId") String tenantId,
                                                 @Param("batchId") String batchId);
    int countAppliedCheckpoints(@Param("tenantId") String tenantId, @Param("batchId") String batchId);
    int insertReconciliation(ReconciliationWrite value);
    ReconciliationRecord latestReconciliation(@Param("tenantId") String tenantId,
                                               @Param("batchId") String batchId);
    int clearStaging(@Param("tenantId") String tenantId, @Param("batchId") String batchId,
                     @Param("at") LocalDateTime at);
}
