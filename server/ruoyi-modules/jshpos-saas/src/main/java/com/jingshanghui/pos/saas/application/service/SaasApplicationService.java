package com.jingshanghui.pos.saas.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.TenantProvisioningPort;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort.*;
import com.jingshanghui.pos.saas.domain.SaasIdGenerator;
import com.jingshanghui.pos.saas.domain.SaasRules;
import com.jingshanghui.pos.saas.domain.SaasStates.ApplicationState;
import com.jingshanghui.pos.saas.domain.SaasStates.EntitlementState;
import com.jingshanghui.pos.saas.domain.SaasStates.LifecycleState;
import com.jingshanghui.pos.saas.domain.SaasStates;
import com.jingshanghui.pos.saas.infrastructure.persistence.entity.SaasPlanEntity;
import com.jingshanghui.pos.saas.infrastructure.persistence.mapper.SaasPlanMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * T2-SAA-001 正式应用编排。
 *
 * <p>本服务只写 SaaS Owner 自有事实；技术租户只能通过 Foundation 端口创建和停启。
 * 所有状态迁移、幂等结果、审计与 Outbox 在同一 MySQL 事务内提交。</p>
 */
@Service
@RequiredArgsConstructor
public class SaasApplicationService {
    private static final String PLATFORM_SCOPE = "PLATFORM";
    private static final Set<String> REQUIRED_CHECKPOINTS = Set.of("TECHNICAL_TENANT", "ENTITLEMENT_BINDING", "OWNER_INITIALIZATION");

    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final TenantProvisioningPort tenantProvisioning;
    private final SaasPersistencePort persistence;
    private final SaasPlanMapper planMapper;
    private final SaasIdGenerator ids;
    private final Clock clock;

    /** 创建不含真实 PII 的商户申请。 */
    @Transactional
    public ApplicationDetail createApplication(CreateApplication command) {
        TrustedPrincipal actor = platformActor();
        String code = SaasRules.code(command.applicationCode(), "applicationCode");
        String company = limited(command.companyName(), "companyName", 128);
        String industry = SaasRules.code(command.industry(), "industry");
        requireActivePlan(command.planId());
        String key = SaasRules.key(command.idempotencyKey());
        CanonicalJson.Result payload = canonical(Map.of("applicationCode", code, "companyName", company,
            "industry", industry, "planId", command.planId()));
        CommandRecord replay = replay(PLATFORM_SCOPE, "CREATE_APPLICATION", key, payload.sha256());
        if (replay != null) return detail(replay.resultRef());
        if (persistence.findApplicationByCode(code) != null) throw conflict("SAA-APP-001", "申请号已存在");
        LocalDateTime at = now(); String applicationId = ids.next();
        persistence.insertApplication(new ApplicationWrite(applicationId, code, company, industry, command.planId(),
            ApplicationState.DRAFT.name(), actor.userId(), payload.sha256(), at));
        appendApplicationState(applicationId, null, null, ApplicationState.DRAFT, payload.sha256(),
            command.correlationId(), actor.userId(), at);
        appendAudit(null, "APPLICATION", applicationId, "CREATE_APPLICATION", payload.sha256(), command.correlationId(),
            actor.userId(), "商户申请已创建", at);
        record(PLATFORM_SCOPE, "CREATE_APPLICATION", key, payload.sha256(), applicationId, ApplicationState.DRAFT.name(), at);
        return detail(applicationId);
    }

    /** 预检套餐与申请数据，失败关闭且不创建技术租户。 */
    @Transactional
    public ApplicationDetail preflight(ApplicationCommand command) {
        Action action = appAction("PREFLIGHT_APPLICATION", command, Map.of());
        ApplicationDetail replay = replayApplication(action); if (replay != null) return replay;
        ApplicationRecord app = lock(command.applicationId());
        requireState(app, ApplicationState.DRAFT, ApplicationState.PREFLIGHT_FAILED);
        app = transition(app, ApplicationState.PREFLIGHTING, action, null, null);
        SaasPlanEntity plan = requireActivePlan(app.planId());
        EntitlementVersionRecord version = persistence.findEffectiveVersion(plan.getPlanId(), now());
        ApplicationState target = version == null ? ApplicationState.PREFLIGHT_FAILED : ApplicationState.READY;
        app = transition(app, target, action, null, null);
        record(action.scope(), action.operation(), action.key(), action.hash(), app.applicationId(), app.state(), now());
        return detail(app.applicationId());
    }

