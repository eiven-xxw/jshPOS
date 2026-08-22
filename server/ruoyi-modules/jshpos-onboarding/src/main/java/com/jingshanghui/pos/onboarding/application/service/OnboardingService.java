package com.jingshanghui.pos.onboarding.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.*;
import com.jingshanghui.pos.onboarding.application.port.OnboardingOwnerGateway;
import com.jingshanghui.pos.onboarding.application.port.OnboardingPersistencePort;
import com.jingshanghui.pos.onboarding.application.port.OnboardingPersistencePort.*;
import com.jingshanghui.pos.onboarding.domain.OnboardingRules;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.CheckDecision;
import com.jingshanghui.pos.onboarding.domain.OnboardingStates.PlanState;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 门店开通计划的正式应用编排服务。
 *
 * <p>本服务只写 Onboarding Owner 自有事实；跨 Owner 通过具名端口推进，任何事实漂移、
 * 幂等冲突或外部 P0 未解阻都失败关闭。</p>
 */
@Service
@RequiredArgsConstructor
public class OnboardingService {
    private static final String ZERO_HASH = "0".repeat(64);
    private static final String APPLY_STEP = "FOUNDATION_CONFIG_BINDING";
    private static final Set<String> OPERATIONS = Set.of("PREFLIGHT", "APPROVE", "APPLY", "CHECK", "OPEN", "CANCEL");

    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final OnboardingPersistencePort persistence;
    private final OnboardingOwnerGateway owners;
    private final UlidGenerator ids;
    private final Clock clock;
    private final PlatformTransactionManager transactionManager;

    /** 创建计划时即冻结版本身份和白名单摘要，但不产生目标 Owner 效果。 */
    @Transactional
    public PlanDetail create(CreatePlan command) {
        TrustedPrincipal principal = principalAdmin();
        OnboardingRules.positive(command.targetStoreId(), "targetStoreId");
        OnboardingRules.positive(command.templateId(), "templateId");
        OnboardingRules.positive(command.templateVersionId(), "templateVersionId");
        if (command.sourceStoreId() != null) OnboardingRules.positive(command.sourceStoreId(), "sourceStoreId");
        String key = OnboardingRules.key(command.idempotencyKey());
        String correlation = OnboardingRules.correlation(command.correlationId());
        String requestHash = requestHash("CREATE", null, command.sourceStoreId(), command.targetStoreId(),
            command.templateId(), command.templateVersionId(), null);
        PlanRecord replay = persistence.findPlanByIdempotency(principal.tenantId(), key);
        if (replay != null) {
            OnboardingRules.requireSameHash(replay.requestSha256(), requestHash);
            return detailOf(replay);
        }
        OwnerSnapshot snapshot = owners.capture(command.sourceStoreId(), command.targetStoreId(),
            command.templateId(), command.templateVersionId());
        String snapshotHash = CanonicalJson.from(snapshot.items()).sha256();
        LocalDateTime at = now();
        String planId = ids.next();
        persistence.insertPlan(new PlanWrite(planId, principal.tenantId(), snapshot.sourceStoreId(),
            snapshot.targetStoreId(), snapshot.templateId(), snapshot.templateVersionId(),
            snapshot.sourceStoreVersion(), snapshot.targetStoreVersion(), snapshot.templateVersionNo(),
            OnboardingRules.hash(snapshot.templateSha256(), "templateSha256"), snapshot.industry(), snapshotHash,
            PlanState.DRAFT.name(), key, requestHash, principal.userId(), at));
        PlanRecord created = requirePlan(principal.tenantId(), planId);
        appendStateAndAudit(created, null, PlanState.DRAFT, "PLAN_CREATED", requestHash, correlation,
            principal, "创建门店开通计划", "onboarding.plan.created.v1", at);
        return detailOf(created);
    }

