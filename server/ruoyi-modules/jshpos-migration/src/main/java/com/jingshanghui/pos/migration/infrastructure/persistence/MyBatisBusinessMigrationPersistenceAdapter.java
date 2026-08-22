package com.jingshanghui.pos.migration.infrastructure.persistence;

import com.jingshanghui.pos.migration.application.port.BusinessMigrationPersistencePort;
import com.jingshanghui.pos.migration.infrastructure.persistence.mapper.BusinessMigrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Migration 应用层与 MyBatis Mapper 之间的持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisBusinessMigrationPersistenceAdapter implements BusinessMigrationPersistencePort {
    private final BusinessMigrationMapper mapper;

    @Override
    public BatchRecord findBatch(String tenantId, String batchId) {
        return mapper.findBatch(tenantId, batchId);
    }

    @Override
    public BatchRecord findBatchByIdempotency(String tenantId, String idempotencyKey) {
        return mapper.findBatchByIdempotency(tenantId, idempotencyKey);
    }

    @Override public void insertBatch(BatchWrite value) { mapper.insertBatch(value); }
    @Override public int changeBatchState(StateChange value) { return mapper.changeBatchState(value); }
    @Override public void appendStateEvent(StateEventWrite value) { mapper.appendStateEvent(value); }
    @Override public void appendAudit(AuditWrite value) { mapper.appendAudit(value); }
    @Override public void appendOutbox(OutboxWrite value) { mapper.appendOutbox(value); }
    @Override public void insertFile(FileWrite value) { mapper.insertFile(value); }

    @Override
    public List<FileRecord> listFiles(String tenantId, String batchId) {
        return mapper.listFiles(tenantId, batchId);
    }

    @Override public void insertPreflightError(PreflightErrorWrite value) { mapper.insertPreflightError(value); }

    @Override
    public List<PreflightErrorRecord> listPreflightErrors(String tenantId, String batchId) {
        return mapper.listPreflightErrors(tenantId, batchId);
    }

    @Override
    public List<PreflightErrorRecord> listPreflightErrorsPage(String tenantId, String batchId,
                                                              int offset, int limit) {
        return mapper.listPreflightErrorsPage(tenantId, batchId, offset, limit);
    }

    @Override
    public int countPreflightErrors(String tenantId, String batchId) {
        return mapper.countPreflightErrors(tenantId, batchId);
    }

    @Override public void insertStagingRow(StagingWrite value) { mapper.insertStagingRow(value); }

    @Override
    public StagingRecord findStagingRow(String tenantId, String rowId) {
        return mapper.findStagingRow(tenantId, rowId);
    }

    @Override
    public List<StagingRecord> listStagingRows(String tenantId, String batchId) {
        return mapper.listStagingRows(tenantId, batchId);
    }

    @Override public int countStagingRows(String tenantId, String batchId) { return mapper.countStagingRows(tenantId, batchId); }
    @Override public void insertApproval(ApprovalWrite value) { mapper.insertApproval(value); }
    @Override public int countApprovals(String tenantId, String batchId) { return mapper.countApprovals(tenantId, batchId); }

    @Override
    public boolean hasApproval(String tenantId, String batchId, Long userId) {
        return mapper.hasApproval(tenantId, batchId, userId);
    }

    @Override
    public ApprovalRecord findApprovalByIdempotency(String tenantId, String batchId, String idempotencyKey) {
        return mapper.findApprovalByIdempotency(tenantId, batchId, idempotencyKey);
    }

    @Override
    public CheckpointRecord findCheckpoint(String tenantId, String batchId, String rowId) {
        return mapper.findCheckpoint(tenantId, batchId, rowId);
    }

    @Override public void insertCheckpoint(CheckpointWrite value) { mapper.insertCheckpoint(value); }

    @Override
    public int countAppliedCheckpoints(String tenantId, String batchId) {
        return mapper.countAppliedCheckpoints(tenantId, batchId);
    }

    @Override
    public List<CheckpointDigest> listCheckpointDigests(String tenantId, String batchId) {
        return mapper.listCheckpointDigests(tenantId, batchId);
    }

    @Override
    public List<CheckpointSummary> listCheckpointSummaries(String tenantId, String batchId) {
        Map<String, List<CheckpointDigest>> groups = new LinkedHashMap<>();
        for (CheckpointDigest value : listCheckpointDigests(tenantId, batchId)) {
            groups.computeIfAbsent(value.ownerType() + "\u0000" + value.dataType(), ignored -> new ArrayList<>())
                .add(value);
        }
        List<CheckpointSummary> result = new ArrayList<>();
        for (List<CheckpointDigest> values : groups.values()) {
            int applied = (int) values.stream().filter(value -> "APPLIED".equals(value.state())).count();
            int failed = values.size() - applied;
            CheckpointDigest first = values.get(0);
            result.add(new CheckpointSummary(first.ownerType(), first.dataType(), applied, failed, digest(values),
                failed == 0 ? "APPLIED" : "FAILED"));
        }
        return List.copyOf(result);
    }

    @Override public void insertReconciliation(ReconciliationWrite value) { mapper.insertReconciliation(value); }

    @Override
    public ReconciliationRecord latestReconciliation(String tenantId, String batchId) {
        return mapper.latestReconciliation(tenantId, batchId);
    }

    @Override
    public int clearStaging(String tenantId, String batchId, LocalDateTime at) {
        return mapper.clearStaging(tenantId, batchId, at);
    }

    /** 长度前缀流式摘要不依赖 MySQL GROUP_CONCAT，10 万行以上也不会静默截断。 */
    private String digest(List<CheckpointDigest> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CheckpointDigest value : values) {
                update(digest, value.rowId());
                update(digest, value.ownerType());
                update(digest, value.dataType());
                update(digest, value.requestSha256());
                update(digest, value.resultSha256());
                update(digest, value.state());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
