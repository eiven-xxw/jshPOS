package com.jingshanghui.pos.operations.application.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** T2-EXC-001 命令、持久化投影和安全 REST 视图。 */
public final class ExceptionModels {
    private ExceptionModels() { }

    /**
     * 异常来源扫描命令。
     * @param storeId 可信授权门店
     * @param businessDate 门店业务日
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record ScanCommand(Long storeId, LocalDate businessDate, String idempotencyKey, String correlationId) { }
    /**
     * 案件认领命令。
     * @param caseId 异常案件标识
     * @param leaseMinutes 认领租约分钟数
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record ClaimCommand(String caseId, int leaseMinutes, String idempotencyKey, String correlationId) { }
    /**
     * 通用案件状态命令。
     * @param caseId 异常案件标识
     * @param reason 去敏操作原因
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record CaseCommand(String caseId, String reason, String idempotencyKey, String correlationId) { }
    /**
     * 案件转派命令。
     * @param caseId 异常案件标识
     * @param assigneeUserId 目标员工主键
     * @param leaseMinutes 新租约分钟数
     * @param reason 去敏转派原因
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record TransferCommand(String caseId, Long assigneeUserId, int leaseMinutes, String reason,
                                  String idempotencyKey, String correlationId) { }
    /**
     * 处置计划命令。
     * @param caseId 异常案件标识
     * @param actionCode Owner 具名修复动作
     * @param planSummary 去敏处置计划
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record PlanCommand(String caseId, String actionCode, String planSummary,
                              String idempotencyKey, String correlationId) { }
    /**
     * Owner 修复命令。
     * @param caseId 异常案件标识
     * @param actionCode Owner 具名修复动作
     * @param idempotencyKey 稳定幂等键
     * @param correlationId 关联标识
     */
    public record RepairCommand(String caseId, String actionCode, String idempotencyKey, String correlationId) { }

