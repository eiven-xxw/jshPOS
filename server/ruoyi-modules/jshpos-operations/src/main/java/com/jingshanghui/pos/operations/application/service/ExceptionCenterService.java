package com.jingshanghui.pos.operations.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerObservation;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairCommand;
import com.jingshanghui.pos.foundation.application.port.OperationsExceptionOwnerPort.OwnerRepairResult;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;
import com.jingshanghui.pos.operations.application.port.ExceptionOwnerGateway;
import com.jingshanghui.pos.operations.application.port.ExceptionOwnerGateway.OwnedObservation;
import com.jingshanghui.pos.operations.application.port.ExceptionPersistencePort;
import com.jingshanghui.pos.operations.application.port.ExceptionPersistencePort.*;
import com.jingshanghui.pos.operations.domain.ExceptionRules;
import com.jingshanghui.pos.operations.domain.ExceptionStates;
import com.jingshanghui.pos.operations.domain.ExceptionStates.CaseState;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统一异常中心正式应用服务。
 *
 * <p>本服务只写 Operations 自有案件与编排事实。扫描内容来自 Owner 窄端口；修复只保存
 * 原命令引用和结果摘要，绝不直接写其他 Owner 表。</p>
 */
@Service
@RequiredArgsConstructor
public class ExceptionCenterService {
    private static final String ZERO_HASH = "0".repeat(64);
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final ExceptionPersistencePort persistence;
    private final ExceptionOwnerGateway owners;
    private final UlidGenerator ids;
    private final Clock clock;

    /** 扫描请求只携带门店与业务日，来源身份、摘要和严重级别均由 Owner 产生。 */
    @Transactional
    public List<CaseRecord> scan(ScanCommand command) {
        TrustedPrincipal principal = principal(command.storeId());
        Long storeId = ExceptionRules.store(command.storeId());
        ExceptionRules.date(command.businessDate());
        String key = ExceptionRules.safe(command.idempotencyKey(), "OPS-EXC-IDEMPOTENCY-002");
        String correlation = ExceptionRules.safe(command.correlationId(), "OPS-EXC-TRACE-001");
        for (OwnedObservation owned : owners.scan(storeId, command.businessDate(), 100)) {
            observe(principal, storeId, owned, key, correlation);
        }
        return persistence.list(principal.tenantId(), storeId, null, null, 100);
    }

    @Transactional(readOnly = true)
    public List<CaseRecord> list(Long storeId, String state, String severity, int limit) {
        TrustedPrincipal principal = principal(storeId);
        if (state != null) CaseState.valueOf(state);
        if (severity != null) ExceptionRules.severity(severity);
        return persistence.list(principal.tenantId(), ExceptionRules.store(storeId), state, severity,
            ExceptionRules.limit(limit));
    }

    @Transactional(readOnly = true)
    public CaseDetail detail(String caseId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        CaseRecord value = requireCase(principal.tenantId(), caseId);
        authorization.requireStoreAccess(value.storeId());
        return detailOf(value);
    }

