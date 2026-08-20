package com.jingshanghui.pos.release.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.release.application.port.ReleasePorts.*;
import com.jingshanghui.pos.release.domain.ReleaseIdGenerator;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import com.jingshanghui.pos.release.domain.ReleaseRules;
import org.dromara.common.core.exception.ServiceException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * T2-UPG-001 应用服务。它只编排发布治理事实，不执行厂商静默安装、固件命令或真实终端重启。
 */
@Service
public class ReleaseGovernanceService {
    private final TrustedTenantContext tenantContext;
    private final AuthorizedStores authorizedStores;
    private final Repository repository;
    private final ArtifactVerifier artifactVerifier;
    private final TrustedTerminalRegistry terminalRegistry;
    private final SafetyProbe safetyProbe;
    private final ReleaseIdGenerator ids;
    private final Clock clock;

    public ReleaseGovernanceService(TrustedTenantContext tenantContext, AuthorizedStores authorizedStores,
                                    Repository repository, ArtifactVerifier artifactVerifier,
                                    TrustedTerminalRegistry terminalRegistry, SafetyProbe safetyProbe,
                                    ReleaseIdGenerator ids, Clock clock) {
        this.tenantContext = tenantContext;
        this.authorizedStores = authorizedStores;
        this.repository = repository;
        this.artifactVerifier = artifactVerifier;
        this.terminalRegistry = terminalRegistry;
        this.safetyProbe = safetyProbe;
        this.ids = ids;
        this.clock = clock;
    }

    /** 创建发布草稿；目标范围必须全部属于当前可信租户管理员的数据范围。 */
    @Transactional
    public Release create(CreateRelease command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.validateCreate(command, principal.tenantId());
        authorizedStores.require(command.targetStoreIds());
        String requestSha = ReleaseRules.requestSha(command.artifactType(), command.version(), command.channel(),
            command.objectKey(), command.artifactSha256(), command.keyVersion(), command.buildCommit(),
            command.sbomSha256(), command.signatureBase64(), new TreeSet<>(command.targetStoreIds()),
            ReleaseRules.manifestSha(command, principal.tenantId()));
        Optional<CommandResult> previous = repository.findCommand(principal.tenantId(), "CREATE_RELEASE", command.idempotencyKey());
        if (previous.isPresent()) return existingRelease(principal.tenantId(), previous.get(), requestSha);
        Instant now = clock.instant();
        Release release = new Release(ids.next(), principal.tenantId(), command.artifactType(), command.version(),
            command.channel(), command.objectKey(), command.artifactSha256(), command.signatureBase64(),
            command.keyVersion(), command.buildCommit(), command.sbomSha256(),
            ReleaseRules.manifestSha(command, principal.tenantId()), command.compatibility(),
            Set.copyOf(command.targetStoreIds()), ReleaseState.DRAFT, 0, now);
        repository.insertRelease(release, requestSha, principal.userId(), correlationId());
        repository.appendCommand(principal.tenantId(), "CREATE_RELEASE", command.idempotencyKey(), requestSha,
            release.releaseId(), "DRAFT", principal.userId(), now);
        return release;
    }

    /** 重新读取对象并验证摘要、签名和密钥版本后冻结清单。 */
    @Transactional
    public Release verifyAndSign(ReleaseCommand command) {
        return transitionRelease(command, "VERIFY_RELEASE", ReleaseState.DRAFT, ReleaseState.SIGNED, true);
    }

    /** 将已签名发布物登记为可建立灰度批次。 */
    @Transactional
    public Release stage(ReleaseCommand command) {
        return transitionRelease(command, "STAGE_RELEASE", ReleaseState.SIGNED, ReleaseState.STAGED, false);
    }

