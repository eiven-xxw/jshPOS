package com.jingshanghui.pos.subscription.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort.ApplyAccessCommand;
import com.jingshanghui.pos.saas.application.port.SaasSubscriptionControlPort.TenantPlanSnapshot;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.*;
import com.jingshanghui.pos.subscription.application.port.SubscriptionPersistencePort;
import com.jingshanghui.pos.subscription.application.port.SubscriptionPersistencePort.*;
import com.jingshanghui.pos.subscription.domain.SubscriptionIdGenerator;
import com.jingshanghui.pos.subscription.domain.SubscriptionRules;
import com.jingshanghui.pos.subscription.domain.SubscriptionStates;
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
import java.util.Set;

/**
 * T2-SUB-001 正式应用编排。
 *
 * <p>订阅事实、期限、审计、通知意图与 Outbox 在一个事务中提交；SaaS 访问效果仅通过
 * 正式端口切换。任何失败均整体回滚，不会把到期推导为资金成功或物理删除历史。</p>
 */
@Service
@RequiredArgsConstructor
public class SubscriptionApplicationService {
    private static final String PLATFORM = "PLATFORM";
    private static final String JOB_CODE = "SUBSCRIPTION_EXPIRY_SCAN";
    private static final Set<String> RETAINED = Set.of("REFUND", "PAYMENT_AND_REFUND_QUERY", "RECONCILIATION",
        "AUDIT", "BACKUP_RESTORE", "LEGAL_EXPORT", "DATA_MIGRATION", "DATA_DELETION_REQUEST");
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final SaasSubscriptionControlPort saas;
    private final SubscriptionPersistencePort persistence;
    private final SubscriptionIdGenerator ids;
    private final Clock clock;

    /** 平台为已开户租户创建订阅草稿；目标租户必须由 SaaS 正式记录交叉校验。 */
    @Transactional
    public SubscriptionDetail create(CreateSubscription command) {
        TrustedPrincipal actor = platformActor(); String tenant = required(command.targetTenantId(), "targetTenantId");
        TenantPlanSnapshot plan = saas.requireTenantPlan(tenant);
        if (!"ACTIVE".equals(plan.lifecycleState())) throw conflict("SUB-SAA-006", "目标租户商业生命周期未激活");
        validateTerm(command.startsAt(), command.endsAt(), command.graceEndsAt(), command.businessTimeZone());
        String contract = SubscriptionRules.reference(command.contractRef(), "contractRef");
        String order = SubscriptionRules.reference(command.externalOrderRef(), "externalOrderRef");
        String policy = SubscriptionRules.degradationPolicy(command.degradationPolicyVersion());
        String key = SubscriptionRules.key(command.idempotencyKey()); String correlation = correlation(command.correlationId());
        CanonicalJson.Result payload = canonical(Map.of("tenantId", tenant, "contractRef", contract,
            "externalOrderRef", order, "startsAt", command.startsAt(), "endsAt", command.endsAt(),
            "graceEndsAt", command.graceEndsAt(), "businessTimeZone", command.businessTimeZone(),
            "degradationPolicyVersion", policy));
        CommandRecord replay = replay(PLATFORM, "CREATE_SUBSCRIPTION", key, payload.sha256());
        if (replay != null) return detailUnchecked(replay.resultRef());
        if (persistence.findByTenant(tenant) != null) throw conflict("SUB-CREATE-001", "租户已存在订阅");
        LocalDateTime at = now(); String id = ids.next();
        persistence.insertSubscription(new SubscriptionWrite(id, tenant, plan.planId(), plan.entitlementVersionId(),
            contract, order, "DRAFT", 0, 1, command.startsAt(), command.endsAt(), command.graceEndsAt(),
            SubscriptionRules.zone(command.businessTimeZone()), policy, payload.sha256(), at));
        appendTerm(id, tenant, 1, contract, order, command.startsAt(), command.endsAt(), command.graceEndsAt(), command.businessTimeZone(), at);
        appendState(tenant, id, null, "DRAFT", 0, 1, "CREATE_SUBSCRIPTION", "订阅草稿创建", payload.sha256(), correlation, actor.userId(), at);
        appendEvidence(tenant, id, "CREATE_SUBSCRIPTION", "DRAFT", payload.sha256(), correlation, actor.userId(), at);
        scheduleReminders(tenant, id, 1, command.endsAt(), payload.sha256(), correlation, at);
        record(PLATFORM, "CREATE_SUBSCRIPTION", key, payload.sha256(), id, "DRAFT", at);
        return detailUnchecked(id);
    }