    /**
     * 案件控制面；tenantId 只由可信上下文注入。
     *
     * @param caseId 案件 ULID
     * @param tenantId 可信租户标识
     * @param storeId 可信门店主键
     * @param sourceOwner 来源 Owner
     * @param sourceType 来源异常类型
     * @param sourceFactId 来源事实标识
     * @param dedupKey 稳定去重键
     * @param severity 严重级别
     * @param state 当前控制状态
     * @param latestSourceEventId 最新来源事件
     * @param latestSourceSequence 最新来源序号
     * @param latestSourceSha256 最新来源摘要
     * @param assigneeUserId 当前认领人
     * @param leaseExpiresAt 租约 UTC 到期时间
     * @param resolverUserId Owner 成功结果处置人
     * @param reviewerUserId 独立复核人
     * @param recordVersion 乐观锁版本
     * @param firstObservedAt 首次观察 UTC 时间
     * @param lastObservedAt 最近观察 UTC 时间
     * @param createdAt 创建 UTC 时间
     * @param updatedAt 更新 UTC 时间
     */
    public record CaseRecord(String caseId, String tenantId, Long storeId, String sourceOwner,
                             String sourceType, String sourceFactId, String dedupKey, String severity,
                             String state, String latestSourceEventId, long latestSourceSequence,
                             String latestSourceSha256, Long assigneeUserId, LocalDateTime leaseExpiresAt,
                             Long resolverUserId, Long reviewerUserId, int recordVersion,
                             LocalDateTime firstObservedAt, LocalDateTime lastObservedAt,
                             LocalDateTime createdAt, LocalDateTime updatedAt) { }
    /**
     * Owner 来源观察投影。
     * @param observationId 观察 ULID
     * @param caseId 案件标识
     * @param sourceEventId 来源事件标识
     * @param sourceSequence 来源序号
     * @param sourceSha256 来源摘要
     * @param correlationId 关联标识
     * @param maskedSummary 去敏摘要
     * @param conflictFlag 冲突分类
     * @param observedAt 观察 UTC 时间
     */
    public record ObservationRecord(String observationId, String caseId, String sourceEventId,
                                    long sourceSequence, String sourceSha256, String correlationId,
                                    String maskedSummary, String conflictFlag, LocalDateTime observedAt) { }
    /**
     * 处置计划投影。
     * @param planId 计划 ULID
     * @param caseId 案件标识
     * @param actionCode 具名修复动作
     * @param summarySha256 去敏计划摘要
     * @param plannerUserId 计划人
     * @param state 计划状态
     * @param createdAt 创建 UTC 时间
     */
    public record PlanRecord(String planId, String caseId, String actionCode, String summarySha256,
                             Long plannerUserId, String state, LocalDateTime createdAt) { }
    /**
     * Owner 修复命令及观察结果投影。
     * @param repairCommandId 修复命令 ULID
     * @param caseId 案件标识
     * @param ownerCode 目标 Owner
     * @param actionCode 具名动作
     * @param requestSha256 请求摘要
     * @param idempotencyKey 幂等键
     * @param correlationId 关联标识
     * @param state 观察状态
     * @param ownerResultReference Owner 结果引用
     * @param ownerResultSha256 Owner 结果摘要
     * @param requestedAt 请求 UTC 时间
     * @param observedAt 结果观察 UTC 时间
     */
    public record RepairRecord(String repairCommandId, String caseId, String ownerCode, String actionCode,
                               String requestSha256, String idempotencyKey, String correlationId,
                               String state, String ownerResultReference, String ownerResultSha256,
                               LocalDateTime requestedAt, LocalDateTime observedAt) { }
    /**
     * 独立复核事实投影。
     * @param reviewId 复核 ULID
     * @param caseId 案件标识
     * @param reviewerUserId 独立复核人
     * @param decision 复核结论
     * @param reasonSha256 去敏原因摘要
     * @param reviewedAt 复核 UTC 时间
     */
    public record ReviewRecord(String reviewId, String caseId, Long reviewerUserId, String decision,
                               String reasonSha256, LocalDateTime reviewedAt) { }
    /**
     * 案件只追加状态历史投影。
     * @param stateEventId 状态事件 ULID
     * @param caseId 案件标识
     * @param fromState 原状态
     * @param toState 新状态
     * @param reasonSha256 原因摘要
     * @param actorUserId 操作者
     * @param occurredAt 发生 UTC 时间
     */
    public record StateEventRecord(String stateEventId, String caseId, String fromState, String toState,
                                   String reasonSha256, Long actorUserId, LocalDateTime occurredAt) { }
    /**
     * 案件只追加审计投影。
     * @param auditId 审计 ULID
     * @param caseId 案件标识
     * @param actionCode 动作代码
     * @param resultCode 结果代码
     * @param requestSha256 请求摘要
     * @param correlationId 关联标识
     * @param actorUserId 操作者
     * @param occurredAt 发生 UTC 时间
     */
    public record AuditRecord(String auditId, String caseId, String actionCode, String resultCode,
                              String requestSha256, String correlationId, Long actorUserId,
                              LocalDateTime occurredAt) { }
    /**
     * 案件幂等命令结果投影。
     * @param commandId 命令 ULID
     * @param caseId 案件标识
     * @param operation 操作类型
     * @param idempotencyKey 幂等键
     * @param requestSha256 请求摘要
     * @param resultState 首次结果状态
     * @param createdAt 创建 UTC 时间
     */
    public record CommandRecord(String commandId, String caseId, String operation, String idempotencyKey,
                                String requestSha256, String resultState, LocalDateTime createdAt) { }

    /**
     * 异常案件完整安全视图。
     * @param exceptionCase 案件控制面
     * @param observations 来源观察
     * @param plans 处置计划
     * @param repairs 修复命令与结果
     * @param reviews 独立复核
     * @param states 状态历史
     * @param audits 审计时间线
     */
    public record CaseDetail(CaseRecord exceptionCase, List<ObservationRecord> observations,
                             List<PlanRecord> plans, List<RepairRecord> repairs, List<ReviewRecord> reviews,
                             List<StateEventRecord> states, List<AuditRecord> audits) {
        public CaseDetail {
            observations = observations == null ? List.of() : List.copyOf(observations);
            plans = plans == null ? List.of() : List.copyOf(plans);
            repairs = repairs == null ? List.of() : List.copyOf(repairs);
            reviews = reviews == null ? List.of() : List.copyOf(reviews);
            states = states == null ? List.of() : List.copyOf(states);
            audits = audits == null ? List.of() : List.copyOf(audits);
        }
    }
}