    /** 吊销只阻止后续分发，不重写已经成功的终端执行历史。 */
    @Transactional
    public Release revoke(ReleaseCommand command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(command.releaseId(), "releaseId");
        ReleaseRules.idempotency(command.idempotencyKey());
        String requestSha = ReleaseRules.requestSha(command.releaseId(), "REVOKE_RELEASE");
        CommandResult previous = previous(principal.tenantId(), "REVOKE_RELEASE", command.idempotencyKey(), requestSha);
        if (previous != null) return requireRelease(principal.tenantId(), previous.aggregateId());
        Release release = requireRelease(principal.tenantId(), command.releaseId());
        if (release.state() == ReleaseState.REVOKED) {
            repository.appendCommand(principal.tenantId(), "REVOKE_RELEASE", command.idempotencyKey(), requestSha,
                release.releaseId(), "REVOKED", principal.userId(), clock.instant());
            return release;
        }
        ReleaseRules.releaseTransition(release.state(), ReleaseState.REVOKED);
        repository.transitionRelease(principal.tenantId(), release.releaseId(), release.state(), ReleaseState.REVOKED,
            release.versionNo(), principal.userId(), correlationId(), requestSha);
        repository.appendCommand(principal.tenantId(), "REVOKE_RELEASE", command.idempotencyKey(), requestSha,
            release.releaseId(), "REVOKED", principal.userId(), clock.instant());
        return requireRelease(principal.tenantId(), release.releaseId());
    }

    /** 创建灰度批次；范围只能收窄，首轮灰度比例不超过25%。 */
    @Transactional
    public Rollout createRollout(CreateRollout command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(command.releaseId(), "releaseId");
        Release release = requireRelease(principal.tenantId(), command.releaseId());
        if (release.state() != ReleaseState.STAGED) throw new ServiceException("UPG-STATE-004: 仅STAGED发布物可建立批次", 409);
        ReleaseRules.validateRollout(command, release);
        authorizedStores.require(command.targetStoreIds());
        String requestSha = ReleaseRules.requestSha(command.releaseId(), new TreeSet<>(command.targetStoreIds()), command.canaryPercent());
        Optional<CommandResult> previous = repository.findCommand(principal.tenantId(), "CREATE_ROLLOUT", command.idempotencyKey());
        if (previous.isPresent()) return existingRollout(principal.tenantId(), previous.get(), requestSha);
        Rollout rollout = new Rollout(ids.next(), principal.tenantId(), release.releaseId(),
            Set.copyOf(command.targetStoreIds()), command.canaryPercent(), RolloutState.PLANNED, 0, clock.instant());
        repository.insertRollout(rollout, requestSha, principal.userId(), correlationId());
        repository.appendCommand(principal.tenantId(), "CREATE_ROLLOUT", command.idempotencyKey(), requestSha,
            rollout.rolloutId(), "PLANNED", principal.userId(), clock.instant());
        return rollout;
    }

    @Transactional public Rollout startCanary(String rolloutId, String idempotencyKey) {
        return transitionRollout(rolloutId, idempotencyKey, "START_CANARY", RolloutState.CANARY);
    }
    @Transactional public Rollout expand(String rolloutId, String idempotencyKey) {
        return transitionRollout(rolloutId, idempotencyKey, "EXPAND", RolloutState.ROLLING);
    }
    @Transactional public Rollout complete(String rolloutId, String idempotencyKey) {
        return transitionRollout(rolloutId, idempotencyKey, "COMPLETE", RolloutState.COMPLETED);
    }
    @Transactional public Rollout pause(String rolloutId, String idempotencyKey) {
        return transitionRollout(rolloutId, idempotencyKey, "PAUSE", RolloutState.PAUSED);
    }