    /** DRAFT → PENDING_ACTIVATION → ACTIVE，并在同一事务切换 SaaS NORMAL 访问。 */
    @Transactional public SubscriptionDetail activate(SubscriptionCommand command) {
        Action action = action("ACTIVATE_SUBSCRIPTION", command); SubscriptionDetail replay = replayDetail(action); if (replay != null) return replay;
        SubscriptionRecord record = requireLocked(command.subscriptionId()); requireState(record, "DRAFT");
        requireEffectiveNow(record.startsAt(),record.endsAt());
        record = transition(record, "PENDING_ACTIVATION", action, action.actor());
        record = transition(record, "ACTIVE", action, action.actor());
        applyAccess(record, action); finish(action, record); return detailUnchecked(record.subscriptionId());
    }

    /** 续期只追加新期限；ACTIVE 自迁移或 GRACE_PERIOD 经 RESTORED 回到 ACTIVE。 */
    @Transactional public SubscriptionDetail renew(NewTermCommand command) {
        Action action = termAction("RENEW_SUBSCRIPTION", command); SubscriptionDetail replay = replayDetail(action); if (replay != null) return replay;
        SubscriptionRecord record = requireLocked(command.subscriptionId()); requireState(record, "ACTIVE", "GRACE_PERIOD");
        if("ACTIVE".equals(record.state()))SubscriptionRules.renewalWindow(record.endsAt().toInstant(ZoneOffset.UTC),command.startsAt().toInstant(ZoneOffset.UTC),command.endsAt().toInstant(ZoneOffset.UTC));
        else requireEffectiveNow(command.startsAt(),command.endsAt());
        record = appendAndSwitchTerm(record, command, action);
        if ("GRACE_PERIOD".equals(record.state())) record = transition(record, "RESTORED", action, action.actor());
        record = transition(record, "ACTIVE", action, action.actor());
        applyAccess(record, action); finish(action, record); return detailUnchecked(record.subscriptionId());
    }

    /** 人工暂停只切换服务端恢复白名单，不停用技术租户或删除历史。 */
    @Transactional public SubscriptionDetail suspend(SubscriptionCommand command) {
        Action action=action("SUSPEND_SUBSCRIPTION",command); SubscriptionDetail replay=replayDetail(action);if(replay!=null)return replay;
        SubscriptionRecord record=requireLocked(command.subscriptionId());requireState(record,"ACTIVE","GRACE_PERIOD");
        record=transition(record,"SUSPENDED",action,action.actor());applyAccess(record,action);finish(action,record);return detailUnchecked(record.subscriptionId());
    }

    /** 暂停、过期或终止后的恢复必须追加新期限，再通过 RESTORED → ACTIVE。 */
    @Transactional public SubscriptionDetail restore(NewTermCommand command) {
        Action action=termAction("RESTORE_SUBSCRIPTION",command);SubscriptionDetail replay=replayDetail(action);if(replay!=null)return replay;
        SubscriptionRecord record=requireLocked(command.subscriptionId());requireState(record,"GRACE_PERIOD","SUSPENDED","EXPIRED","TERMINATION_PENDING","TERMINATED");
        requireEffectiveNow(command.startsAt(),command.endsAt());
        record=appendAndSwitchTerm(record,command,action);record=transition(record,"RESTORED",action,action.actor());
        record=transition(record,"ACTIVE",action,action.actor());applyAccess(record,action);finish(action,record);return detailUnchecked(record.subscriptionId());
    }

    @Transactional public SubscriptionDetail requestTermination(SubscriptionCommand command) {
        Action action=action("REQUEST_TERMINATION",command);SubscriptionDetail replay=replayDetail(action);if(replay!=null)return replay;
        SubscriptionRecord record=requireLocked(command.subscriptionId());requireState(record,"ACTIVE","GRACE_PERIOD","SUSPENDED","EXPIRED");
        record=transition(record,"TERMINATION_PENDING",action,action.actor());finish(action,record);return detailUnchecked(record.subscriptionId());
    }

    @Transactional public SubscriptionDetail terminate(SubscriptionCommand command) {
        Action action=action("TERMINATE_SUBSCRIPTION",command);SubscriptionDetail replay=replayDetail(action);if(replay!=null)return replay;
        SubscriptionRecord record=requireLocked(command.subscriptionId());requireState(record,"TERMINATION_PENDING");
        record=transition(record,"TERMINATED",action,action.actor());applyAccess(record,action);finish(action,record);return detailUnchecked(record.subscriptionId());
    }

