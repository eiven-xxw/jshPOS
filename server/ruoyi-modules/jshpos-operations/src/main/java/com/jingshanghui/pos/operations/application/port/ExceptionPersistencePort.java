package com.jingshanghui.pos.operations.application.port;

import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;

import java.time.LocalDateTime;
import java.util.List;

/** 异常中心持久化内层端口；只暴露具名受控写入和只读投影。 */
public interface ExceptionPersistencePort {
    CaseRecord find(String tenantId, String caseId);
    CaseRecord lock(String tenantId, String caseId);
    CaseRecord findByDedup(String tenantId, Long storeId, String dedupKey);
    ObservationRecord findObservation(String tenantId, String ownerCode, String sourceEventId);
    List<CaseRecord> list(String tenantId, Long storeId, String state, String severity, int limit);
    void insertCase(CaseWrite value);
    int updateObservationHead(ObservationHead value);
    int changeState(StateChange value);
    void insertObservation(ObservationWrite value);
    void insertLeaseEvent(LeaseEventWrite value);
    void insertPlan(PlanWrite value);
    PlanRecord latestPlan(String tenantId, String caseId);
    void insertRepair(RepairWrite value);
    int updateRepairResult(RepairResultWrite value);
    void insertReview(ReviewWrite value);
    ReviewRecord latestApprovedReview(String tenantId, String caseId);
    void appendState(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);
    CommandRecord findCommand(String tenantId, String operation, String idempotencyKey);
    void insertCommand(CommandWrite value);
    List<ObservationRecord> listObservations(String tenantId, String caseId);
    List<PlanRecord> listPlans(String tenantId, String caseId);
    List<RepairRecord> listRepairs(String tenantId, String caseId);
    List<ReviewRecord> listReviews(String tenantId, String caseId);
    List<StateEventRecord> listStates(String tenantId, String caseId);
    List<AuditRecord> listAudits(String tenantId, String caseId);

