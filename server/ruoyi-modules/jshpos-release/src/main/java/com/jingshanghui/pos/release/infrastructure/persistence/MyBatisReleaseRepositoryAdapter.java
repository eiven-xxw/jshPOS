package com.jingshanghui.pos.release.infrastructure.persistence;

import com.jingshanghui.pos.release.application.port.ReleasePorts.Repository;
import com.jingshanghui.pos.release.domain.ReleaseIdGenerator;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import com.jingshanghui.pos.release.infrastructure.persistence.ReleasePersistenceParams.*;
import com.jingshanghui.pos.release.infrastructure.persistence.mapper.ReleasePersistenceMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** MyBatis XML 发布治理适配器；状态条件更新与事件、审计在同一事务中提交。 */
@org.springframework.stereotype.Repository
public class MyBatisReleaseRepositoryAdapter implements Repository {
    private final ReleasePersistenceMapper mapper;
    private final ReleaseIdGenerator ids;

    public MyBatisReleaseRepositoryAdapter(ReleasePersistenceMapper mapper, ReleaseIdGenerator ids) {
        this.mapper = mapper; this.ids = ids;
    }

    @Override public Optional<Release> findRelease(String tenantId, String releaseId) {
        ReleaseRow row = mapper.selectRelease(tenantId, releaseId);
        if (row == null) return Optional.empty();
        CompatibilityWindow compatibility = new CompatibilityWindow(row.minAppVersion(), row.maxAppVersion(),
            row.minProtocolVersion(), row.maxProtocolVersion(), row.minSchemaVersion(), row.maxSchemaVersion(),
            row.minSystemVersion(), row.maxSystemVersion(), row.requiredCapabilitySha256());
        return Optional.of(new Release(row.releaseId(), row.tenantId(), ArtifactType.valueOf(row.artifactType()),
            row.releaseVersion(), Channel.valueOf(row.channelCode()), row.objectKey(), row.artifactSha256(),
            row.signatureBase64(), row.keyVersion(), row.buildCommit(), row.sbomSha256(), row.manifestSha256(),
            compatibility, Set.copyOf(mapper.selectScopes(tenantId, "RELEASE", releaseId)),
            ReleaseState.valueOf(row.state()), row.versionNo(), row.createdAt()));
    }

    @Override public Optional<Rollout> findRollout(String tenantId, String rolloutId) {
        RolloutRow row = mapper.selectRollout(tenantId, rolloutId);
        if (row == null) return Optional.empty();
        return Optional.of(new Rollout(row.rolloutId(), row.tenantId(), row.releaseId(),
            Set.copyOf(mapper.selectScopes(tenantId, "ROLLOUT", rolloutId)), row.canaryPercent(),
            RolloutState.valueOf(row.state()), row.versionNo(), row.createdAt()));
    }

    @Override public Optional<TerminalTask> findTask(String tenantId, String taskId) {
        TaskRow row = mapper.selectTask(tenantId, taskId);
        return row == null ? Optional.empty() : Optional.of(new TerminalTask(row.taskId(), row.tenantId(),
            row.rolloutId(), row.releaseId(), row.deviceId(), row.storeId(), TaskState.valueOf(row.state()),
            row.lastEvidenceSha256(), row.versionNo(), row.createdAt()));
    }

    @Override public Optional<CommandResult> findCommand(String tenantId, String commandType, String idempotencyKey) {
        CommandRow row = mapper.selectCommand(tenantId, commandType, idempotencyKey);
        return row == null ? Optional.empty() : Optional.of(new CommandResult(row.requestSha256(), row.aggregateId(), row.resultCode()));
    }

    @Override @Transactional public void insertRelease(Release value, String requestSha256, long actorId, String correlationId) {
        CompatibilityWindow c = value.compatibility();
        one(mapper.insertRelease(new InsertRelease(value.releaseId(), value.tenantId(), value.artifactType().name(),
            value.version(), value.channel().name(), value.objectKey(), value.artifactSha256(), value.signatureBase64(),
            value.keyVersion(), value.buildCommit(), value.sbomSha256(), value.manifestSha256(), c.minAppVersion(),
            c.maxAppVersion(), c.minProtocolVersion(), c.maxProtocolVersion(), c.minSchemaVersion(), c.maxSchemaVersion(),
            c.minSystemVersion(), c.maxSystemVersion(), c.requiredCapabilitySha256(), requestSha256, actorId,
            correlationId)), "UPG-DB-001: 发布草稿登记失败");
        for (Long storeId : value.targetStoreIds()) one(mapper.insertScope(new InsertScope(ids.next(), value.tenantId(),
            "RELEASE", value.releaseId(), storeId)), "UPG-DB-002: 发布范围登记失败");
        append(value.tenantId(), "RELEASE", value.releaseId(), "RELEASE_CREATED", null, "DRAFT",
            requestSha256, actorId, correlationId);
    }

