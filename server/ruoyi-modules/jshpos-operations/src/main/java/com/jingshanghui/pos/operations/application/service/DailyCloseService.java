package com.jingshanghui.pos.operations.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.port.DailyCloseOwnerGateway;
import com.jingshanghui.pos.operations.application.port.DailyClosePersistencePort;
import com.jingshanghui.pos.operations.application.port.DailyClosePersistencePort.*;
import com.jingshanghui.pos.operations.domain.DailyCloseRules;
import com.jingshanghui.pos.operations.domain.DailyCloseStates;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CloseState;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店业务日日结正式应用服务。
 *
 * <p>本服务只写 Operations Owner 自有事实；签署前再次读取权威 Owner 并比较冻结摘要，
 * 晚到事实只追加差异和新更正版本，绝不回写原关闭事实。</p>
 */
@Service
@RequiredArgsConstructor
public class DailyCloseService {
    private static final String ZERO_HASH = "0".repeat(64);
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final DailyClosePersistencePort persistence;
    private final DailyCloseOwnerGateway owners;
    private final UlidGenerator ids;
    private final Clock clock;

    /** 创建 DRAFT 或引用已关闭日结的 CORRECTION_REQUIRED 新版本。 */
    @Transactional
    public CloseDetail create(CreateClose command) {
        TrustedPrincipal principal = principal(command.storeId());
        Long storeId = DailyCloseRules.store(command.storeId());
        LocalDate businessDate = DailyCloseRules.date(command.businessDate());
        String key = DailyCloseRules.key(command.idempotencyKey());
        String correlation = DailyCloseRules.correlation(command.correlationId());
        String correctionReason = command.correctionOfCloseId() == null ? null : DailyCloseRules.reason(command.correctionReason());
        String reasonHash = correctionReason == null ? null : hash(Map.of("reason", correctionReason));
        String requestHash = hash(map("operation", "CREATE", "storeId", storeId,
            "businessDate", businessDate.toString(), "correctionOfCloseId", safe(command.correctionOfCloseId()),
            "correctionReasonSha256", safe(reasonHash)));
        CloseRecord replay = persistence.findByCreateKey(principal.tenantId(), key);
        if (replay != null) {
            DailyCloseRules.requireSameHash(replay.requestSha256(), requestHash);
            return detailOf(replay);
        }
        CloseState initial = CloseState.DRAFT;
        if (command.correctionOfCloseId() != null) {
            CloseRecord original = requireClose(principal.tenantId(), command.correctionOfCloseId());
            if (!CloseState.CLOSED.name().equals(original.state()) || !storeId.equals(original.storeId())
                || !businessDate.equals(original.businessDate())) {
                throw new ServiceException("OPS-CORRECTION-001: 更正只能引用同门店同业务日已关闭版本", 409);
            }
            initial = CloseState.CORRECTION_REQUIRED;
        }
        OwnerSnapshot identity = owners.capture(storeId, businessDate);
        int closeVersion = persistence.nextVersion(principal.tenantId(), storeId, businessDate);
        String closeId = ids.next();
        LocalDateTime at = now();
        persistence.insertClose(new CloseWrite(closeId, principal.tenantId(), storeId, businessDate,
            identity.zoneId(), identity.businessDayStart(), closeVersion, command.correctionOfCloseId(),
            reasonHash, initial.name(), ZERO_HASH, ZERO_HASH, key, requestHash, principal.userId(), at));
        CloseRecord created = requireClose(principal.tenantId(), closeId);
        appendStateAudit(created, null, initial, "DAILY_CLOSE_CREATED", requestHash, correlation,
            principal, initial == CloseState.DRAFT ? "创建日结草稿" : "创建日结更正版本", at);
        return detailOf(created);
    }