    /** 完整预检并只追加白名单快照；版本漂移形成 PREFLIGHT_FAILED，不产生部分配置。 */
    @Transactional
    public PlanDetail preflight(PlanCommand command) {
        Action action = action("PREFLIGHT", command, null);
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.DRAFT, PlanState.PREFLIGHT_FAILED, PlanState.READY, PlanState.FAILED);
        plan = transition(plan, PlanState.PREFLIGHTING, action, "开始白名单预检", null);
        OwnerSnapshot current = owners.capture(plan.sourceStoreId(), plan.targetStoreId(), plan.templateId(),
            plan.templateVersionId());
        if (!snapshotMatches(plan, current)) {
            PlanRecord failed = transition(plan, PlanState.PREFLIGHT_FAILED, action,
                "来源、目标或模板版本已漂移，未写入快照", "onboarding.plan.preflighted.v1");
            recordCommand(action, failed);
            return detailOf(failed);
        }
        List<SnapshotItem> existing = persistence.listSnapshot(plan.tenantId(), plan.planId());
        if (existing.isEmpty()) {
            for (Map.Entry<String, Object> entry : new TreeMap<>(current.items()).entrySet()) {
                CanonicalJson.Result content = snapshotContent(entry.getValue());
                persistence.insertSnapshot(new SnapshotWrite(ids.next(), plan.tenantId(), plan.planId(),
                    entry.getKey(), content.json(), content.sha256(), now()));
            }
        } else if (!snapshotItemsMatch(existing, current.items())) {
            PlanRecord failed = transition(plan, PlanState.PREFLIGHT_FAILED, action,
                "既有白名单快照与权威配置不一致", "onboarding.plan.preflighted.v1");
            recordCommand(action, failed);
            return detailOf(failed);
        }
        PlanRecord ready = transition(plan, PlanState.READY, action, "白名单预检通过并冻结快照",
            "onboarding.plan.preflighted.v1");
        recordCommand(action, ready);
        return detailOf(ready);
    }

    /** 审批人与计划创建人强制分离。 */
    @Transactional
    public PlanDetail approve(ReasonCommand command) {
        Action action = action("APPROVE", command.planId(), command.idempotencyKey(), command.correlationId(),
            OnboardingRules.reason(command.reason()));
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.READY);
        if (plan.creatorUserId().equals(action.principal().userId())) {
            throw new ServiceException("ONB-APPROVAL-001: 创建人与审批人必须分离", 403);
        }
        persistence.insertApproval(new ApprovalWrite(ids.next(), plan.tenantId(), plan.planId(),
            action.principal().userId(), command.reason().strip(), action.key(), action.requestHash(), now()));
        PlanRecord approved = transition(plan, PlanState.APPROVED, action, "独立审批通过",
            "onboarding.plan.approved.v1");
        recordCommand(action, approved);
        return detailOf(approved);
    }

    /** 以单一事务推进当前正式 Owner；失败后保留 FAILED 状态，不制造第二条业务命令。 */
    public PlanDetail apply(PlanCommand command) {
        Action action = action("APPLY", command, null);
        PlanDetail replay = replayReadOnly(action);
        if (replay != null) return replay;
        try {
            return tx(() -> applyInTransaction(command, action));
        } catch (RuntimeException exception) {
            tx(() -> {
                PlanRecord current = persistence.lockPlan(action.principal().tenantId(), command.planId());
                if (current != null && PlanState.APPROVED.name().equals(current.state())) {
                    transition(current, PlanState.FAILED, action, "Owner 应用失败，未确认任何新效果", null);
                }
                return null;
            });
            throw exception;
        }
    }

    /** 只追加新检查 run；外部 P0 的 BLOCKED/UNAVAILABLE 原样展示。 */
    @Transactional
    public PlanDetail checks(PlanCommand command) {
        Action action = action("CHECK", command, null);
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.APPLIED, PlanState.CHECK_FAILED, PlanState.READY_TO_OPEN,
            PlanState.COMPENSATION_REQUIRED);
        plan = transition(plan, PlanState.CHECKING, action, "开始权威事实开店检查", null);
        int runNo = persistence.nextCheckRun(plan.tenantId(), plan.planId());
        List<CheckFact> facts = validateChecks(owners.checks(plan, runNo));
        for (CheckFact fact : facts) {
            persistence.insertCheck(new CheckWrite(ids.next(), plan.tenantId(), plan.planId(), runNo, fact.code(),
                fact.ownerType(), fact.required(), fact.external(), safeVersion(fact.factVersion()),
                OnboardingRules.hash(fact.factSha256(), "factSha256"), fact.status(), safeMessage(fact.maskedMessage()), now()));
        }
        PlanState target = OnboardingStates.checkTarget(facts.stream().map(value -> new CheckDecision(value.code(),
            value.required(), value.external(), value.status())).toList());
        plan = transition(plan, target, action, target == PlanState.READY_TO_OPEN
            ? "内部检查通过；外部 P0 状态保留" : "必需内部检查未通过", "onboarding.plan.checked.v1", runNo);
        recordCommand(action, plan);
        return detailOf(plan);
    }

    /** 只有全部内部和外部必需检查 PASS 才激活门店。 */
    @Transactional
    public PlanDetail open(ReasonCommand command) {
        Action action = action("OPEN", command.planId(), command.idempotencyKey(), command.correlationId(),
            OnboardingRules.reason(command.reason()));
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.READY_TO_OPEN);
        List<CheckRecord> checks = persistence.listLatestChecks(plan.tenantId(), plan.planId());
        OnboardingStates.requireAllRequiredPass(checks.stream().map(value -> new CheckDecision(value.checkCode(),
            value.required(), value.external(), value.status())).toList());
        OwnerOpenResult result = owners.open(plan, command.reason());
        if (!plan.targetStoreId().equals(result.storeId()) || !"ACTIVE".equals(result.status())) {
            throw new ServiceException("ONB-OWNER-005: 门店 Owner 返回非法开店结果", 409);
        }
        PlanRecord opened = transition(plan, PlanState.OPENED, action, "全部必需检查通过，形成开店里程碑",
            "onboarding.store.opened.v1");
        recordCommand(action, opened);
        return detailOf(opened);
    }

    /** 只允许尚未产生 Owner 效果的计划取消。 */
    @Transactional
    public PlanDetail cancel(ReasonCommand command) {
        Action action = action("CANCEL", command.planId(), command.idempotencyKey(), command.correlationId(),
            OnboardingRules.reason(command.reason()));
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.DRAFT, PlanState.PREFLIGHT_FAILED, PlanState.READY);
        PlanRecord cancelled = transition(plan, PlanState.CANCELLED, action, "未应用计划已取消",
            "onboarding.plan.cancelled.v1");
        recordCommand(action, cancelled);
        return detailOf(cancelled);
    }

    @Transactional(readOnly = true)
    public PlanDetail detail(String planId) {
        TrustedPrincipal principal = principalAdmin();
        PlanRecord plan = requirePlan(principal.tenantId(), OnboardingRules.ulid(planId, "planId"));
        authorization.requireStoreAccess(plan.targetStoreId());
        return detailOf(plan);
    }

    private PlanDetail applyInTransaction(PlanCommand command, Action action) {
        PlanDetail replay = replay(action);
        if (replay != null) return replay;
        PlanRecord plan = lockPlan(action.principal().tenantId(), command.planId());
        requireState(plan, PlanState.APPROVED);
        plan = transition(plan, PlanState.APPLYING, action, "开始应用冻结配置", null);
        OwnerSnapshot current = owners.capture(plan.sourceStoreId(), plan.targetStoreId(), plan.templateId(),
            plan.templateVersionId());
        if (!snapshotMatches(plan, current)) throw new ServiceException("ONB-OWNER-002: 应用前权威事实已漂移", 409);
        OwnerApplyResult result = owners.apply(plan);
        if (!APPLY_STEP.equals(result.stepCode())) throw new ServiceException("ONB-OWNER-003: Owner 步骤代码非法", 409);
        OnboardingRules.hash(result.resultSha256(), "resultSha256");
        persistence.insertCheckpoint(new CheckpointWrite(ids.next(), plan.tenantId(), plan.planId(), result.stepCode(),
            action.key(), action.requestHash(), result.resultSha256(), "APPLIED", now()));
        PlanRecord applied = transition(plan, PlanState.APPLIED, action, "冻结配置已通过正式 Owner 端口应用",
            "onboarding.plan.applied.v1");
        recordCommand(action, applied);
        return detailOf(applied);
    }

    private Action action(String operation, PlanCommand command, String reason) {
        return action(operation, command.planId(), command.idempotencyKey(), command.correlationId(), reason);
    }

    private Action action(String operation, String planId, String key, String correlation, String reason) {
        if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("unknown operation");
        TrustedPrincipal principal = principalAdmin();
        String safePlan = OnboardingRules.ulid(planId, "planId");
        String safeKey = OnboardingRules.key(key);
        String safeCorrelation = OnboardingRules.correlation(correlation);
        String hash = requestHash(operation, safePlan, null, null, null, null, reason);
        return new Action(operation, safePlan, safeKey, safeCorrelation, hash, principal);
    }

    private PlanDetail replay(Action action) {
        CommandRecord command = persistence.findCommand(action.principal().tenantId(), action.operation(), action.key());
        if (command == null) return null;
        OnboardingRules.requireSameHash(command.requestSha256(), action.requestHash());
        if (!command.planId().equals(action.planId())) throw new ServiceException("ONB-IDEMPOTENCY-002: 幂等键属于其他计划", 409);
        return detailOf(requirePlan(action.principal().tenantId(), command.planId()));
    }

    private PlanDetail replayReadOnly(Action action) {
        return tx(() -> replay(action));
    }

    private void recordCommand(Action action, PlanRecord result) {
        persistence.insertCommand(new CommandWrite(ids.next(), result.tenantId(), result.planId(), action.operation(),
            action.key(), action.requestHash(), result.state(), resultHash(result), now()));
    }

    private PlanRecord transition(PlanRecord current, PlanState to, Action action, String summary, String eventType) {
        return transition(current, to, action, summary, eventType, null);
    }

    private PlanRecord transition(PlanRecord current, PlanState to, Action action, String summary, String eventType,
                                  Integer checkRun) {
        PlanState from = PlanState.valueOf(current.state());
        OnboardingStates.requireTransition(from, to);
        LocalDateTime at = now();
        int updated = persistence.changeState(new StateChange(current.tenantId(), current.planId(), from.name(),
            to.name(), current.recordVersion(), checkRun, at));
        if (updated != 1) throw new ServiceException("ONB-CONCURRENCY-001: 开店计划并发版本冲突", 409);
        PlanRecord next = requirePlan(current.tenantId(), current.planId());
        appendStateAndAudit(next, from, to, action.operation(), action.requestHash(), action.correlation(),
            action.principal(), summary, eventType, at);
        return next;
    }

    private void appendStateAndAudit(PlanRecord plan, PlanState from, PlanState to, String actionCode,
                                     String requestHash, String correlation, TrustedPrincipal principal,
                                     String summary, String eventType, LocalDateTime at) {
        persistence.appendState(new StateEventWrite(ids.next(), plan.tenantId(), plan.planId(),
            from == null ? null : from.name(), to.name(), requestHash, correlation, principal.userId(), at));
        persistence.appendAudit(new AuditWrite(ids.next(), plan.tenantId(), plan.planId(), "ONBOARDING_" + actionCode,
            "SUCCESS", requestHash, correlation, principal.userId(), safeMessage(summary), at));
        if (eventType != null) appendOutbox(plan, eventType, correlation, at);
    }

    private void appendOutbox(PlanRecord plan, String eventType, String correlation, LocalDateTime at) {
        String eventId = ids.next();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("state", plan.state());
        payload.put("targetStoreId", plan.targetStoreId());
        payload.put("sourceStoreId", plan.sourceStoreId());
        payload.put("templateVersionId", plan.templateVersionId());
        payload.put("snapshotSha256", plan.snapshotSha256());
        payload.put("checkRun", plan.checkRun() == null || plan.checkRun() == 0 ? null : plan.checkRun());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("aggregateId", plan.planId());
        envelope.put("occurredAt", at.toInstant(ZoneOffset.UTC).toString());
        envelope.put("correlationId", correlation);
        envelope.put("payload", payload);
        CanonicalJson.Result canonical = CanonicalJson.from(envelope);
        persistence.appendOutbox(new OutboxWrite(eventId, plan.tenantId(), plan.planId(), eventType, 1,
            canonical.json(), canonical.sha256(), correlation, at));
    }

    private List<CheckFact> validateChecks(List<CheckFact> facts) {
        if (facts == null) throw new ServiceException("ONB-CHECK-005: Owner 检查结果缺失", 409);
        Set<String> expected = new java.util.HashSet<>(OnboardingRules.INTERNAL_CHECKS);
        expected.addAll(OnboardingRules.EXTERNAL_CHECKS);
        Map<String, CheckFact> unique = new LinkedHashMap<>();
        for (CheckFact fact : facts) {
            if (fact == null || !expected.contains(fact.code()) || unique.putIfAbsent(fact.code(), fact) != null) {
                throw new ServiceException("ONB-CHECK-006: 检查代码缺失、重复或未冻结", 409);
            }
            boolean shouldExternal = OnboardingRules.EXTERNAL_CHECKS.contains(fact.code());
            if (!fact.required() || fact.external() != shouldExternal || fact.status() == null
                || (shouldExternal && fact.status() == OnboardingStates.CheckStatus.WARN)) {
                throw new ServiceException("ONB-CHECK-007: 检查等级或证据边界非法", 409);
            }
        }
        if (!unique.keySet().equals(expected)) throw new ServiceException("ONB-CHECK-008: 检查集不完整", 409);
        return unique.values().stream().sorted(java.util.Comparator.comparing(CheckFact::code)).toList();
    }

    private boolean snapshotMatches(PlanRecord plan, OwnerSnapshot current) {
        return java.util.Objects.equals(plan.sourceStoreId(), current.sourceStoreId())
            && java.util.Objects.equals(plan.sourceStoreVersion(), current.sourceStoreVersion())
            && plan.targetStoreId().equals(current.targetStoreId())
            && plan.targetStoreVersion().equals(current.targetStoreVersion())
            && plan.templateId().equals(current.templateId())
            && plan.templateVersionId().equals(current.templateVersionId())
            && plan.templateVersionNo().equals(current.templateVersionNo())
            && plan.templateSha256().equals(current.templateSha256())
            && plan.industry().equals(current.industry())
            && plan.snapshotSha256().equals(CanonicalJson.from(current.items()).sha256());
    }

    private boolean snapshotItemsMatch(List<SnapshotItem> existing, Map<String, Object> items) {
        if (existing.size() != items.size()) return false;
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : items.entrySet()) {
            hashes.put(entry.getKey(), snapshotContent(entry.getValue()).sha256());
        }
        return existing.stream().allMatch(value -> value.contentSha256().equals(hashes.get(value.itemKey())));
    }

    /**
     * 生成可包含空值的配置快照内容。Map.of 不接受空值，而配置白名单中的可选项
     * 允许显式记录 null；这里统一交给规范 JSON 计算摘要，避免空值导致开店计划异常。
     */
    private CanonicalJson.Result snapshotContent(Object value) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("value", value);
        return CanonicalJson.from(wrapper);
    }

    private PlanRecord lockPlan(String tenantId, String planId) {
        PlanRecord plan = persistence.lockPlan(tenantId, OnboardingRules.ulid(planId, "planId"));
        if (plan == null) throw new ServiceException("ONB-PLAN-001: 开店计划不存在或不可见", 404);
        authorization.requireStoreAccess(plan.targetStoreId());
        return plan;
    }

    private PlanRecord requirePlan(String tenantId, String planId) {
        PlanRecord plan = persistence.findPlan(tenantId, planId);
        if (plan == null) throw new ServiceException("ONB-PLAN-001: 开店计划不存在或不可见", 404);
        return plan;
    }

    private PlanDetail detailOf(PlanRecord plan) {
        return new PlanDetail(plan, persistence.listSnapshot(plan.tenantId(), plan.planId()),
            persistence.listApprovals(plan.tenantId(), plan.planId()),
            persistence.listCheckpoints(plan.tenantId(), plan.planId()),
            persistence.listLatestChecks(plan.tenantId(), plan.planId()));
    }

    private TrustedPrincipal principalAdmin() {
        authorization.requireTenantAdministrator();
        return tenantContext.requirePrincipal();
    }

    private void requireState(PlanRecord plan, PlanState... values) {
        for (PlanState value : values) if (value.name().equals(plan.state())) return;
        throw new ServiceException("ONB-STATE-002: 当前状态不允许该操作", 409);
    }

    private String requestHash(String operation, String planId, Long sourceStoreId, Long targetStoreId,
                               Long templateId, Long templateVersionId, String reason) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operation", operation);
        value.put("planId", planId);
        value.put("sourceStoreId", sourceStoreId);
        value.put("targetStoreId", targetStoreId);
        value.put("templateId", templateId);
        value.put("templateVersionId", templateVersionId);
        value.put("reason", reason);
        return OnboardingRules.requestHash(value);
    }

    private String resultHash(PlanRecord plan) {
        return OnboardingRules.requestHash(Map.of("planId", plan.planId(), "state", plan.state(),
            "snapshotSha256", plan.snapshotSha256(), "recordVersion", plan.recordVersion()));
    }

    private static String safeVersion(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > 64 || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) {
            throw new ServiceException("ONB-CHECK-009: 事实版本非法", 409);
        }
        return result;
    }

    private static String safeMessage(String value) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > 256 || result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) {
            throw new ServiceException("ONB-CHECK-010: 脱敏检查说明非法", 409);
        }
        return result;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private <T> T tx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private record Action(String operation, String planId, String key, String correlation,
                          String requestHash, TrustedPrincipal principal) {
    }
}
