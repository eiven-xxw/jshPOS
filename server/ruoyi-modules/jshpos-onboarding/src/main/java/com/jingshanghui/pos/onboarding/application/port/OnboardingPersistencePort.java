package com.jingshanghui.pos.onboarding.application.port;

import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.ApprovalRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckpointRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CommandRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.PlanRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.SnapshotItem;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;

import java.time.LocalDateTime;
import java.util.List;

/** Onboarding 应用层唯一持久化端口，不暴露通用更新或删除。 */
public interface OnboardingPersistencePort {
    PlanRecord findPlan(String tenantId, String planId);
    PlanRecord lockPlan(String tenantId, String planId);
    PlanRecord findPlanByIdempotency(String tenantId, String idempotencyKey);
    void insertPlan(PlanWrite value);
    int changeState(StateChange value);
    void insertSnapshot(SnapshotWrite value);
    List<SnapshotItem> listSnapshot(String tenantId, String planId);
    void insertApproval(ApprovalWrite value);
    List<ApprovalRecord> listApprovals(String tenantId, String planId);
    void insertCheckpoint(CheckpointWrite value);
    CheckpointRecord findCheckpoint(String tenantId, String planId, String stepCode);
    List<CheckpointRecord> listCheckpoints(String tenantId, String planId);
    int nextCheckRun(String tenantId, String planId);
    void insertCheck(CheckWrite value);
    List<CheckRecord> listLatestChecks(String tenantId, String planId);
    CommandRecord findCommand(String tenantId, String operation, String idempotencyKey);
    void insertCommand(CommandWrite value);
    void appendState(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);

    record PlanWrite(String planId, String tenantId, Long sourceStoreId, Long targetStoreId,
                     Long templateId, Long templateVersionId, Integer sourceStoreVersion,
                     Integer targetStoreVersion, Integer templateVersionNo, String templateSha256,
                     String industry, String snapshotSha256, String state, String idempotencyKey,
                     String requestSha256, Long creatorUserId, LocalDateTime at) {
    }
    record StateChange(String tenantId, String planId, String fromState, String toState,
                       Integer expectedVersion, Integer checkRun, LocalDateTime at) {
    }
    record SnapshotWrite(String snapshotId, String tenantId, String planId, String itemKey,
                         String contentJson, String contentSha256, LocalDateTime at) {
    }
    record ApprovalWrite(String approvalId, String tenantId, String planId, Long approverUserId,
                         String reason, String idempotencyKey, String requestSha256, LocalDateTime at) {
    }
    record CheckpointWrite(String checkpointId, String tenantId, String planId, String stepCode,
                           String idempotencyKey, String requestSha256, String resultSha256,
                           String state, LocalDateTime at) {
    }
    record CheckWrite(String checkId, String tenantId, String planId, Integer runNo, String checkCode,
                      String ownerType, boolean required, boolean external, String factVersion,
                      String factSha256, CheckStatus status, String maskedMessage, LocalDateTime at) {
    }
    record CommandWrite(String commandId, String tenantId, String planId, String operation,
                        String idempotencyKey, String requestSha256, String resultState,
                        String resultSha256, LocalDateTime at) {
    }
    record StateEventWrite(String eventId, String tenantId, String planId, String fromState,
                           String toState, String requestSha256, String correlationId,
                           Long actorUserId, LocalDateTime at) {
    }
    record AuditWrite(String auditId, String tenantId, String planId, String actionCode,
                      String result, String requestSha256, String correlationId,
                      Long actorUserId, String maskedSummary, LocalDateTime at) {
    }
    record OutboxWrite(String outboxId, String tenantId, String planId, String eventType,
                       Integer schemaVersion, String payloadJson, String payloadSha256,
                       String correlationId, LocalDateTime at) {
    }
}