    /** 为已登记虚构终端创建软件任务；生产遥测未接通时安全探针会失败关闭。 */
    @Transactional
    public TerminalTask assign(AssignTerminal command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(command.rolloutId(), "rolloutId");
        ReleaseRules.ulid(command.deviceId(), "deviceId");
        ReleaseRules.idempotency(command.idempotencyKey());
        Rollout rollout = requireRollout(principal.tenantId(), command.rolloutId());
        if (rollout.state() != RolloutState.CANARY && rollout.state() != RolloutState.ROLLING) {
            throw new ServiceException("UPG-STATE-005: 批次未处于可分配状态", 409);
        }
        Release release = requireRelease(principal.tenantId(), rollout.releaseId());
        if (release.state() != ReleaseState.STAGED) throw new ServiceException("UPG-STATE-006: 发布物已吊销或未就绪", 409);
        TrustedTerminal terminal = terminalRegistry.require(principal.tenantId(), command.deviceId());
        ReleaseRules.requireTerminalReady(release, rollout, terminal, safetyProbe.inspect(terminal, release));
        String requestSha = ReleaseRules.requestSha(command.rolloutId(), command.deviceId(), release.manifestSha256());
        Optional<CommandResult> previous = repository.findCommand(principal.tenantId(), "ASSIGN_TERMINAL", command.idempotencyKey());
        if (previous.isPresent()) return existingTask(principal.tenantId(), previous.get(), requestSha);
        TerminalTask task = new TerminalTask(ids.next(), principal.tenantId(), rollout.rolloutId(), release.releaseId(),
            terminal.deviceId(), terminal.storeId(), TaskState.PLANNED, requestSha, 0, clock.instant());
        repository.insertTask(task, requestSha, principal.userId(), correlationId());
        repository.appendCommand(principal.tenantId(), "ASSIGN_TERMINAL", command.idempotencyKey(), requestSha,
            task.taskId(), "PLANNED", principal.userId(), clock.instant());
        return task;
    }

    /** 记录软件生成的执行观察；摘要/签名错误和非法顺序不会被重试掩盖。 */
    @Transactional
    public TerminalTask observe(RecordObservation command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(command.taskId(), "taskId");
        ReleaseRules.idempotency(command.idempotencyKey());
        ReleaseRules.sha(command.evidenceSha256(), "执行证据");
        TerminalTask task = requireTask(principal.tenantId(), command.taskId());
        Release release = requireRelease(principal.tenantId(), task.releaseId());
        String observedSha = command.artifactSha256() == null ? release.artifactSha256() : command.artifactSha256();
        ReleaseRules.sha(observedSha, "观察发布物");
        String requestSha = ReleaseRules.requestSha(task.taskId(), command.type(), observedSha, command.evidenceSha256());
        Optional<CommandResult> previous = repository.findCommand(principal.tenantId(), "OBSERVE_TASK", command.idempotencyKey());
        if (previous.isPresent()) return existingTask(principal.tenantId(), previous.get(), requestSha);
        TaskState target = ReleaseRules.taskTransition(release.artifactType(), task.state(), command.type(),
            release.artifactSha256(), observedSha);
        repository.transitionTask(principal.tenantId(), task.taskId(), task.state(), target, task.versionNo(),
            command.evidenceSha256(), principal.userId(), correlationId());
        repository.appendCommand(principal.tenantId(), "OBSERVE_TASK", command.idempotencyKey(), requestSha,
            task.taskId(), target.name(), principal.userId(), clock.instant());
        return requireTask(principal.tenantId(), task.taskId());
    }

    public Release getRelease(String releaseId) {
        String tenantId = tenantContext.requireTenantId();
        ReleaseRules.ulid(releaseId, "releaseId");
        return requireRelease(tenantId, releaseId);
    }
    public Rollout getRollout(String rolloutId) {
        String tenantId = tenantContext.requireTenantId();
        ReleaseRules.ulid(rolloutId, "rolloutId");
        return requireRollout(tenantId, rolloutId);
    }