    @Transactional(readOnly = true) public SubscriptionDetail detail(String subscriptionId) {
        platformActor(); return detailUnchecked(subscriptionId);
    }

    /** 租户仅能读取可信上下文对应的自身订阅。 */
    @Transactional(readOnly = true) public SubscriptionDetail current() {
        String tenant = tenantContext.requireTenantId(); SubscriptionRecord record = persistence.findByTenant(tenant);
        if (record == null) throw new ServiceException("SUB-404: 当前租户没有订阅", 404);
        if (!tenant.equals(record.tenantId())) throw new ServiceException("SUB-TENANT-001: 订阅租户上下文不一致", 403);
        return detailUnchecked(record.subscriptionId());
    }

    /** 平台显式触发受控扫描；客户端时间不参与判断。 */
    @Transactional public ScanResult runExpiryScan(String runnerId) { platformActor(); return scanDue(required(runnerId,"runnerId")); }

    /** 供具名内部 Job 调用，不自动注册定时器。 */
    @Transactional public ScanResult runExpiryScanAsSystem(String runnerId) { return scanDue(required(runnerId,"runnerId")); }

    private ScanResult scanDue(String runnerId) {
        LocalDateTime at=now();persistence.ensureCheckpoint(JOB_CODE,at);
        if(persistence.acquireCheckpoint(new LeaseWrite(JOB_CODE,runnerId,at,at.plusMinutes(5)))!=1)
            throw conflict("SUB-JOB-001","到期扫描租约已被占用");
        List<SubscriptionRecord> due=persistence.findDue(at,500);int changed=0;
        for(SubscriptionRecord candidate:due){SubscriptionRecord record=persistence.lock(candidate.subscriptionId());if(record==null)continue;
            String target=null;if("ACTIVE".equals(record.state())&&!at.isBefore(record.endsAt()))target=at.isBefore(record.graceEndsAt())?"GRACE_PERIOD":"EXPIRED";
            else if("GRACE_PERIOD".equals(record.state())&&!at.isBefore(record.graceEndsAt()))target="EXPIRED";
            if(target==null)continue;String key="expiry:"+record.subscriptionId()+":"+record.currentTermVersion()+":"+target;
            CanonicalJson.Result payload=canonical(Map.of("subscriptionId",record.subscriptionId(),"termVersion",record.currentTermVersion(),"target",target));
            CommandRecord replay=replay(record.tenantId(),"EXPIRY_TRANSITION",key,payload.sha256());if(replay!=null)continue;
            Action action=new Action(record.tenantId(),"EXPIRY_TRANSITION",key,payload.sha256(),"job-"+runnerId,0L);
            record=transition(record,target,action,0L);applyAccess(record,action);record(action.scope(),action.operation(),key,payload.sha256(),record.subscriptionId(),record.state(),at);changed++;}
        CanonicalJson.Result result=canonical(Map.of("runnerId",runnerId,"inspected",due.size(),"transitioned",changed,"scannedAt",at));
        if(persistence.completeCheckpoint(new CheckpointComplete(JOB_CODE,runnerId,at,result.sha256()))!=1)throw conflict("SUB-JOB-002","扫描租约完成失败");
        return new ScanResult(runnerId,due.size(),changed,at);
    }

    private SubscriptionRecord appendAndSwitchTerm(SubscriptionRecord record,NewTermCommand command,Action action){validateTerm(command.startsAt(),command.endsAt(),command.graceEndsAt(),command.businessTimeZone());
        int next=record.currentTermVersion()+1;LocalDateTime at=now();String contract=SubscriptionRules.reference(command.contractRef(),"contractRef");String order=SubscriptionRules.reference(command.externalOrderRef(),"externalOrderRef");
        appendTerm(record.subscriptionId(),record.tenantId(),next,contract,order,command.startsAt(),command.endsAt(),command.graceEndsAt(),command.businessTimeZone(),at);
        if(persistence.changeCurrentTerm(new TermProjectionChange(record.subscriptionId(),record.currentTermVersion(),next,contract,order,command.startsAt(),command.endsAt(),command.graceEndsAt(),SubscriptionRules.zone(command.businessTimeZone()),action.hash(),at))!=1)
            throw conflict("SUB-CONC-002","订阅期限已被并发修改");scheduleReminders(record.tenantId(),record.subscriptionId(),next,command.endsAt(),action.hash(),action.correlation(),at);return requireLocked(record.subscriptionId());}