    /** 认领或在租约过期后重新认领；同一时刻只允许一个租约持有人。 */
    @Transactional
    public CaseDetail claim(ClaimCommand command) {
        ExceptionRules.leaseMinutes(command.leaseMinutes());
        Action action = action("CLAIM", command.caseId(), command.idempotencyKey(), command.correlationId(),
            map("leaseMinutes", command.leaseMinutes()));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action);
        CaseState from = CaseState.valueOf(current.state());
        LocalDateTime now = now();
        boolean expired = current.leaseExpiresAt() == null || !current.leaseExpiresAt().isAfter(now);
        if (!expired && !Objects.equals(current.assigneeUserId(), action.principal().userId())) {
            throw new ServiceException("OPS-EXC-LEASE-002: 案件已被其他人员认领", 409);
        }
        if (!(from == CaseState.OPEN || from == CaseState.REOPENED || from == CaseState.FAILED || expired
            || (from == CaseState.CLAIMED && Objects.equals(current.assigneeUserId(), action.principal().userId())))) {
            throw new ServiceException("OPS-EXC-LEASE-003: 当前状态不可认领", 409);
        }
        LocalDateTime expires = now.plusMinutes(command.leaseMinutes());
        CaseRecord changed = transition(current, CaseState.CLAIMED, action, action.principal().userId(), expires,
            null, null, "认领异常案件");
        persistence.insertLeaseEvent(new LeaseEventWrite(ids.next(), changed.tenantId(), changed.caseId(),
            expired && current.assigneeUserId() != null ? "RECLAIMED" : "CLAIMED", current.assigneeUserId(),
            action.principal().userId(), expires, action.requestHash(), action.principal().userId(), now));
        complete(action, changed, "operations.exception.claimed.v1");
        return detailOf(changed);
    }

    /** 认领人开始处置。 */
    @Transactional
    public CaseDetail start(CaseCommand command) {
        String reason = ExceptionRules.reason(command.reason());
        Action action = action("START", command.caseId(), command.idempotencyKey(), command.correlationId(), map("reason", reason));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action); requireHolder(current, action.principal());
        CaseRecord changed = transition(current, CaseState.IN_PROGRESS, action, current.assigneeUserId(),
            current.leaseExpiresAt(), null, null, reason);
        complete(action, changed, "operations.exception.state-changed.v1");
        return detailOf(changed);
    }

    /** 转派只改变案件租约控制面并保留只追加租约事件。 */
    @Transactional
    public CaseDetail transfer(TransferCommand command) {
        String reason = ExceptionRules.reason(command.reason());
        if (command.assigneeUserId() == null || command.assigneeUserId() <= 0) throw new ServiceException("OPS-EXC-ASSIGNEE-001: 转派人无效", 400);
        ExceptionRules.leaseMinutes(command.leaseMinutes());
        Action action = action("TRANSFER", command.caseId(), command.idempotencyKey(), command.correlationId(),
            map("assigneeUserId", command.assigneeUserId(), "leaseMinutes", command.leaseMinutes(), "reason", reason));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action); requireHolder(current, action.principal());
        LocalDateTime expires = now().plusMinutes(command.leaseMinutes());
        CaseRecord changed = transition(current, CaseState.CLAIMED, action, command.assigneeUserId(), expires,
            null, null, reason);
        persistence.insertLeaseEvent(new LeaseEventWrite(ids.next(), changed.tenantId(), changed.caseId(),
            "TRANSFERRED", current.assigneeUserId(), command.assigneeUserId(), expires, hash(map("reason", reason)),
            action.principal().userId(), now()));
        complete(action, changed, "operations.exception.assigned.v1");
        return detailOf(changed);
    }

    /** 保存去敏处置计划；前端不得把计划当作 Owner 修复结果。 */
    @Transactional
    public CaseDetail plan(PlanCommand command) {
        String actionCode = ExceptionRules.safe(command.actionCode(), "OPS-EXC-ACTION-001");
        String summary = ExceptionRules.reason(command.planSummary());
        Action action = action("PLAN", command.caseId(), command.idempotencyKey(), command.correlationId(),
            map("actionCode", actionCode, "summary", summary));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action); requireHolder(current, action.principal());
        if (CaseState.CLAIMED.name().equals(current.state())) {
            current = transition(current, CaseState.IN_PROGRESS, action, current.assigneeUserId(),
                current.leaseExpiresAt(), null, null, "进入处置计划");
        } else if (!CaseState.IN_PROGRESS.name().equals(current.state())) {
            throw new ServiceException("OPS-EXC-PLAN-001: 当前状态不可制定计划", 409);
        }
        persistence.insertPlan(new PlanWrite(ids.next(), current.tenantId(), current.caseId(), actionCode,
            hash(map("summary", summary)), action.principal().userId(), "ACTIVE", now()));
        complete(action, current, "operations.exception.plan-created.v1");
        return detailOf(current);
    }

    /** 经 Owner 具名端口提交修复；UNAVAILABLE/WAITING 不会被伪装为成功。 */
    @Transactional
    public CaseDetail repair(RepairCommand command) {
        String actionCode = ExceptionRules.safe(command.actionCode(), "OPS-EXC-ACTION-001");
        Action action = action("REPAIR", command.caseId(), command.idempotencyKey(), command.correlationId(),
            map("actionCode", actionCode));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action); requireHolder(current, action.principal());
        if (!(CaseState.IN_PROGRESS.name().equals(current.state()) || CaseState.WAITING_OWNER.name().equals(current.state()))) {
            throw new ServiceException("OPS-EXC-REPAIR-001: 当前状态不可发起Owner修复", 409);
        }
        PlanRecord plan = persistence.latestPlan(current.tenantId(), current.caseId());
        if (plan == null || !actionCode.equals(plan.actionCode())) throw new ServiceException("OPS-EXC-REPAIR-002: 缺少匹配的有效处置计划", 409);
        String repairId = ids.next();
        persistence.insertRepair(new RepairWrite(repairId, current.tenantId(), current.caseId(), current.sourceOwner(),
            actionCode, action.requestHash(), action.key(), action.correlationId(), "REQUESTED", now()));
        OwnerRepairResult result = owners.repair(current.sourceOwner(), new OwnerRepairCommand(repairId,
            current.storeId(), current.sourceType(), current.sourceFactId(), current.latestSourceEventId(),
            current.latestSourceSequence(), current.latestSourceSha256(), actionCode, action.requestHash(),
            action.key(), action.correlationId()));
        String resultState = normalizeOwnerStatus(result.status());
        String resultHash = result.resultSha256() == null ? hash(map("status", resultState,
            "reference", safe(result.resultReference()), "message", safe(result.maskedMessage()))) : ExceptionRules.hash(result.resultSha256());
        int repairUpdated = persistence.updateRepairResult(new RepairResultWrite(current.tenantId(), repairId, "REQUESTED",
            resultState, safe(result.resultReference()), resultHash, now()));
        if (repairUpdated != 1) {
            throw new ServiceException("OPS-EXC-CONCURRENCY-002: Owner修复结果并发冲突", 409);
        }
        CaseState target = switch (resultState) {
            case "SUCCEEDED" -> CaseState.RESOLVED;
            case "FAILED" -> CaseState.FAILED;
            default -> CaseState.WAITING_OWNER;
        };
        CaseRecord changed = transition(current, target, action, current.assigneeUserId(), current.leaseExpiresAt(),
            target == CaseState.RESOLVED ? action.principal().userId() : null, null,
            "Owner修复观察=" + resultState);
        complete(action, changed, "operations.exception.repair-requested.v1");
        return detailOf(changed);
    }

    /** 独立复核只接受具备 Owner 可验证成功摘要的 RESOLVED 案件。 */
    @Transactional
    public CaseDetail review(CaseCommand command) {
        String reason = ExceptionRules.reason(command.reason());
        Action action = action("REVIEW", command.caseId(), command.idempotencyKey(), command.correlationId(), map("reason", reason));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action);
        if (!CaseState.RESOLVED.name().equals(current.state())) throw new ServiceException("OPS-EXC-REVIEW-001: 仅已解决案件可复核", 409);
        ExceptionRules.different(current.assigneeUserId(), action.principal().userId(), "复核");
        persistence.insertReview(new ReviewWrite(ids.next(), current.tenantId(), current.caseId(), action.principal().userId(),
            "APPROVED", hash(map("reason", reason)), now()));
        CaseRecord changed = updateReviewer(current, action, action.principal().userId(), reason);
        complete(action, changed, "operations.exception.reviewed.v1");
        return detailOf(changed);
    }

    /** 关闭需要独立复核事实；关闭不改变任何来源 Owner。 */
    @Transactional
    public CaseDetail close(CaseCommand command) {
        String reason = ExceptionRules.reason(command.reason());
        Action action = action("CLOSE", command.caseId(), command.idempotencyKey(), command.correlationId(), map("reason", reason));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action);
        if (persistence.latestApprovedReview(current.tenantId(), current.caseId()) == null || current.reviewerUserId() == null) {
            throw new ServiceException("OPS-EXC-CLOSE-001: 缺少独立复核事实", 409);
        }
        CaseRecord changed = transition(current, CaseState.CLOSED, action, current.assigneeUserId(),
            current.leaseExpiresAt(), current.resolverUserId(), current.reviewerUserId(), reason);
        complete(action, changed, "operations.exception.closed.v1");
        return detailOf(changed);
    }

    /** 重新出现只追加 REOPENED；旧观察、修复、复核和关闭历史保持不变。 */
    @Transactional
    public CaseDetail reopen(CaseCommand command) {
        String reason = ExceptionRules.reason(command.reason());
        Action action = action("REOPEN", command.caseId(), command.idempotencyKey(), command.correlationId(), map("reason", reason));
        CaseDetail replay = replay(action); if (replay != null) return replay;
        CaseRecord current = lock(action);
        CaseRecord changed = transition(current, CaseState.REOPENED, action, null, null, null, null, reason);
        complete(action, changed, "operations.exception.state-changed.v1");
        return detailOf(changed);
    }

    private void observe(TrustedPrincipal principal, Long storeId, OwnedObservation owned, String scanKey, String scanCorrelation) {
        String owner = ExceptionRules.owner(owned.ownerCode());
        OwnerObservation value = owned.observation();
        String sourceType = ExceptionRules.safe(value.sourceType(), "OPS-EXC-SOURCE-001");
        String sourceFact = ExceptionRules.safe(value.sourceFactId(), "OPS-EXC-SOURCE-002");
        String sourceEvent = ExceptionRules.safe(value.sourceEventId(), "OPS-EXC-SOURCE-003");
        String sourceHash = ExceptionRules.hash(value.sourceSha256());
        String severity = ExceptionRules.severity(value.severity());
        if (value.sourceSequence() < 0 || value.observedAt() == null) throw new ServiceException("OPS-EXC-SOURCE-004: 来源序号或时间无效", 409);
        String dedup = owner + ":" + ExceptionRules.safe(value.dedupKey(), "OPS-EXC-DEDUP-001");
        ObservationRecord eventReplay = persistence.findObservation(principal.tenantId(), owner, sourceEvent);
        if (eventReplay != null) {
            if (!sourceHash.equals(eventReplay.sourceSha256())) throw new ServiceException("OPS-EXC-SOURCE-005: 同来源事件异摘要", 409);
            return;
        }
        CaseRecord current = persistence.findByDedup(principal.tenantId(), storeId, dedup);
        boolean created = current == null;
        String caseId = current == null ? ids.next() : current.caseId();
        LocalDateTime at = value.observedAt();
        if (current == null) {
            persistence.insertCase(new CaseWrite(caseId, principal.tenantId(), storeId, owner, sourceType,
                sourceFact, dedup, severity, CaseState.OPEN.name(), sourceEvent, value.sourceSequence(),
                sourceHash, at, at, now()));
            current = requireCase(principal.tenantId(), caseId);
            persistence.appendState(new StateEventWrite(ids.next(), principal.tenantId(), caseId, null,
                CaseState.OPEN.name(), sourceHash, principal.userId(), now()));
        } else if (value.sourceSequence() > current.latestSourceSequence()) {
            String target = switch (CaseState.valueOf(current.state())) {
                case CLOSED, RESOLVED, FAILED -> CaseState.REOPENED.name();
                default -> current.state();
            };
            int changed = persistence.updateObservationHead(new ObservationHead(principal.tenantId(), caseId,
                current.latestSourceSha256(), sourceEvent, value.sourceSequence(), sourceHash, severity, target,
                current.recordVersion(), at));
            if (changed != 1) throw new ServiceException("OPS-EXC-CONCURRENCY-001: 来源观察并发冲突", 409);
            if (!target.equals(current.state())) persistence.appendState(new StateEventWrite(ids.next(),
                principal.tenantId(), caseId, current.state(), target, sourceHash, principal.userId(), now()));
        }
        String conflict;
        if (created) {
            conflict = "INITIAL";
        } else if (value.sourceSequence() < current.latestSourceSequence()) {
            conflict = "OUT_OF_ORDER";
        } else if (value.sourceSequence() == current.latestSourceSequence()) {
            conflict = current.latestSourceSha256().equals(sourceHash) ? "SAME_CONTENT_NEW_EVENT" : "SEQUENCE_CONFLICT";
        } else {
            conflict = current.latestSourceSha256().equals(sourceHash) ? "SAME_CONTENT_NEW_EVENT" : "CONTENT_CHANGED";
        }
        persistence.insertObservation(new ObservationWrite(ids.next(), principal.tenantId(), caseId, owner,
            sourceEvent, value.sourceSequence(), sourceHash, ExceptionRules.safe(value.correlationId(), "OPS-EXC-TRACE-001"),
            mask(value.maskedSummary()), conflict, at));
        persistence.appendAudit(new AuditWrite(ids.next(), principal.tenantId(), caseId, "OWNER_OBSERVED", "SUCCESS",
            hash(map("scanKey", scanKey, "sourceSha256", sourceHash)), scanCorrelation, principal.userId(), now()));
        appendOutbox(requireCase(principal.tenantId(), caseId), "operations.exception.observed.v1", scanCorrelation);
    }

    private CaseRecord transition(CaseRecord current, CaseState target, Action action, Long assignee,
                                  LocalDateTime lease, Long resolver, Long reviewer, String reason) {
        CaseState from = CaseState.valueOf(current.state());
        ExceptionStates.requireTransition(from, target);
        int changed = persistence.changeState(new StateChange(current.tenantId(), current.caseId(), from.name(),
            target.name(), current.recordVersion(), assignee, lease, resolver, reviewer, now()));
        if (changed != 1) throw new ServiceException("OPS-EXC-CONCURRENCY-001: 案件状态并发冲突", 409);
        persistence.appendState(new StateEventWrite(ids.next(), current.tenantId(), current.caseId(), from.name(),
            target.name(), hash(map("reason", reason)), action.principal().userId(), now()));
        return requireCase(current.tenantId(), current.caseId());
    }

    private CaseRecord updateReviewer(CaseRecord current, Action action, Long reviewer, String reason) {
        int changed = persistence.changeState(new StateChange(current.tenantId(), current.caseId(), current.state(),
            current.state(), current.recordVersion(), current.assigneeUserId(), current.leaseExpiresAt(),
            current.resolverUserId(), reviewer, now()));
        if (changed != 1) throw new ServiceException("OPS-EXC-CONCURRENCY-001: 复核并发冲突", 409);
        persistence.appendState(new StateEventWrite(ids.next(), current.tenantId(), current.caseId(), current.state(),
            current.state(), hash(map("reason", reason)), action.principal().userId(), now()));
        return requireCase(current.tenantId(), current.caseId());
    }

    private void complete(Action action, CaseRecord changed, String eventType) {
        persistence.insertCommand(new CommandWrite(ids.next(), changed.tenantId(), changed.caseId(), action.operation(),
            action.key(), action.requestHash(), changed.state(), now()));
        persistence.appendAudit(new AuditWrite(ids.next(), changed.tenantId(), changed.caseId(), action.operation(),
            "SUCCESS", action.requestHash(), action.correlationId(), action.principal().userId(), now()));
        appendOutbox(changed, eventType, action.correlationId());
    }

    private void appendOutbox(CaseRecord value, String eventType, String correlation) {
        CanonicalJson.Result payload = CanonicalJson.from(map("eventId", ids.next(), "eventType", eventType,
            "schemaVersion", "1.0", "caseId", value.caseId(), "sourceOwner", value.sourceOwner(),
            "sourceFactId", value.sourceFactId(), "sourceSha256", value.latestSourceSha256(),
            "state", value.state(), "correlationId", correlation, "occurredAt", now().toString()));
        persistence.appendOutbox(new OutboxWrite(ids.next(), value.tenantId(), value.caseId(), eventType,
            payload.json(), payload.sha256(), now()));
    }

    private Action action(String operation, String caseId, String key, String correlation, Map<String,Object> extra) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String normalizedKey = ExceptionRules.safe(key, "OPS-EXC-IDEMPOTENCY-002");
        String normalizedCorrelation = ExceptionRules.safe(correlation, "OPS-EXC-TRACE-001");
        return new Action(operation, ExceptionRules.safe(caseId, "OPS-EXC-CASE-001"), normalizedKey,
            normalizedCorrelation, hash(map("operation", operation, "caseId", caseId, "extra", extra)), principal);
    }

    private CaseRecord lock(Action action) {
        CaseRecord value = persistence.lock(action.principal().tenantId(), action.caseId());
        if (value == null) throw new ServiceException("OPS-EXC-404: 异常案件不存在", 404);
        authorization.requireStoreAccess(value.storeId());
        return value;
    }

    private CaseDetail replay(Action action) {
        CommandRecord value = persistence.findCommand(action.principal().tenantId(), action.operation(), action.key());
        if (value == null) return null;
        if (!value.requestSha256().equals(action.requestHash())) throw new ServiceException("OPS-EXC-IDEMPOTENCY-001: 同幂等键异内容", 409);
        return detailOf(requireCase(action.principal().tenantId(), value.caseId()));
    }

    private void requireHolder(CaseRecord value, TrustedPrincipal principal) {
        if (!Objects.equals(value.assigneeUserId(), principal.userId()) || value.leaseExpiresAt() == null
            || !value.leaseExpiresAt().isAfter(now())) throw new ServiceException("OPS-EXC-LEASE-004: 当前人员没有有效认领租约", 409);
    }

    private TrustedPrincipal principal(Long storeId) {
        TrustedPrincipal value = tenantContext.requirePrincipal();
        authorization.requireStoreAccess(ExceptionRules.store(storeId));
        return value;
    }

    private CaseRecord requireCase(String tenantId, String caseId) {
        CaseRecord value = persistence.find(tenantId, caseId);
        if (value == null) throw new ServiceException("OPS-EXC-404: 异常案件不存在", 404);
        return value;
    }

    private CaseDetail detailOf(CaseRecord value) {
        return new CaseDetail(value, persistence.listObservations(value.tenantId(), value.caseId()),
            persistence.listPlans(value.tenantId(), value.caseId()), persistence.listRepairs(value.tenantId(), value.caseId()),
            persistence.listReviews(value.tenantId(), value.caseId()), persistence.listStates(value.tenantId(), value.caseId()),
            persistence.listAudits(value.tenantId(), value.caseId()));
    }

    private String normalizeOwnerStatus(String value) {
        if (value == null) return "UNAVAILABLE";
        return switch (value) {
            case "SUCCEEDED", "WAITING_OWNER", "UNAVAILABLE", "FAILED" -> value;
            default -> throw new ServiceException("OPS-EXC-OWNER-002: Owner结果状态非法", 409);
        };
    }

    private String safe(String value) { return value == null ? "" : value; }
    private String mask(String value) { String text = value == null ? "" : value.strip(); return text.length() <= 256 ? text : text.substring(0, 256); }
    private String hash(Map<String,Object> value) { return CanonicalJson.from(value).sha256(); }
    private Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<values.length;i+=2) result.put(String.valueOf(values[i]), values[i+1]);
        return result;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    /**
     * 经可信上下文归一化后的案件动作。
     * @param operation 操作类型
     * @param caseId 案件标识
     * @param key 稳定幂等键
     * @param correlationId 关联标识
     * @param requestHash 规范请求摘要
     * @param principal 可信租户与操作者上下文
     */
    private record Action(String operation, String caseId, String key, String correlationId,
                          String requestHash, TrustedPrincipal principal) { }
}