    /** 采集权威事实，冻结金额与来源清单，并记录全部通过或阻断检查。 */
    @Transactional
    public CloseDetail preflight(CloseCommand command) {
        Action action = action("PREFLIGHT", command);
        CloseDetail replay = replay(action);
        if (replay != null) return replay;
        CloseRecord close = lock(action, command.closeId());
        requireState(close, CloseState.DRAFT, CloseState.PREFLIGHT_FAILED, CloseState.CORRECTION_REQUIRED,
            CloseState.READY, CloseState.FAILED);
        close = transition(close, CloseState.PREFLIGHTING, action, null, null, null, "开始日结预检");
        OwnerSnapshot snapshot = owners.capture(close.storeId(), close.businessDate());
        if (!close.zoneId().equals(snapshot.zoneId()) || !close.businessDayStart().equals(snapshot.businessDayStart())) {
            insertDifference(close, "BUSINESS_DAY_RULE_DRIFT", close.requestSha256(),
                hash(map("zoneId", snapshot.zoneId(), "businessDayStart", snapshot.businessDayStart().toString())), now());
            close = transition(close, CloseState.PREFLIGHT_FAILED, action, null, null, null, "业务日规则发生漂移");
            recordCommand(action, close);
            return detailOf(close);
        }
        int runNo = persistence.nextPreflightRun(close.tenantId(), close.closeId());
        String snapshotHash = CanonicalJson.from(snapshot.canonicalContent(), 256 * 1024).sha256();
        String manifestHash = manifestHash(snapshot.checkpoints());
        persistSnapshot(close, runNo, snapshot, snapshotHash);
        for (SourceCheckpoint checkpoint : snapshot.checkpoints()) {
            persistence.insertCheckpoint(new CheckpointWrite(ids.next(), close.tenantId(), close.closeId(), runNo,
                checkpoint.ownerCode(), checkpoint.sourceVersion(), checkpoint.sourceSequence(),
                checkpoint.sourceStatus(), DailyCloseRules.hash(checkpoint.contentSha256()), now()));
        }
        for (PreflightFact check : snapshot.checks()) {
            persistence.insertPreflight(new PreflightWrite(ids.next(), close.tenantId(), close.closeId(), runNo,
                check.checkCode(), check.ownerCode(), check.required(), check.external(), check.status(),
                DailyCloseRules.hash(check.evidenceSha256()), check.maskedMessage(), now()));
        }
        boolean ready = DailyCloseStates.ready(snapshot.checks().stream().map(value ->
            new DailyCloseStates.CheckDecision(value.checkCode(), value.required(), value.external(), value.status())).toList());
        CloseState target = ready ? CloseState.READY : CloseState.PREFLIGHT_FAILED;
        close = transition(close, target, action, runNo, snapshotHash, manifestHash,
            ready ? "预检通过并冻结权威事实" : "预检存在必需阻断项");
        appendOutbox(close, "operations.daily-close.preflighted.v1", action.correlationId(), now());
        recordCommand(action, close);
        return detailOf(close);
    }

    /** 审批人与日结创建人强制分离。 */
    @Transactional
    public CloseDetail approve(ApprovalCommand command) {
        String reason = DailyCloseRules.reason(command.reason());
        Action action = action("APPROVE", command.closeId(), command.idempotencyKey(), command.correlationId(),
            hash(Map.of("reason", reason)));
        CloseDetail replay = replay(action);
        if (replay != null) return replay;
        CloseRecord close = lock(action, command.closeId());
        requireState(close, CloseState.READY);
        DailyCloseRules.makerChecker(close.creatorUserId(), action.principal().userId(), "审批日结");
        persistence.insertApproval(new ApprovalWrite(ids.next(), close.tenantId(), close.closeId(),
            action.principal().userId(), hash(Map.of("reason", reason)), action.key(), action.requestHash(), now()));
        close = transition(close, CloseState.APPROVED, action, null, null, null, "独立审批通过");
        appendOutbox(close, "operations.daily-close.approved.v1", action.correlationId(), now());
        recordCommand(action, close);
        return detailOf(close);
    }

    /** 签署前重采来源；任何摘要变化都记录差异并失败关闭。 */
    @Transactional
    public CloseDetail signAndClose(CloseCommand command) {
        Action action = action("SIGN_CLOSE", command);
        CloseDetail replay = replay(action);
        if (replay != null) return replay;
        CloseRecord close = lock(action, command.closeId());
        requireState(close, CloseState.APPROVED);
        DailyCloseRules.makerChecker(close.creatorUserId(), action.principal().userId(), "签署日结");
        if (persistence.listApprovals(close.tenantId(), close.closeId()).isEmpty()) {
            throw new ServiceException("OPS-APPROVAL-001: 缺少独立审批事实", 409);
        }
        OwnerSnapshot current = owners.capture(close.storeId(), close.businessDate());
        String snapshotHash = CanonicalJson.from(current.canonicalContent(), 256 * 1024).sha256();
        String manifestHash = manifestHash(current.checkpoints());
        if (!close.snapshotSha256().equals(snapshotHash) || !close.manifestSha256().equals(manifestHash)) {
            insertDifference(close, "SOURCE_CHANGED_BEFORE_SIGNATURE", close.manifestSha256(), manifestHash, now());
            close = transition(close, CloseState.FAILED, action, null, null, null, "签署前来源事实已变化");
            appendOutbox(close, "operations.daily-close.difference-detected.v1", action.correlationId(), now());
            recordCommand(action, close);
            return detailOf(close);
        }
        close = transition(close, CloseState.CLOSING, action, null, null, null, "进入签署冻结点");
        LocalDateTime at = now();
        String signatureHash = hash(map("closeId", close.closeId(), "closeVersion", close.closeVersion(),
            "snapshotSha256", close.snapshotSha256(), "manifestSha256", close.manifestSha256(),
            "signatoryUserId", action.principal().userId(), "signedAt", at.toString()));
        persistence.insertSignature(new SignatureWrite(ids.next(), close.tenantId(), close.closeId(),
            action.principal().userId(), close.snapshotSha256(), close.manifestSha256(), signatureHash,
            action.key(), action.requestHash(), at));
        close = transition(close, CloseState.CLOSED, action, null, null, null, "只追加签署完成");
        appendOutbox(close, "operations.daily-close.closed.v1", action.correlationId(), at);
        recordCommand(action, close);
        return detailOf(close);
    }