    private SubscriptionRecord transition(SubscriptionRecord record,String target,Action action,Long actor){SubscriptionStates.requireTransition(record.state(),target);LocalDateTime at=now();
        CanonicalJson.Result fact=canonical(Map.of("subscriptionId",record.subscriptionId(),"from",record.state(),"to",target,"stateVersion",record.stateVersion()+1,"termVersion",record.currentTermVersion(),"commandSha256",action.hash()));
        if(persistence.changeState(new StateChange(record.subscriptionId(),record.state(),target,record.stateVersion(),fact.sha256(),at))!=1)throw conflict("SUB-CONC-001","订阅状态已被并发修改");
        appendState(record.tenantId(),record.subscriptionId(),record.state(),target,record.stateVersion()+1,record.currentTermVersion(),action.operation(),"服务端具名状态迁移",fact.sha256(),action.correlation(),actor,at);
        appendEvidence(record.tenantId(),record.subscriptionId(),action.operation(),target,fact.sha256(),action.correlation(),actor,at);return requireLocked(record.subscriptionId());}

    private void applyAccess(SubscriptionRecord record,Action action){String mode=SubscriptionRules.accessModeFor(record.state());saas.applySubscriptionAccess(new ApplyAccessCommand(record.tenantId(),record.subscriptionId(),mode,record.stateVersion(),record.contentSha256(),"sub-access:"+record.subscriptionId()+":"+record.stateVersion(),action.correlation(),now()));}
    private void finish(Action action,SubscriptionRecord record){record(action.scope(),action.operation(),action.key(),action.hash(),record.subscriptionId(),record.state(),now());}

    private void appendTerm(String id,String tenant,int version,String contract,String order,LocalDateTime starts,LocalDateTime ends,LocalDateTime grace,String zone,LocalDateTime at){String normalizedZone=SubscriptionRules.zone(zone);CanonicalJson.Result term=canonical(Map.of("subscriptionId",id,"termVersion",version,"startsAt",starts,"endsAt",ends,"graceEndsAt",grace,"businessTimeZone",normalizedZone,"contractRef",contract,"externalOrderRef",order));persistence.appendTerm(new TermWrite(ids.next(),id,version,starts,ends,grace,normalizedZone,contract,order,term.sha256(),at));}
    private void scheduleReminders(String tenant,String id,int termVersion,LocalDateTime ends,String hash,String correlation,LocalDateTime at){for(int days:List.of(7,1,0)){String type=days==0?"EXPIRES_TODAY":days==1?"EXPIRES_IN_1_DAY":"EXPIRES_IN_7_DAYS";CanonicalJson.Result p=canonical(Map.of("subscriptionId",id,"termVersion",termVersion,"type",type,"scheduledAt",ends.minusDays(days),"sourceSha256",hash));persistence.appendNotification(new NotificationWrite(ids.next(),tenant,id,termVersion,type,ends.minusDays(days),p.sha256(),at));persistence.appendOutbox(new OutboxWrite(ids.next(),tenant,id,"subscription.reminder-planned.v1",p.json(),p.sha256(),correlation,at));}}
    private void appendState(String tenant,String id,String from,String to,int stateVersion,int termVersion,String action,String reason,String hash,String corr,Long actor,LocalDateTime at){persistence.appendState(new StateEventWrite(ids.next(),tenant,id,from,to,stateVersion,termVersion,action,reason,hash,corr,actor,at));}
    private void appendEvidence(String tenant,String id,String action,String state,String hash,String corr,Long actor,LocalDateTime at){persistence.appendAudit(new AuditWrite(ids.next(),tenant,id,action,"SUCCESS",hash,corr,actor,"订阅状态="+state,at));CanonicalJson.Result out=canonical(Map.of("subscriptionId",id,"state",state,"sourceSha256",hash));persistence.appendOutbox(new OutboxWrite(ids.next(),tenant,id,"subscription.state-changed.v1",out.json(),out.sha256(),corr,at));}

