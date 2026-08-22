package com.jingshanghui.pos.onboarding.application.model;

import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckStatus;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** T2-ONB-001 应用命令、持久化投影和安全 REST 视图。 */
public final class OnboardingModels {
    private OnboardingModels() {
    }

    /** @param sourceStoreId 空表示纯行业模板开店。 */
    public record CreatePlan(Long sourceStoreId, Long targetStoreId, Long templateId, Long templateVersionId,
                             String idempotencyKey, String correlationId) {
    }

    public record PlanCommand(String planId, String idempotencyKey, String correlationId) {
    }

    public record ReasonCommand(String planId, String reason, String idempotencyKey, String correlationId) {
    }

    /** 数据库中的开店计划；租户只来自可信上下文。 */
    public record PlanRecord(String planId, String tenantId, Long sourceStoreId, Long targetStoreId,
                             Long templateId, Long templateVersionId, Integer sourceStoreVersion,
                             Integer targetStoreVersion, Integer templateVersionNo, String templateSha256,
                             String industry, String snapshotSha256, String state, String idempotencyKey,
                             String requestSha256, Long creatorUserId, Integer checkRun, Integer recordVersion,
                             LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record SnapshotItem(String snapshotId, String tenantId, String planId, String itemKey,
                               String contentJson, String contentSha256, LocalDateTime createdAt) {
    }

    public record ApprovalRecord(String approvalId, String tenantId, String planId, Long approverUserId,
                                 String reason, String idempotencyKey, String requestSha256,
                                 LocalDateTime approvedAt) {
    }

    public record CheckpointRecord(String checkpointId, String tenantId, String planId, String stepCode,
                                   String idempotencyKey, String requestSha256, String resultSha256,
                                   String state, LocalDateTime createdAt) {
    }

    public record CheckRecord(String checkId, String tenantId, String planId, Integer runNo, String checkCode,
                              String ownerType, boolean required, boolean external, String factVersion,
                              String factSha256, CheckStatus status, String maskedMessage,
                              LocalDateTime checkedAt) {
    }

    public record CommandRecord(String commandId, String tenantId, String planId, String operation,
                                String idempotencyKey, String requestSha256, String resultState,
                                String resultSha256, LocalDateTime createdAt) {
    }

    public record PlanDetail(PlanRecord plan, List<SnapshotItem> snapshot, List<ApprovalRecord> approvals,
                             List<CheckpointRecord> checkpoints, List<CheckRecord> checks) {
        public PlanDetail {
            snapshot = snapshot == null ? List.of() : List.copyOf(snapshot);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    public record CheckFact(String code, String ownerType, boolean required, boolean external,
                            String factVersion, String factSha256, CheckStatus status, String maskedMessage) {
    }

    public record OwnerSnapshot(Long sourceStoreId, Integer sourceStoreVersion, Long targetStoreId,
                                Integer targetStoreVersion, Long templateId, Long templateVersionId,
                                Integer templateVersionNo, String templateSha256, String industry,
                                Map<String, Object> items) {
        public OwnerSnapshot {
            items = items == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }
    }

    public record OwnerApplyResult(String stepCode, String resultSha256) {
    }

    public record OwnerOpenResult(Long storeId, String status, Integer version) {
    }
}
