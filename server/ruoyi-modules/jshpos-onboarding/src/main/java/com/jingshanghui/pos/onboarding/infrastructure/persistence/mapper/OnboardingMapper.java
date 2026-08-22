package com.jingshanghui.pos.onboarding.infrastructure.persistence.mapper;

import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.ApprovalRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CheckpointRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.CommandRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.PlanRecord;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.SnapshotItem;
import com.jingshanghui.pos.onboarding.application.port.OnboardingPersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 门店开通 Owner 的 XML Mapper；所有方法显式携带可信租户。 */
public interface OnboardingMapper {
    PlanRecord findPlan(@Param("tenantId") String tenantId, @Param("planId") String planId);
    PlanRecord lockPlan(@Param("tenantId") String tenantId, @Param("planId") String planId);
    PlanRecord findPlanByIdempotency(@Param("tenantId") String tenantId,
                                     @Param("idempotencyKey") String idempotencyKey);
    void insertPlan(PlanWrite value);
    int changeState(StateChange value);
    void insertSnapshot(SnapshotWrite value);
    List<SnapshotItem> listSnapshot(@Param("tenantId") String tenantId, @Param("planId") String planId);
    void insertApproval(ApprovalWrite value);
    List<ApprovalRecord> listApprovals(@Param("tenantId") String tenantId, @Param("planId") String planId);
    void insertCheckpoint(CheckpointWrite value);
    CheckpointRecord findCheckpoint(@Param("tenantId") String tenantId, @Param("planId") String planId,
                                    @Param("stepCode") String stepCode);
    List<CheckpointRecord> listCheckpoints(@Param("tenantId") String tenantId, @Param("planId") String planId);
    int nextCheckRun(@Param("tenantId") String tenantId, @Param("planId") String planId);
    void insertCheck(CheckWrite value);
    List<CheckRecord> listLatestChecks(@Param("tenantId") String tenantId, @Param("planId") String planId);
    CommandRecord findCommand(@Param("tenantId") String tenantId, @Param("operation") String operation,
                              @Param("idempotencyKey") String idempotencyKey);
    void insertCommand(CommandWrite value);
    void appendState(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);
}