    /** 检测晚到事实；只追加差异，原 CLOSED 状态、快照和签名保持不变。 */
    @Transactional
    public CloseDetail detectLateFacts(CloseCommand command) {
        Action action = action("DETECT_LATE_FACTS", command);
        CloseDetail replay = replay(action);
        if (replay != null) return replay;
        CloseRecord close = lock(action, command.closeId());
        requireState(close, CloseState.CLOSED);
        OwnerSnapshot current = owners.capture(close.storeId(), close.businessDate());
        String currentManifest = manifestHash(current.checkpoints());
        String currentSnapshot = CanonicalJson.from(current.canonicalContent(), 256 * 1024).sha256();
        if (!close.manifestSha256().equals(currentManifest) || !close.snapshotSha256().equals(currentSnapshot)) {
            insertDifference(close, "LATE_FACT_REQUIRES_CORRECTION", close.manifestSha256(), currentManifest, now());
            appendOutbox(close, "operations.daily-close.correction-required.v1", action.correlationId(), now());
        }
        persistence.appendAudit(new AuditWrite(ids.next(), close.tenantId(), close.closeId(), "LATE_FACTS_SCANNED",
            "SUCCESS", action.requestHash(), action.correlationId(), action.principal().userId(),
            "完成晚到事实扫描，未改写原日结", now()));
        recordCommand(action, close);
        return detailOf(close);
    }

    public CloseDetail detail(String closeId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        CloseRecord close = requireClose(principal.tenantId(), closeId);
        authorization.requireStoreAccess(close.storeId());
        return detailOf(close);
    }