    private Release transitionRelease(ReleaseCommand command, String action, ReleaseState from, ReleaseState to, boolean verify) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(command.releaseId(), "releaseId"); ReleaseRules.idempotency(command.idempotencyKey());
        String requestSha = ReleaseRules.requestSha(command.releaseId(), action);
        CommandResult previous = previous(principal.tenantId(), action, command.idempotencyKey(), requestSha);
        if (previous != null) return requireRelease(principal.tenantId(), previous.aggregateId());
        Release release = requireRelease(principal.tenantId(), command.releaseId());
        if (release.state() == to) {
            repository.appendCommand(principal.tenantId(), action, command.idempotencyKey(), requestSha,
                release.releaseId(), to.name(), principal.userId(), clock.instant());
            return release;
        }
        if (release.state() != from) throw new ServiceException("UPG-STATE-007: 发布物状态不满足命令前置条件", 409);
        ReleaseRules.releaseTransition(from, to);
        if (verify) ReleaseRules.requireArtifact(release, artifactVerifier.verify(release));
        repository.transitionRelease(principal.tenantId(), release.releaseId(), from, to, release.versionNo(),
            principal.userId(), correlationId(), requestSha);
        repository.appendCommand(principal.tenantId(), action, command.idempotencyKey(), requestSha,
            release.releaseId(), to.name(), principal.userId(), clock.instant());
        return requireRelease(principal.tenantId(), release.releaseId());
    }

    private Rollout transitionRollout(String rolloutId, String idempotencyKey, String action, RolloutState target) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReleaseRules.ulid(rolloutId, "rolloutId"); ReleaseRules.idempotency(idempotencyKey);
        String requestSha = ReleaseRules.requestSha(rolloutId, action);
        CommandResult previous = previous(principal.tenantId(), action, idempotencyKey, requestSha);
        if (previous != null) return requireRollout(principal.tenantId(), previous.aggregateId());
        Rollout rollout = requireRollout(principal.tenantId(), rolloutId);
        if (rollout.state() == target) {
            repository.appendCommand(principal.tenantId(), action, idempotencyKey, requestSha, rolloutId,
                target.name(), principal.userId(), clock.instant());
            return rollout;
        }
        TaskSummary summary = repository.summarizeTasks(principal.tenantId(), rolloutId);
        ReleaseRules.rolloutTransition(rollout.state(), target, summary);
        repository.transitionRollout(principal.tenantId(), rolloutId, rollout.state(), target, rollout.versionNo(),
            principal.userId(), correlationId(), requestSha);
        repository.appendCommand(principal.tenantId(), action, idempotencyKey, requestSha, rolloutId,
            target.name(), principal.userId(), clock.instant());
        return requireRollout(principal.tenantId(), rolloutId);
    }

    private CommandResult previous(String tenantId, String type, String key, String requestSha) {
        Optional<CommandResult> previous = repository.findCommand(tenantId, type, key);
        if (previous.isEmpty()) return null;
        same(previous.get().requestSha256(), requestSha);
        return previous.get();
    }
    private Release existingRelease(String tenantId, CommandResult previous, String requestSha) {
        same(previous.requestSha256(), requestSha); return requireRelease(tenantId, previous.aggregateId());
    }
    private Rollout existingRollout(String tenantId, CommandResult previous, String requestSha) {
        same(previous.requestSha256(), requestSha); return requireRollout(tenantId, previous.aggregateId());
    }
    private TerminalTask existingTask(String tenantId, CommandResult previous, String requestSha) {
        same(previous.requestSha256(), requestSha); return requireTask(tenantId, previous.aggregateId());
    }
    private static void same(String expected, String actual) {
        if (!Objects.equals(expected, actual)) throw new ServiceException("UPG-IDEMP-002: 同幂等键异内容", 409);
    }
    private Release requireRelease(String tenantId, String id) { return repository.findRelease(tenantId, id)
        .orElseThrow(() -> new ServiceException("UPG-NOTFOUND-001: 发布物不存在或不可见", 404)); }
    private Rollout requireRollout(String tenantId, String id) { return repository.findRollout(tenantId, id)
        .orElseThrow(() -> new ServiceException("UPG-NOTFOUND-002: 灰度批次不存在或不可见", 404)); }
    private TerminalTask requireTask(String tenantId, String id) { return repository.findTask(tenantId, id)
        .orElseThrow(() -> new ServiceException("UPG-NOTFOUND-003: 终端任务不存在或不可见", 404)); }
    private String correlationId() { String value = MDC.get("correlationId"); return value == null || value.isBlank() ? ids.next() : value; }
}