    private Action action(String operation,SubscriptionCommand command){TrustedPrincipal actor=platformActor();CanonicalJson.Result p=canonical(Map.of("subscriptionId",command.subscriptionId(),"reason",required(command.reason(),"reason")));return new Action(PLATFORM,operation,SubscriptionRules.key(command.idempotencyKey()),p.sha256(),correlation(command.correlationId()),actor.userId());}
    private Action termAction(String operation,NewTermCommand command){TrustedPrincipal actor=platformActor();validateTerm(command.startsAt(),command.endsAt(),command.graceEndsAt(),command.businessTimeZone());String contract=SubscriptionRules.reference(command.contractRef(),"contractRef");String order=SubscriptionRules.reference(command.externalOrderRef(),"externalOrderRef");String zone=SubscriptionRules.zone(command.businessTimeZone());CanonicalJson.Result p=canonical(Map.of("subscriptionId",command.subscriptionId(),"contractRef",contract,"externalOrderRef",order,"startsAt",command.startsAt(),"endsAt",command.endsAt(),"graceEndsAt",command.graceEndsAt(),"businessTimeZone",zone));return new Action(PLATFORM,operation,SubscriptionRules.key(command.idempotencyKey()),p.sha256(),correlation(command.correlationId()),actor.userId());}
    private SubscriptionDetail replayDetail(Action a){CommandRecord r=replay(a.scope(),a.operation(),a.key(),a.hash());return r==null?null:detailUnchecked(r.resultRef());}
    private CommandRecord replay(String scope,String operation,String key,String hash){CommandRecord r=persistence.findCommand(scope,operation,key);if(r!=null)SubscriptionRules.sameHash(r.requestSha256(),hash);return r;}
    private void record(String scope,String op,String key,String hash,String ref,String state,LocalDateTime at){persistence.insertCommand(new CommandWrite(ids.next(),scope,op,key,hash,ref,state,at));}
    private SubscriptionRecord requireLocked(String id){SubscriptionRecord r=persistence.lock(id);if(r==null)throw new ServiceException("SUB-404: 订阅不存在",404);return r;}
    private SubscriptionDetail detailUnchecked(String id){SubscriptionRecord r=persistence.find(id);if(r==null)throw new ServiceException("SUB-404: 订阅不存在",404);String mode=switch(r.state()){case "ACTIVE"->"NORMAL";case "GRACE_PERIOD"->"GRACE";case "SUSPENDED","EXPIRED"->"RECOVERY_ONLY";case "TERMINATED"->"TERMINATED_RECOVERY";default->"NO_ACCESS_EFFECT";};return new SubscriptionDetail(r,persistence.listTerms(id),mode,Set.of("RECOVERY_ONLY","TERMINATED_RECOVERY").contains(mode)?RETAINED.stream().sorted().toList():List.of());}
    private void requireState(SubscriptionRecord r,String...states){if(java.util.Arrays.stream(states).noneMatch(r.state()::equals))throw conflict("SUB-STATE-003","当前状态不允许该操作");}
    private void validateTerm(LocalDateTime start,LocalDateTime end,LocalDateTime grace,String zone){SubscriptionRules.zone(zone);SubscriptionRules.term(start==null?null:start.toInstant(ZoneOffset.UTC),end==null?null:end.toInstant(ZoneOffset.UTC),grace==null?null:grace.toInstant(ZoneOffset.UTC));}
    private void requireEffectiveNow(LocalDateTime start,LocalDateTime end){SubscriptionRules.effectiveAt(start==null?null:start.toInstant(ZoneOffset.UTC),end==null?null:end.toInstant(ZoneOffset.UTC),clock.instant());}
    private TrustedPrincipal platformActor(){authorization.requirePlatformAdministrator();return tenantContext.requirePrincipal();}
    private LocalDateTime now(){return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);}
    private CanonicalJson.Result canonical(Map<String,?> source){Map<String,Object> copy=new LinkedHashMap<>();source.forEach((key,value)->copy.put(key,canonicalValue(value)));return CanonicalJson.from(copy);}
    private Object canonicalValue(Object value){return value instanceof LocalDateTime time?time.toInstant(ZoneOffset.UTC).toString():value;}
    private String required(String value,String field){return SubscriptionRules.required(value,field);}
    private String correlation(String value){String v=required(value,"correlationId");if(!v.matches("^[A-Za-z0-9._:-]{1,64}$"))throw conflict("SUB-CORR-001","关联标识格式非法");return v;}
    private static ServiceException conflict(String code,String message){return new ServiceException(code+": "+message,409);}
    private record Action(String scope,String operation,String key,String hash,String correlation,Long actor){}
}
