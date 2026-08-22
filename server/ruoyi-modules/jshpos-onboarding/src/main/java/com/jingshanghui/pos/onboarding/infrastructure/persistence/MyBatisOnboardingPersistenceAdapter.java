package com.jingshanghui.pos.onboarding.infrastructure.persistence;

import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.*;
import com.jingshanghui.pos.onboarding.application.port.OnboardingPersistencePort;
import com.jingshanghui.pos.onboarding.infrastructure.persistence.mapper.OnboardingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Onboarding 应用层到 MyBatis XML 的持久化适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisOnboardingPersistenceAdapter implements OnboardingPersistencePort {
    private final OnboardingMapper mapper;
    @Override public PlanRecord findPlan(String tenantId, String planId) { return mapper.findPlan(tenantId, planId); }
    @Override public PlanRecord lockPlan(String tenantId, String planId) { return mapper.lockPlan(tenantId, planId); }
    @Override public PlanRecord findPlanByIdempotency(String tenantId, String key) { return mapper.findPlanByIdempotency(tenantId, key); }
    @Override public void insertPlan(PlanWrite value) { mapper.insertPlan(value); }
    @Override public int changeState(StateChange value) { return mapper.changeState(value); }
    @Override public void insertSnapshot(SnapshotWrite value) { mapper.insertSnapshot(value); }
    @Override public List<SnapshotItem> listSnapshot(String tenantId, String planId) { return mapper.listSnapshot(tenantId, planId); }
    @Override public void insertApproval(ApprovalWrite value) { mapper.insertApproval(value); }
    @Override public List<ApprovalRecord> listApprovals(String tenantId, String planId) { return mapper.listApprovals(tenantId, planId); }
    @Override public void insertCheckpoint(CheckpointWrite value) { mapper.insertCheckpoint(value); }
    @Override public CheckpointRecord findCheckpoint(String tenantId, String planId, String step) { return mapper.findCheckpoint(tenantId, planId, step); }
    @Override public List<CheckpointRecord> listCheckpoints(String tenantId, String planId) { return mapper.listCheckpoints(tenantId, planId); }
    @Override public int nextCheckRun(String tenantId, String planId) { return mapper.nextCheckRun(tenantId, planId); }
    @Override public void insertCheck(CheckWrite value) { mapper.insertCheck(value); }
    @Override public List<CheckRecord> listLatestChecks(String tenantId, String planId) { return mapper.listLatestChecks(tenantId, planId); }
    @Override public CommandRecord findCommand(String tenantId, String operation, String key) { return mapper.findCommand(tenantId, operation, key); }
    @Override public void insertCommand(CommandWrite value) { mapper.insertCommand(value); }
    @Override public void appendState(StateEventWrite value) { mapper.appendState(value); }
    @Override public void appendAudit(AuditWrite value) { mapper.appendAudit(value); }
    @Override public void appendOutbox(OutboxWrite value) { mapper.appendOutbox(value); }
}