    /** 审批人与申请提交人强制分离。 */
    @Transactional
    public ApplicationDetail approve(ApplicationCommand command) {
        Action action = appAction("APPROVE_APPLICATION", command, Map.of("reason", limited(command.reason(), "reason", 256)));
        ApplicationDetail replay = replayApplication(action); if (replay != null) return replay;
        ApplicationRecord app = lock(command.applicationId()); requireState(app, ApplicationState.READY);
        SaasRules.separate(app.submitterUserId(), action.actor().userId());
        app = transition(app, ApplicationState.APPROVED, action, null, action.actor().userId());
        record(action.scope(), action.operation(), action.key(), action.hash(), app.applicationId(), app.state(), now());
        return detail(app.applicationId());
    }

    /**
     * 创建技术租户并冻结有效权益；一次性密码仅在调用栈内存在，方法退出前清零。
     */
    @Transactional
    public ApplicationDetail provision(ProvisionCommand command) {
        TrustedPrincipal actor = platformActor();
        char[] password = Objects.requireNonNull(command.bootstrapPassword(), "bootstrapPassword");
        String secretHash = secretHash(password);
        try {
            String key = SaasRules.key(command.idempotencyKey());
            CanonicalJson.Result payload = canonical(new LinkedHashMap<>(Map.of(
                "applicationId", command.applicationId(), "contactName", limited(command.contactName(), "contactName", 64),
                "contactPhone", limited(command.contactPhone(), "contactPhone", 32),
                "bootstrapUsername", limited(command.bootstrapUsername(), "bootstrapUsername", 64), "secretSha256", secretHash)));
            Action action = new Action(PLATFORM_SCOPE, "PROVISION_TENANT", key, payload.sha256(),
                safeCorrelation(command.correlationId()), actor);
            ApplicationDetail replay = replayApplication(action); if (replay != null) return replay;
            ApplicationRecord app = lock(command.applicationId()); requireState(app, ApplicationState.APPROVED);
            SaasPlanEntity plan = requireActivePlan(app.planId());
            EntitlementVersionRecord version = persistence.findEffectiveVersion(plan.getPlanId(), now());
            if (version == null) throw conflict("SAA-ENT-002", "没有可绑定的有效权益版本");
            app = transition(app, ApplicationState.PROVISIONING, action, null, null);
            TenantProvisioningPort.ProvisionedTenant provisioned = tenantProvisioning.provision(
                new TenantProvisioningPort.ProvisionTenant(app.companyName(), command.contactName(), command.contactPhone(),
                    command.bootstrapUsername(), password, plan.getPlatformPackageId(), plan.getAccountLimit()));
            app = transition(app, ApplicationState.INITIALIZING, action, provisioned, null);
            String bindingHash = canonical(Map.of("tenantId", provisioned.tenantId(), "planId", plan.getPlanId(),
                "versionId", version.versionId())).sha256();
            persistence.bindTenant(new TenantBindingWrite(provisioned.tenantId(), plan.getPlanId(), version.versionId(),
                LifecycleState.PENDING_ACTIVATION.name(), bindingHash, now()));
            for (EntitlementItemRecord item : persistence.listItems(version.versionId())) {
                if (item.quotaLimit() != null) persistence.seedQuota(new TenantQuotaWrite(provisioned.tenantId(), item.featureCode(), item.quotaLimit(), now()));
            }
            checkpoint(app, "TECHNICAL_TENANT", secretHash);
            checkpoint(app, "ENTITLEMENT_BINDING", bindingHash);
            appendLifecycle(provisioned.tenantId(), null, LifecycleState.PENDING_ACTIVATION, "技术租户待激活",
                action, now());
            record(action.scope(), action.operation(), action.key(), action.hash(), app.applicationId(), app.state(), now());
            return detail(app.applicationId());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /** 初始化 Saga 只记录受控检查点，不直接写其他 Owner 私有表。 */
    @Transactional
    public ApplicationDetail initialize(ApplicationCommand command) {
        Action action = appAction("INITIALIZE_TENANT", command, Map.of());
        ApplicationDetail replay = replayApplication(action); if (replay != null) return replay;
        ApplicationRecord app = lock(command.applicationId()); requireState(app, ApplicationState.INITIALIZING);
        if (app.tenantId() == null || persistence.findTenantEntitlement(app.tenantId()) == null) {
            throw conflict("SAA-INIT-001", "技术租户或权益绑定缺失");
        }
        checkpoint(app, "OWNER_INITIALIZATION", canonical(Map.of("tenantId", app.tenantId(), "mode", "FORMAL_PORTS_READY")).sha256());
        record(action.scope(), action.operation(), action.key(), action.hash(), app.applicationId(), app.state(), now());
        appendAudit(app.tenantId(), "APPLICATION", app.applicationId(), action.operation(), action.hash(),
            action.correlation(), action.actor().userId(), "初始化检查点完成", now());
        return detail(app.applicationId());
    }

    /** 只有全部检查点存在时才激活技术租户和商业生命周期。 */
    @Transactional
    public ApplicationDetail activate(ApplicationCommand command) {
        Action action = appAction("ACTIVATE_TENANT", command, Map.of());
        ApplicationDetail replay = replayApplication(action); if (replay != null) return replay;
        ApplicationRecord app = lock(command.applicationId()); requireState(app, ApplicationState.INITIALIZING);
        if (!new HashSet<>(persistence.listCheckpoints(app.applicationId())).containsAll(REQUIRED_CHECKPOINTS)) {
            throw conflict("SAA-INIT-002", "初始化检查点不完整");
        }
        TenantEntitlementRecord tenant = requireTenant(app.tenantId());
        changeLifecycle(tenant, LifecycleState.ACTIVE, "完成初始化后激活", action);
        tenantProvisioning.changeStatus(app.tenantId(), TenantProvisioningPort.TechnicalTenantStatus.ACTIVE);
        app = transition(app, ApplicationState.ACTIVE, action, null, null);
        record(action.scope(), action.operation(), action.key(), action.hash(), app.applicationId(), app.state(), now());
        return detail(app.applicationId());
    }

    /** 使用 MyBatis-Plus 创建普通套餐主数据。 */
    @Transactional
    public SaasPlanEntity createPlan(CreatePlan command) {
        TrustedPrincipal actor = platformActor(); String key = SaasRules.key(command.idempotencyKey());
        String code = SaasRules.code(command.planCode(), "planCode");
        SaasRules.positive(command.platformPackageId(), "platformPackageId"); SaasRules.positive(command.accountLimit(), "accountLimit");
        CanonicalJson.Result payload = canonical(Map.of("planCode", code, "planName", limited(command.planName(), "planName", 64),
            "platformPackageId", command.platformPackageId(), "accountLimit", command.accountLimit()));
        CommandRecord existing = replay(PLATFORM_SCOPE, "CREATE_PLAN", key, payload.sha256());
        if (existing != null) return requirePlan(Long.valueOf(existing.resultRef()));
        if (planMapper.selectCount(new LambdaQueryWrapper<SaasPlanEntity>().eq(SaasPlanEntity::getPlanCode, code)) > 0) {
            throw conflict("SAA-PLAN-001", "套餐代码已存在");
        }
        SaasPlanEntity entity = new SaasPlanEntity(); entity.setPlanCode(code); entity.setPlanName(command.planName().strip());
        entity.setPlatformPackageId(command.platformPackageId()); entity.setAccountLimit(command.accountLimit());
        entity.setStatus("ACTIVE"); entity.setCreatedAt(now()); entity.setUpdatedAt(entity.getCreatedAt()); planMapper.insert(entity);
        record(PLATFORM_SCOPE, "CREATE_PLAN", key, payload.sha256(), entity.getPlanId().toString(), "ACTIVE", now());
        appendAudit(null, "PLAN", entity.getPlanId().toString(), "CREATE_PLAN", payload.sha256(), command.correlationId(), actor.userId(), "套餐主数据已创建", now());
        return entity;
    }

    /** 创建内容冻结的权益草稿版本。 */
    @Transactional
    public EntitlementVersionRecord createVersion(CreateEntitlementVersion command) {
        TrustedPrincipal actor = platformActor(); requireActivePlan(command.planId());
        if (command.versionNo() == null || command.versionNo() <= 0) throw conflict("SAA-ENT-003", "版本号必须大于零");
        SaasRules.window(toInstant(command.effectiveAt()), command.expiresAt() == null ? null : toInstant(command.expiresAt()));
        if (command.items() == null || command.items().isEmpty()) throw conflict("SAA-ENT-004", "权益条目不能为空");
        List<Map<String,Object>> normalized = new ArrayList<>(); Set<String> codes = new HashSet<>();
        for (EntitlementItemInput item : command.items()) {
            String code = SaasRules.code(item.featureCode(), "featureCode");
            if (!codes.add(code)) throw conflict("SAA-ENT-005", "权益代码重复");
            if (item.quotaLimit() != null && item.quotaLimit() < 0) throw conflict("SAA-ENT-006", "配额不能为负数");
            normalized.add(new TreeMap<>(Map.of("featureCode", code, "enabled", Boolean.TRUE.equals(item.enabled()),
                "quotaLimit", item.quotaLimit() == null ? -1L : item.quotaLimit())));
        }
        normalized.sort(Comparator.comparing(v -> v.get("featureCode").toString()));
        CanonicalJson.Result payload = canonical(Map.of("planId", command.planId(), "versionNo", command.versionNo(),
            "effectiveAt", command.effectiveAt().toString(), "expiresAt", command.expiresAt() == null ? "" : command.expiresAt().toString(), "items", normalized));
        String key = SaasRules.key(command.idempotencyKey()); CommandRecord replay = replay(PLATFORM_SCOPE, "CREATE_ENTITLEMENT_VERSION", key, payload.sha256());
        if (replay != null) return requireVersion(replay.resultRef());
        if (persistence.findVersionByPlanNo(command.planId(), command.versionNo()) != null) throw conflict("SAA-ENT-007", "套餐版本号已存在");
        String versionId = ids.next(); LocalDateTime at = now();
        persistence.insertVersion(new VersionWrite(versionId, command.planId(), command.versionNo(), EntitlementState.DRAFT.name(),
            command.effectiveAt(), command.expiresAt(), payload.sha256(), actor.userId(), at));
        for (Map<String,Object> item : normalized) {
            CanonicalJson.Result itemJson = canonical(item); long quota = (long) item.get("quotaLimit");
            persistence.insertItem(new ItemWrite(ids.next(), versionId, item.get("featureCode").toString(),
                (boolean) item.get("enabled"), quota < 0 ? null : quota, itemJson.sha256()));
        }
        record(PLATFORM_SCOPE, "CREATE_ENTITLEMENT_VERSION", key, payload.sha256(), versionId, EntitlementState.DRAFT.name(), at);
        appendAudit(null, "ENTITLEMENT", versionId, "CREATE_ENTITLEMENT_VERSION", payload.sha256(), command.correlationId(), actor.userId(), "权益版本草稿已创建", at);
        return requireVersion(versionId);
    }

    @Transactional public EntitlementVersionRecord validateVersion(EntitlementCommand command) { return entitlementTransition(command, "VALIDATE_ENTITLEMENT", EntitlementState.DRAFT, EntitlementState.VALIDATING, EntitlementState.READY, false); }
    @Transactional public EntitlementVersionRecord approveVersion(EntitlementCommand command) { return entitlementTransition(command, "APPROVE_ENTITLEMENT", EntitlementState.READY, EntitlementState.APPROVED, null, true); }

    /** 发布前校验有效窗口无重叠，已发布内容不可再修改。 */
    @Transactional
    public EntitlementVersionRecord publishVersion(EntitlementCommand command) {
        Action action = entitlementAction("PUBLISH_ENTITLEMENT", command); EntitlementVersionRecord replay = replayVersion(action); if (replay != null) return replay;
        EntitlementVersionRecord version = lockVersion(command.versionId()); requireState(version, EntitlementState.APPROVED);
        if (persistence.countOverlappingVersions(version.planId(), version.versionId(), version.effectiveAt(), version.expiresAt()) != 0) {
            throw conflict("SAA-ENT-008", "权益生效窗口重叠");
        }
        version = transition(version, EntitlementState.PUBLISHED, action, null);
        record(action.scope(), action.operation(), action.key(), action.hash(), version.versionId(), version.state(), now()); return version;
    }

    /** 到达生效时间后显式切换为 EFFECTIVE。 */
    @Transactional
    public EntitlementVersionRecord activateVersion(EntitlementCommand command) {
        Action action = entitlementAction("ACTIVATE_ENTITLEMENT", command); EntitlementVersionRecord replay = replayVersion(action); if (replay != null) return replay;
        EntitlementVersionRecord version = lockVersion(command.versionId()); requireState(version, EntitlementState.PUBLISHED);
        LocalDateTime at = now(); if (at.isBefore(version.effectiveAt()) || (version.expiresAt() != null && !at.isBefore(version.expiresAt()))) throw conflict("SAA-ENT-009", "权益版本尚未进入有效窗口");
        version = transition(version, EntitlementState.EFFECTIVE, action, null);
        record(action.scope(), action.operation(), action.key(), action.hash(), version.versionId(), version.state(), now()); return version;
    }

    /** 暂停租户并关闭正常业务能力，恢复类能力由授权服务保留。 */
    @Transactional public TenantEntitlementRecord suspend(LifecycleCommand c) { return lifecycle(c, "SUSPEND_TENANT", LifecycleState.SUSPENSION_PENDING, LifecycleState.SUSPENDED, TenantProvisioningPort.TechnicalTenantStatus.DISABLED); }
    @Transactional public TenantEntitlementRecord deactivate(LifecycleCommand c) { return lifecycle(c, "DEACTIVATE_TENANT", LifecycleState.DEACTIVATION_PENDING, LifecycleState.DEACTIVATED, TenantProvisioningPort.TechnicalTenantStatus.DISABLED); }
    @Transactional public TenantEntitlementRecord restore(LifecycleCommand c) { return lifecycle(c, "RESTORE_TENANT", LifecycleState.RESTORING, LifecycleState.ACTIVE, TenantProvisioningPort.TechnicalTenantStatus.ACTIVE); }

    @Transactional
    public TenantEntitlementRecord requestTermination(LifecycleCommand command) {
        Action action = lifecycleAction("REQUEST_TERMINATION", command); TenantEntitlementRecord replay = replayTenant(action); if (replay != null) return replay;
        TenantEntitlementRecord tenant = requireTenant(command.tenantId());
        LifecycleState state = LifecycleState.valueOf(tenant.lifecycleState());
        if (!Set.of(LifecycleState.ACTIVE, LifecycleState.SUSPENDED, LifecycleState.DEACTIVATED).contains(state)) throw conflict("SAA-LIFE-001", "当前状态不能申请注销");
        tenant = changeLifecycle(tenant, LifecycleState.TERMINATION_REQUESTED, command.reason(), action);
        tenantProvisioning.changeStatus(command.tenantId(), TenantProvisioningPort.TechnicalTenantStatus.DISABLED);
        record(action.scope(), action.operation(), action.key(), action.hash(), tenant.tenantId(), tenant.lifecycleState(), now()); return tenant;
    }

    /** 逻辑注销只追加状态，不删除任何业务历史。 */
    @Transactional
    public TenantEntitlementRecord terminateLogical(LifecycleCommand command) {
        Action action = lifecycleAction("TERMINATE_LOGICAL", command); TenantEntitlementRecord replay = replayTenant(action); if (replay != null) return replay;
        TenantEntitlementRecord tenant = requireTenant(command.tenantId());
        tenant = changeLifecycle(tenant, LifecycleState.TERMINATED_LOGICAL, command.reason(), action);
        record(action.scope(), action.operation(), action.key(), action.hash(), tenant.tenantId(), tenant.lifecycleState(), now()); return tenant;
    }

    @Transactional(readOnly = true)
    public ApplicationDetail detail(String applicationId) {
        platformActor(); ApplicationRecord app = persistence.findApplication(applicationId);
        if (app == null) throw new ServiceException("SAA-APP-404: 商户申请不存在", 404);
        return new ApplicationDetail(app, persistence.listCheckpoints(applicationId), app.tenantId() == null ? null : persistence.findTenantEntitlement(app.tenantId()));
    }

    private EntitlementVersionRecord entitlementTransition(EntitlementCommand command, String operation,
        EntitlementState from, EntitlementState middle, EntitlementState target, boolean approval) {
        Action action = entitlementAction(operation, command); EntitlementVersionRecord replay = replayVersion(action); if (replay != null) return replay;
        EntitlementVersionRecord version = lockVersion(command.versionId()); requireState(version, from);
        if (approval) SaasRules.separate(version.creatorUserId(), action.actor().userId());
        version = transition(version, middle, action, approval ? action.actor().userId() : null);
        if (target != null) version = transition(version, target, action, null);
        record(action.scope(), action.operation(), action.key(), action.hash(), version.versionId(), version.state(), now()); return version;
    }

    private TenantEntitlementRecord lifecycle(LifecycleCommand command, String operation, LifecycleState middle,
        LifecycleState target, TenantProvisioningPort.TechnicalTenantStatus technicalStatus) {
        Action action = lifecycleAction(operation, command); TenantEntitlementRecord replay = replayTenant(action); if (replay != null) return replay;
        TenantEntitlementRecord tenant = requireTenant(command.tenantId());
        tenant = changeLifecycle(tenant, middle, command.reason(), action);
        tenantProvisioning.changeStatus(command.tenantId(), technicalStatus);
        tenant = changeLifecycle(tenant, target, command.reason(), action);
        record(action.scope(), action.operation(), action.key(), action.hash(), tenant.tenantId(), tenant.lifecycleState(), now()); return tenant;
    }

    private ApplicationRecord transition(ApplicationRecord app, ApplicationState to, Action action,
        TenantProvisioningPort.ProvisionedTenant provisioned, Long approver) {
        ApplicationState from = ApplicationState.valueOf(app.state()); SaasStates.require(from, to); LocalDateTime at = now();
        int changed = persistence.changeApplication(new ApplicationChange(app.applicationId(), from.name(), to.name(), app.recordVersion(),
            provisioned == null ? null : provisioned.tenantId(), provisioned == null ? null : provisioned.technicalRecordId(), approver, at));
        if (changed != 1) throw conflict("SAA-CONC-001", "申请已被并发修改");
        String tenantId = provisioned == null ? app.tenantId() : provisioned.tenantId();
        appendApplicationState(app.applicationId(), tenantId, from, to, action.hash(), action.correlation(), action.actor().userId(), at);
        appendAudit(tenantId, "APPLICATION", app.applicationId(), action.operation(), action.hash(), action.correlation(), action.actor().userId(), from + "→" + to, at);
        appendOutbox(tenantId, "APPLICATION", app.applicationId(), "saas.application.state-changed.v1", Map.of("from", from.name(), "to", to.name()), action.correlation(), at);
        return lock(app.applicationId());
    }

    private EntitlementVersionRecord transition(EntitlementVersionRecord version, EntitlementState to, Action action, Long approver) {
        EntitlementState from = EntitlementState.valueOf(version.state()); SaasStates.require(from, to); LocalDateTime at = now();
        if (persistence.changeVersion(new VersionChange(version.versionId(), from.name(), to.name(), version.recordVersion(), approver, at)) != 1) throw conflict("SAA-CONC-002", "权益版本已被并发修改");
        appendAudit(null, "ENTITLEMENT", version.versionId(), action.operation(), action.hash(), action.correlation(), action.actor().userId(), from + "→" + to, at);
        appendOutbox(null, "ENTITLEMENT", version.versionId(), "saas.entitlement.state-changed.v1", Map.of("from", from.name(), "to", to.name()), action.correlation(), at);
        return lockVersion(version.versionId());
    }

    private TenantEntitlementRecord changeLifecycle(TenantEntitlementRecord tenant, LifecycleState to, String reason, Action action) {
        LifecycleState from = LifecycleState.valueOf(tenant.lifecycleState()); SaasStates.require(from, to); LocalDateTime at = now();
        if (persistence.changeLifecycle(new LifecycleChange(tenant.tenantId(), from.name(), to.name(), tenant.lifecycleVersion(), at)) != 1) throw conflict("SAA-CONC-003", "租户生命周期已被并发修改");
        appendLifecycle(tenant.tenantId(), from, to, reason, action, at);
        appendAudit(tenant.tenantId(), "TENANT_LIFECYCLE", tenant.tenantId(), action.operation(), action.hash(), action.correlation(), action.actor().userId(), from + "→" + to, at);
        appendOutbox(tenant.tenantId(), "TENANT_LIFECYCLE", tenant.tenantId(), "saas.tenant.lifecycle-changed.v1", Map.of("from", from.name(), "to", to.name()), action.correlation(), at);
        return requireTenant(tenant.tenantId());
    }

    private void checkpoint(ApplicationRecord app, String step, String resultHash) {
        if (!persistence.listCheckpoints(app.applicationId()).contains(step)) persistence.insertCheckpoint(new CheckpointWrite(ids.next(), app.applicationId(), app.tenantId(), step, SaasRules.hash(resultHash), now()));
    }

    private Action appAction(String operation, ApplicationCommand command, Map<String,Object> extra) {
        TrustedPrincipal actor = platformActor(); Map<String,Object> values = new LinkedHashMap<>(extra); values.put("applicationId", command.applicationId());
        CanonicalJson.Result payload = canonical(values); return new Action(PLATFORM_SCOPE, operation, SaasRules.key(command.idempotencyKey()), payload.sha256(), safeCorrelation(command.correlationId()), actor);
    }
    private Action entitlementAction(String operation, EntitlementCommand command) { TrustedPrincipal actor=platformActor(); return new Action(PLATFORM_SCOPE,operation,SaasRules.key(command.idempotencyKey()),canonical(Map.of("versionId",command.versionId())).sha256(),safeCorrelation(command.correlationId()),actor); }
    private Action lifecycleAction(String operation, LifecycleCommand command) { TrustedPrincipal actor=platformActor(); return new Action(command.tenantId(),operation,SaasRules.key(command.idempotencyKey()),canonical(Map.of("tenantId",command.tenantId(),"reason",limited(command.reason(),"reason",256))).sha256(),safeCorrelation(command.correlationId()),actor); }

    private ApplicationDetail replayApplication(Action a){CommandRecord r=replay(a.scope(),a.operation(),a.key(),a.hash());return r==null?null:detail(r.resultRef());}
    private EntitlementVersionRecord replayVersion(Action a){CommandRecord r=replay(a.scope(),a.operation(),a.key(),a.hash());return r==null?null:requireVersion(r.resultRef());}
    private TenantEntitlementRecord replayTenant(Action a){CommandRecord r=replay(a.scope(),a.operation(),a.key(),a.hash());return r==null?null:requireTenant(r.resultRef());}
    private CommandRecord replay(String scope,String operation,String key,String hash){CommandRecord r=persistence.findCommand(scope,operation,key);if(r!=null)SaasRules.sameHash(r.requestSha256(),hash);return r;}
    private void record(String scope,String operation,String key,String hash,String ref,String state,LocalDateTime at){persistence.insertCommand(new CommandWrite(ids.next(),scope,operation,key,hash,ref,state,at));}

    private ApplicationRecord lock(String id){ApplicationRecord r=persistence.lockApplication(id);if(r==null)throw new ServiceException("SAA-APP-404: 商户申请不存在",404);return r;}
    private EntitlementVersionRecord lockVersion(String id){EntitlementVersionRecord r=persistence.lockVersion(id);if(r==null)throw new ServiceException("SAA-ENT-404: 权益版本不存在",404);return r;}
    private EntitlementVersionRecord requireVersion(String id){EntitlementVersionRecord r=persistence.findVersion(id);if(r==null)throw new ServiceException("SAA-ENT-404: 权益版本不存在",404);return r;}
    private TenantEntitlementRecord requireTenant(String id){TenantEntitlementRecord r=persistence.findTenantEntitlement(id);if(r==null)throw new ServiceException("SAA-TENANT-404: 商业租户不存在",404);return r;}
    private SaasPlanEntity requirePlan(Long id){SaasPlanEntity p=planMapper.selectById(id);if(p==null)throw new ServiceException("SAA-PLAN-404: 套餐不存在",404);return p;}
    private SaasPlanEntity requireActivePlan(Long id){SaasPlanEntity p=requirePlan(id);if(!"ACTIVE".equals(p.getStatus()))throw conflict("SAA-PLAN-002","套餐未启用");return p;}
    private void requireState(ApplicationRecord r,ApplicationState...allowed){if(Arrays.stream(allowed).noneMatch(s->s.name().equals(r.state())))throw conflict("SAA-STATE-004","申请状态不允许该操作");}
    private void requireState(EntitlementVersionRecord r,EntitlementState allowed){if(!allowed.name().equals(r.state()))throw conflict("SAA-STATE-005","权益状态不允许该操作");}
    private TrustedPrincipal platformActor(){authorization.requirePlatformAdministrator();return tenantContext.requirePrincipal();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);}
    private java.time.Instant toInstant(LocalDateTime value){if(value==null)return null;return value.toInstant(ZoneOffset.UTC);}
    private CanonicalJson.Result canonical(Map<String,?> source){Map<String,Object> copy=new LinkedHashMap<>();source.forEach(copy::put);return CanonicalJson.from(copy);}
    private String safeCorrelation(String value){String v=SaasRules.required(value,"correlationId");if(!v.matches("^[A-Za-z0-9._:-]{1,64}$"))throw conflict("SAA-CORR-001","关联标识格式非法");return v;}
    private String limited(String value,String field,int max){String v=SaasRules.required(value,field);if(v.length()>max)throw conflict("SAA-VALID-004",field+" 超长");return v;}
    private String secretHash(char[] secret){if(secret.length<12||secret.length>128)throw conflict("SAA-SECRET-001","一次性密码长度非法");try{var bytes=StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));byte[] data=new byte[bytes.remaining()];bytes.get(data);try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));}finally{Arrays.fill(data,(byte)0);}}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void appendApplicationState(String app,String tenant,ApplicationState from,ApplicationState to,String hash,String corr,Long actor,LocalDateTime at){persistence.appendApplicationState(new StateEventWrite(ids.next(),app,tenant,from==null?null:from.name(),to.name(),hash,safeCorrelation(corr),actor,at));}
    private void appendLifecycle(String tenant,LifecycleState from,LifecycleState to,String reason,Action action,LocalDateTime at){persistence.appendLifecycle(new LifecycleEventWrite(ids.next(),tenant,from==null?null:from.name(),to.name(),limited(reason,"reason",256),action.hash(),action.correlation(),action.actor().userId(),at));}
    private void appendAudit(String tenant,String type,String id,String action,String hash,String corr,Long actor,String summary,LocalDateTime at){persistence.appendAudit(new AuditWrite(ids.next(),tenant,type,id,action,"SUCCESS",hash,safeCorrelation(corr),actor,limited(summary,"summary",256),at));}
    private void appendOutbox(String tenant,String type,String id,String event,Map<String,Object> body,String corr,LocalDateTime at){CanonicalJson.Result p=canonical(body);persistence.appendOutbox(new OutboxWrite(ids.next(),tenant,type,id,event,p.json(),p.sha256(),safeCorrelation(corr),at));}
    private static ServiceException conflict(String code,String message){return new ServiceException(code+": "+message,409);}
    private record Action(String scope,String operation,String key,String hash,String correlation,TrustedPrincipal actor){}
}