    /**
     * 新异常案件受控写入参数。
     * @param caseId 案件 ULID
     * @param tenantId 可信租户
     * @param storeId 可信门店
     * @param sourceOwner 来源 Owner
     * @param sourceType 来源类型
     * @param sourceFactId 来源事实
     * @param dedupKey 去重键
     * @param severity 严重级别
     * @param state 初始状态
     * @param latestSourceEventId 来源事件
     * @param latestSourceSequence 来源序号
     * @param latestSourceSha256 来源摘要
     * @param firstObservedAt 首次观察时间
     * @param lastObservedAt 最近观察时间
     * @param createdAt 创建时间
     */
    record CaseWrite(String caseId, String tenantId, Long storeId, String sourceOwner,
                     String sourceType, String sourceFactId, String dedupKey, String severity,
                     String state, String latestSourceEventId, long latestSourceSequence,
                     String latestSourceSha256, LocalDateTime firstObservedAt,
                     LocalDateTime lastObservedAt, LocalDateTime createdAt) { }
    /**
     * 来源头乐观锁更新参数。
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param expectedSha256 原来源摘要
     * @param sourceEventId 新来源事件
     * @param sourceSequence 新来源序号
     * @param sourceSha256 新来源摘要
     * @param severity 新严重级别
     * @param targetState 观察后的案件状态
     * @param expectedVersion 原记录版本
     * @param observedAt Owner 观察时间
     */
    record ObservationHead(String tenantId, String caseId, String expectedSha256, String sourceEventId,
                           long sourceSequence, String sourceSha256, String severity, String targetState,
                           int expectedVersion, LocalDateTime observedAt) { }
    /**
     * 案件控制状态乐观锁更新参数。
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param fromState 原状态
     * @param toState 新状态
     * @param expectedVersion 原记录版本
     * @param assigneeUserId 认领人
     * @param leaseExpiresAt 租约到期时间
     * @param resolverUserId 处置人
     * @param reviewerUserId 复核人
     * @param changedAt 变更时间
     */
    record StateChange(String tenantId, String caseId, String fromState, String toState,
                       int expectedVersion, Long assigneeUserId, LocalDateTime leaseExpiresAt,
                       Long resolverUserId, Long reviewerUserId, LocalDateTime changedAt) { }
    /**
     * 只追加 Owner 观察写入参数。
     * @param observationId 观察 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param ownerCode 来源 Owner
     * @param sourceEventId 来源事件
     * @param sourceSequence 来源序号
     * @param sourceSha256 来源摘要
     * @param correlationId 关联标识
     * @param maskedSummary 去敏摘要
     * @param conflictFlag 冲突分类
     * @param observedAt 观察时间
     */
    record ObservationWrite(String observationId, String tenantId, String caseId, String ownerCode,
                            String sourceEventId, long sourceSequence, String sourceSha256,
                            String correlationId, String maskedSummary, String conflictFlag,
                            LocalDateTime observedAt) { }
    /**
     * 只追加认领租约事件写入参数。
     * @param leaseEventId 租约事件 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param eventType 租约事件类型
     * @param fromUserId 原认领人
     * @param toUserId 新认领人
     * @param expiresAt 租约到期时间
     * @param reasonSha256 原因摘要
     * @param actorUserId 操作者
     * @param occurredAt 发生时间
     */
    record LeaseEventWrite(String leaseEventId, String tenantId, String caseId, String eventType,
                           Long fromUserId, Long toUserId, LocalDateTime expiresAt,
                           String reasonSha256, Long actorUserId, LocalDateTime occurredAt) { }
    /**
     * 处置计划受控写入参数。
     * @param planId 计划 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param actionCode Owner 具名动作
     * @param summarySha256 去敏计划摘要
     * @param plannerUserId 计划人
     * @param state 计划状态
     * @param createdAt 创建时间
     */
    record PlanWrite(String planId, String tenantId, String caseId, String actionCode,
                     String summarySha256, Long plannerUserId, String state, LocalDateTime createdAt) { }
    /**
     * Owner 修复命令首次写入参数。
     * @param repairCommandId 修复命令 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param ownerCode 目标 Owner
     * @param actionCode 具名动作
     * @param requestSha256 请求摘要
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     * @param state 首次状态
     * @param requestedAt 请求时间
     */
    record RepairWrite(String repairCommandId, String tenantId, String caseId, String ownerCode,
                       String actionCode, String requestSha256, String idempotencyKey,
                       String correlationId, String state, LocalDateTime requestedAt) { }
    /**
     * Owner 修复结果条件更新参数。
     * @param tenantId 可信租户
     * @param repairCommandId 修复命令标识
     * @param fromState 期望原状态
     * @param toState Owner 观察状态
     * @param ownerResultReference Owner 结果引用
     * @param ownerResultSha256 Owner 结果摘要
     * @param observedAt 结果观察时间
     */
    record RepairResultWrite(String tenantId, String repairCommandId, String fromState, String toState,
                             String ownerResultReference, String ownerResultSha256, LocalDateTime observedAt) { }
    /**
     * 独立复核只追加写入参数。
     * @param reviewId 复核 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param reviewerUserId 复核人
     * @param decision 复核结论
     * @param reasonSha256 原因摘要
     * @param reviewedAt 复核时间
     */
    record ReviewWrite(String reviewId, String tenantId, String caseId, Long reviewerUserId,
                       String decision, String reasonSha256, LocalDateTime reviewedAt) { }
    /**
     * 状态历史只追加写入参数。
     * @param stateEventId 状态事件 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param fromState 原状态
     * @param toState 新状态
     * @param reasonSha256 原因摘要
     * @param actorUserId 操作者
     * @param occurredAt 发生时间
     */
    record StateEventWrite(String stateEventId, String tenantId, String caseId, String fromState,
                           String toState, String reasonSha256, Long actorUserId, LocalDateTime occurredAt) { }
    /**
     * 审计事实只追加写入参数。
     * @param auditId 审计 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param actionCode 动作代码
     * @param resultCode 结果代码
     * @param requestSha256 请求摘要
     * @param correlationId 关联标识
     * @param actorUserId 操作者
     * @param occurredAt 发生时间
     */
    record AuditWrite(String auditId, String tenantId, String caseId, String actionCode,
                      String resultCode, String requestSha256, String correlationId,
                      Long actorUserId, LocalDateTime occurredAt) { }
    /**
     * 异常事件 Outbox 受控写入参数。
     * @param eventId 事件 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param eventType 版本化事件类型
     * @param payloadJson 规范事件负载
     * @param payloadSha256 负载摘要
     * @param occurredAt 发生时间
     */
    record OutboxWrite(String eventId, String tenantId, String caseId, String eventType,
                       String payloadJson, String payloadSha256, LocalDateTime occurredAt) { }
    /**
     * 幂等命令结果只追加写入参数。
     * @param commandId 命令 ULID
     * @param tenantId 可信租户
     * @param caseId 案件标识
     * @param operation 操作类型
     * @param idempotencyKey 稳定幂等键
     * @param requestSha256 请求摘要
     * @param resultState 首次结果状态
     * @param createdAt 创建时间
     */
    record CommandWrite(String commandId, String tenantId, String caseId, String operation,
                        String idempotencyKey, String requestSha256, String resultState,
                        LocalDateTime createdAt) { }
}