    @Override @Transactional public void transitionRelease(String tenantId, String releaseId, ReleaseState from,
        ReleaseState to, long expectedVersion, long actorId, String correlationId, String evidenceSha256) {
        one(mapper.transitionRelease(new TransitionRelease(tenantId, releaseId, from.name(), to.name(), expectedVersion,
            actorId, correlationId)), "UPG-DB-003: 发布物状态竞争");
        append(tenantId, "RELEASE", releaseId, "RELEASE_" + to.name(), from.name(), to.name(), evidenceSha256,
            actorId, correlationId);
    }

    @Override @Transactional public void insertRollout(Rollout value, String requestSha256, long actorId, String correlationId) {
        one(mapper.insertRollout(new InsertRollout(value.rolloutId(), value.tenantId(), value.releaseId(),
            value.canaryPercent(), requestSha256, actorId, correlationId)), "UPG-DB-004: 灰度批次登记失败");
        for (Long storeId : value.targetStoreIds()) one(mapper.insertScope(new InsertScope(ids.next(), value.tenantId(),
            "ROLLOUT", value.rolloutId(), storeId)), "UPG-DB-005: 灰度范围登记失败");
        append(value.tenantId(), "ROLLOUT", value.rolloutId(), "ROLLOUT_CREATED", null, "PLANNED",
            requestSha256, actorId, correlationId);
    }

    @Override @Transactional public void transitionRollout(String tenantId, String rolloutId, RolloutState from,
        RolloutState to, long expectedVersion, long actorId, String correlationId, String evidenceSha256) {
        one(mapper.transitionRollout(new TransitionRollout(tenantId, rolloutId, from.name(), to.name(), expectedVersion,
            actorId, correlationId)), "UPG-DB-006: 灰度状态竞争");
        append(tenantId, "ROLLOUT", rolloutId, "ROLLOUT_" + to.name(), from.name(), to.name(), evidenceSha256,
            actorId, correlationId);
    }

    @Override @Transactional public void insertTask(TerminalTask value, String requestSha256, long actorId, String correlationId) {
        one(mapper.insertTask(new InsertTask(value.taskId(), value.tenantId(), value.rolloutId(), value.releaseId(),
            value.deviceId(), value.storeId(), requestSha256, value.lastEvidenceSha256(), actorId, correlationId)),
            "UPG-DB-007: 终端任务登记失败");
        append(value.tenantId(), "TASK", value.taskId(), "TASK_CREATED", null, "PLANNED", requestSha256,
            actorId, correlationId);
    }

    @Override @Transactional public void transitionTask(String tenantId, String taskId, TaskState from, TaskState to,
        long expectedVersion, String evidenceSha256, long actorId, String correlationId) {
        one(mapper.transitionTask(new TransitionTask(tenantId, taskId, from.name(), to.name(), expectedVersion,
            evidenceSha256, actorId, correlationId)), "UPG-DB-008: 任务状态竞争");
        append(tenantId, "TASK", taskId, "TASK_" + to.name(), from.name(), to.name(), evidenceSha256,
            actorId, correlationId);
    }

    @Override public TaskSummary summarizeTasks(String tenantId, String rolloutId) {
        SummaryRow row = mapper.summarizeTasks(tenantId, rolloutId);
        return row == null ? new TaskSummary(0, 0, 0) : new TaskSummary(row.succeeded(), row.active(), row.failed());
    }

    @Override public void appendCommand(String tenantId, String commandType, String idempotencyKey,
        String requestSha256, String aggregateId, String resultCode, long actorId, Instant occurredAt) {
        one(mapper.insertCommand(new InsertCommand(ids.next(), tenantId, commandType, idempotencyKey,
            requestSha256, aggregateId, resultCode, actorId, occurredAt)), "UPG-DB-009: 幂等结果登记失败");
    }

    private void append(String tenantId, String aggregateType, String aggregateId, String action,
                        String before, String after, String evidence, long actorId, String correlationId) {
        one(mapper.insertEvent(new InsertEvent(ids.next(), tenantId, aggregateType, aggregateId, action, before,
            after, evidence, correlationId)), "UPG-DB-010: 发布事件登记失败");
        one(mapper.insertAudit(new InsertAudit(ids.next(), tenantId, aggregateType, aggregateId, action, before,
            after, evidence, actorId, correlationId)), "UPG-DB-011: 发布审计登记失败");
    }
    private static void one(int count, String message) { if (count != 1) throw new ServiceException(message, 409); }
}