    public List<CloseRecord> list(Long storeId, LocalDate businessDate, int limit) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        Long trustedStoreId = DailyCloseRules.store(storeId);
        authorization.requireStoreAccess(trustedStoreId);
        int bounded = Math.max(1, Math.min(limit, 100));
        return persistence.list(principal.tenantId(), trustedStoreId, businessDate, bounded);
    }

    private TrustedPrincipal principal(Long storeId) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorization.requireStoreAccess(storeId);
        return principal;
    }

    private Action action(String operation, CloseCommand command) {
        return action(operation, command.closeId(), command.idempotencyKey(), command.correlationId(), "");
    }

    private Action action(String operation, String closeId, String key, String correlation, String extraHash) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String normalizedKey = DailyCloseRules.key(key);
        String normalizedCorrelation = DailyCloseRules.correlation(correlation);
        String requestHash = hash(map("operation", operation, "closeId", closeId, "extraHash", safe(extraHash)));
        return new Action(operation, closeId, normalizedKey, normalizedCorrelation, requestHash, principal);
    }

    private CloseRecord lock(Action action, String closeId) {
        CloseRecord close = persistence.lock(action.principal().tenantId(), closeId);
        if (close == null) throw new ServiceException("OPS-CLOSE-404: 日结不存在", 404);
        authorization.requireStoreAccess(close.storeId());
        return close;
    }

    private CloseDetail replay(Action action) {
        CommandRecord command = persistence.findCommand(action.principal().tenantId(), action.operation(), action.key());
        if (command == null) return null;
        DailyCloseRules.requireSameHash(command.requestSha256(), action.requestHash());
        return detailOf(requireClose(action.principal().tenantId(), command.closeId()));
    }

    private CloseRecord transition(CloseRecord close, CloseState target, Action action, Integer runNo,
                                   String snapshotHash, String manifestHash, String summary) {
        CloseState from = CloseState.valueOf(close.state());
        DailyCloseStates.requireTransition(from, target);
        LocalDateTime at = now();
        int changed = persistence.changeState(new StateChange(close.tenantId(), close.closeId(), from.name(), target.name(),
            close.recordVersion(), runNo, snapshotHash, manifestHash, at));
        if (changed != 1) throw new ServiceException("OPS-CONCURRENCY-001: 日结状态并发冲突", 409);
        CloseRecord current = requireClose(close.tenantId(), close.closeId());
        appendStateAudit(current, from, target, action.operation(), action.requestHash(), action.correlationId(),
            action.principal(), summary, at);
        return current;
    }

    private void persistSnapshot(CloseRecord close, int runNo, OwnerSnapshot snapshot, String hash) {
        SnapshotAmounts a = snapshot.amounts();
        persistence.insertSnapshot(new SnapshotWrite(ids.next(), close.tenantId(), close.closeId(), runNo,
            a.currency(), a.orderCount(), a.cancelledOrderCount(), a.returnCount(), a.grossMinor(),
            a.discountMinor(), a.surchargeMinor(), a.receivableMinor(), a.refundMinor(),
            a.cashReceivedMinor(), a.cashRefundedMinor(), a.electronicReceivedMinor(),
            a.electronicRefundedMinor(), a.unknownPaymentCount(), a.unknownRefundCount(),
            a.shiftDifferenceMinor(), hash, now()));
    }

    private void insertDifference(CloseRecord close, String type, String expected, String actual, LocalDateTime at) {
        String detailHash = hash(map("type", type, "closeId", close.closeId(), "expected", expected, "actual", actual));
        persistence.insertDifference(new DifferenceWrite(ids.next(), close.tenantId(), close.closeId(), type,
            "OPEN", expected, actual, detailHash, at));
    }

    private void recordCommand(Action action, CloseRecord close) {
        String resultHash = hash(map("closeId", close.closeId(), "state", close.state(),
            "recordVersion", close.recordVersion(), "snapshotSha256", close.snapshotSha256(),
            "manifestSha256", close.manifestSha256()));
        persistence.insertCommand(new CommandWrite(ids.next(), close.tenantId(), close.closeId(), action.operation(),
            action.key(), action.requestHash(), close.state(), resultHash, now()));
    }

    private void appendStateAudit(CloseRecord close, CloseState from, CloseState to, String action,
                                  String requestHash, String correlation, TrustedPrincipal actor,
                                  String summary, LocalDateTime at) {
        persistence.appendState(new StateEventWrite(ids.next(), close.tenantId(), close.closeId(),
            from == null ? null : from.name(), to.name(), requestHash, correlation, actor.userId(), at));
        persistence.appendAudit(new AuditWrite(ids.next(), close.tenantId(), close.closeId(), action,
            "SUCCESS", requestHash, correlation, actor.userId(), summary, at));
    }

    private void appendOutbox(CloseRecord close, String eventType, String correlation, LocalDateTime at) {
        Map<String, Object> payload = map("eventId", ids.next(), "eventType", eventType, "schemaVersion", 1,
            "closeId", close.closeId(), "closeVersion", close.closeVersion(), "storeId", close.storeId(),
            "businessDate", close.businessDate().toString(), "snapshotSha256", close.snapshotSha256(),
            "manifestSha256", close.manifestSha256(), "correlationId", correlation,
            "occurredAt", at.toInstant(ZoneOffset.UTC).toString());
        CanonicalJson.Result canonical = CanonicalJson.from(payload);
        persistence.appendOutbox(new OutboxWrite(ids.next(), close.tenantId(), close.closeId(), eventType,
            1, canonical.json(), canonical.sha256(), correlation, at));
    }

    private String manifestHash(List<SourceCheckpoint> checkpoints) {
        List<Map<String, Object>> values = checkpoints.stream().map(value -> map(
            "ownerCode", value.ownerCode(), "sourceVersion", value.sourceVersion(),
            "sourceSequence", value.sourceSequence(), "sourceStatus", value.sourceStatus(),
            "contentSha256", value.contentSha256())).toList();
        return CanonicalJson.from(Map.of("checkpoints", values)).sha256();
    }

    private CloseDetail detailOf(CloseRecord close) {
        List<DifferenceRecord> differences = persistence.listDifferences(close.tenantId(), close.closeId());
        boolean correction = differences.stream().anyMatch(value -> "OPEN".equals(value.state()));
        return new CloseDetail(close, persistence.listSnapshots(close.tenantId(), close.closeId()),
            persistence.listCheckpoints(close.tenantId(), close.closeId()),
            persistence.listPreflights(close.tenantId(), close.closeId()), differences,
            persistence.listApprovals(close.tenantId(), close.closeId()),
            persistence.listSignatures(close.tenantId(), close.closeId()), correction);
    }

    private CloseRecord requireClose(String tenantId, String closeId) {
        CloseRecord close = persistence.find(tenantId, closeId);
        if (close == null) throw new ServiceException("OPS-CLOSE-404: 日结不存在", 404);
        return close;
    }

    private void requireState(CloseRecord close, CloseState... allowed) {
        for (CloseState value : allowed) if (value.name().equals(close.state())) return;
        throw new ServiceException("OPS-STATE-002: 当前状态不允许该操作: " + close.state(), 409);
    }

    private String hash(Map<String, Object> content) {
        return CanonicalJson.from(content).sha256();
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }

    private String safe(String value) { return value == null ? "" : value; }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private record Action(String operation, String closeId, String key, String correlationId,
                          String requestHash, TrustedPrincipal principal) { }
}
