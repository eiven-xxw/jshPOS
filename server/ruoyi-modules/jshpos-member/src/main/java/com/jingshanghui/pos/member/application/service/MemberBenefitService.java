package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.model.BenefitCommands.*;
import com.jingshanghui.pos.member.application.model.BenefitViews.*;
import com.jingshanghui.pos.member.application.model.MemberViews.MemberView;
import com.jingshanghui.pos.member.application.model.PointsViews.LevelView;
import com.jingshanghui.pos.member.application.port.*;
import com.jingshanghui.pos.member.application.port.BenefitPersistencePort.*;
import com.jingshanghui.pos.member.domain.BenefitRules;
import com.jingshanghui.pos.member.domain.BenefitStates;
import com.jingshanghui.pos.member.domain.BenefitStates.VersionState;
import com.jingshanghui.pos.member.domain.MemberRules;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** T2-MEM-003 Member Owner：版本化权益、职责分离与最小无 PII 快照。 */
@Service
@RequiredArgsConstructor
public class MemberBenefitService implements MemberEntitlementQueryPort {
    private static final String SHA256 = "^[a-f0-9]{64}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final MemberPersistencePort members;
    private final PointsPersistencePort points;
    private final BenefitPersistencePort persistence;
    private final MemberIdGenerator ids;
    private final Clock clock;

    /** 在单一事务中创建策略、DRAFT 版本、门店范围、等级映射和审计事实。 */
    @Transactional
    public PolicyVersionView createDraft(CreateDraft command) {
        requireCreate(command);
        authorization.requireTenantAdministrator();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        List<LevelRule> rules = BenefitRules.requireLevelRules(command.levelRules());
        List<Long> storeIds = BenefitRules.requireStoreIds(command.storeIds());
        storeIds.forEach(authorization::requireStoreAccess);
        Map<String,Object> content = new LinkedHashMap<>();
        content.put("policyCode", command.policyCode()); content.put("displayName", command.displayName());
        content.put("defaultCombinationPolicy", "BEST_PRICE");
        content.put("levelRules", rules.stream().map(rule -> Map.of("levelCode",rule.levelCode(),
            "memberPriceEligible",rule.memberPriceEligible(),"stackingAllowed",rule.stackingAllowed())).toList());
        content.put("storeIds", storeIds.stream().sorted().toList());
        String contentHash = CanonicalJson.from(content).sha256();
        String requestHash = hash(Map.of("policyId", command.policyId(), "versionId", command.versionId(),
            "contentSha256", contentHash));
        StoredCommand replay = replay(principal.tenantId(), "CREATE_BENEFIT_DRAFT", command.commandId(), requestHash);
        if (replay != null) return requireVersion(principal.tenantId(), command.policyId(), command.versionId());
        LocalDateTime now = now();
        persistence.insertPolicy(new PolicyWrite(principal.tenantId(), command.policyId(), command.policyCode(),
            command.displayName().trim(), principal.userId()));
        boolean anyStacking = rules.stream().anyMatch(LevelRule::stackingAllowed);
        persistence.insertVersion(new VersionWrite(principal.tenantId(), command.versionId(), command.policyId(), 1,
            "BEST_PRICE", anyStacking, contentHash, principal.userId(), now));
        persistence.insertScopes(storeIds.stream().map(storeId -> new ScopeWrite(principal.tenantId(), ids.next(),
            command.versionId(), storeId, hash(Map.of("versionId",command.versionId(),"storeId",storeId)), now)).toList());
        persistence.insertMappings(rules.stream().map(rule -> new MappingWrite(principal.tenantId(), ids.next(),
            command.versionId(), rule.levelCode(), rule.memberPriceEligible(), rule.stackingAllowed(),
            hash(Map.of("versionId",command.versionId(),"levelCode",rule.levelCode(),"memberPriceEligible",
                rule.memberPriceEligible(),"stackingAllowed",rule.stackingAllowed())), now)).toList());
        appendState(principal, command.versionId(), null, VersionState.DRAFT, "CREATED", "create",
            command.correlationId(), contentHash, 0);
        writeCommand(principal.tenantId(), "CREATE_BENEFIT_DRAFT", command.commandId(), requestHash,
            command.versionId(), contentHash, now);
        return requireVersion(principal.tenantId(), command.policyId(), command.versionId());
    }

    /** 完整预检通过后才将 DRAFT 迁移为 VALIDATED。 */
    @Transactional
    public PolicyVersionView validate(VersionAction command) {
        return transition(command, VersionState.DRAFT, VersionState.VALIDATED, false, false);
    }

    /** 批准人不得与创建人相同；批准后版本内容不再变更。 */
    @Transactional
    public PolicyVersionView approve(VersionAction command) {
        return transition(command, VersionState.VALIDATED, VersionState.APPROVED, true, false);
    }

    /** 根据生效时间进入 SCHEDULED 或 ACTIVE，同一门店范围重叠由数据库查询失败关闭。 */
    @Transactional
    public PolicyVersionView publish(VersionAction command) {
        requireAction(command, true);
        LocalDateTime effective = utc(command.effectiveAt());
        LocalDateTime expires = utc(command.expiresAt());
        BenefitRules.requireWindow(effective, expires);
        VersionState target = effective.isAfter(now()) ? VersionState.SCHEDULED : VersionState.ACTIVE;
        return transition(command, VersionState.APPROVED, target, false, true);
    }

    @Transactional public PolicyVersionView pause(VersionAction c) {
        return transition(c, VersionState.ACTIVE, VersionState.PAUSED, false, false);
    }
    @Transactional public PolicyVersionView resume(VersionAction c) {
        return transition(c, VersionState.PAUSED, VersionState.ACTIVE, false, false);
    }
    @Transactional public PolicyVersionView revoke(VersionAction c) {
        requireAction(c, false);
        PolicyVersionView current = lock(c);
        VersionState from = state(current.state());
        if (!Set.of(VersionState.SCHEDULED, VersionState.ACTIVE, VersionState.PAUSED).contains(from)) {
            throw invalidTransition();
        }
        return transitionLocked(c, current, from, VersionState.REVOKED, false, false);
    }

    /** 从当前等级历史与活动权益版本发行最长 24 小时的最小快照。 */
    @Transactional
    public EntitlementSnapshotView issue(IssueEntitlement command) {
        requireIssue(command);
        authorization.requireStoreAccess(command.storeId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        LocalDateTime quoteAt = utc(command.quoteAt());
        String requestHash = hash(Map.of("snapshotId",command.snapshotId(),"memberId",command.memberId(),
            "storeId",command.storeId(),"quoteAt",quoteAt.toString()));
        StoredCommand replay = replay(principal.tenantId(), "ISSUE_ENTITLEMENT", command.commandId(), requestHash);
        if (replay != null) return requireSnapshot(principal.tenantId(), replay.aggregateId());
        MemberView member = members.findMember(principal.tenantId(), command.memberId());
        if (member == null || !"ACTIVE".equals(member.state())) {
            throw new ServiceException("MEM-BENEFIT-007: 会员不存在或非活动状态", 409);
        }
        LevelView level = points.findCurrentLevel(principal.tenantId(), command.memberId());
        if (level == null || level.effectiveAt().isAfter(quoteAt)) {
            throw new ServiceException("MEM-BENEFIT-008: 没有可用的已生效会员等级", 409);
        }
        PolicyVersionView benefit = persistence.findActiveVersion(principal.tenantId(), command.storeId(),
            level.levelCode(), quoteAt);
        if (benefit == null) throw new ServiceException("MEM-BENEFIT-009: 权益能力未启用或不在适用范围", 409);
        LocalDateTime expires = quoteAt.plusHours(BenefitRules.OFFLINE_TTL_HOURS);
        if (benefit.expiresAt() != null && benefit.expiresAt().isBefore(expires)) expires = benefit.expiresAt();
        String memberRefHash = hash(Map.of("tenantId",principal.tenantId(),"memberId",command.memberId(),
            "purpose","MEMBER_BENEFIT_REF_V1"));
        Map<String,Object> rights = new LinkedHashMap<>(); rights.put("levelHistoryId",level.historyId());
        rights.put("levelCode",level.levelCode()); rights.put("benefitVersionId",benefit.versionId());
        rights.put("storeId",command.storeId()); rights.put("memberPriceEligible", benefit.memberPriceEligible());
        rights.put("stackingAllowed", benefit.allowStacking()); rights.put("effectiveAt",quoteAt.toString());
        rights.put("expiresAt",expires.toString()); rights.put("revocationEpoch",benefit.revocationEpoch());
        String rightsDigest = CanonicalJson.from(rights).sha256();
        String contentHash = hash(Map.of("snapshotId",command.snapshotId(),"memberRefHash",memberRefHash,
            "rightsDigest",rightsDigest));
        persistence.insertSnapshot(new SnapshotWrite(principal.tenantId(), command.snapshotId(), command.memberId(),
            memberRefHash, level.historyId(), level.levelCode(), benefit.versionId(), command.storeId(),
            benefit.memberPriceEligible(),
            benefit.allowStacking(), quoteAt, expires, benefit.revocationEpoch(), rightsDigest, contentHash, now()));
        appendFact(principal, "member.entitlement.snapshot.changed.v1", command.snapshotId(), 1,
            command.correlationId(), contentHash, Map.of("storeId",command.storeId(),"rightsDigest",rightsDigest));
        writeCommand(principal.tenantId(), "ISSUE_ENTITLEMENT", command.commandId(), requestHash,
            command.snapshotId(), contentHash, now());
        return requireSnapshot(principal.tenantId(), command.snapshotId());
    }

    /** 跨 Owner 查询必须再次校验可信门店、有效期和快照摘要。 */
    @Override
    @Transactional(readOnly = true)
    public EntitlementQuote resolve(String snapshotId, Long storeId, Instant quoteAt) {
        MemberRules.requireUlid(snapshotId, "权益快照");
        if (storeId == null || storeId <= 0 || quoteAt == null) {
            throw new ServiceException("MEM-BENEFIT-010: 权益快照查询无效", 400);
        }
        authorization.requireStoreAccess(storeId);
        EntitlementSnapshotView view = requireSnapshot(tenantContext.requireTenantId(), snapshotId);
        LocalDateTime at = utc(quoteAt);
        if (!storeId.equals(view.storeId()) || at.isBefore(view.effectiveAt()) || !at.isBefore(view.expiresAt())) {
            throw new ServiceException("MEM-BENEFIT-011: 权益快照范围或有效期无效", 409);
        }
        return new EntitlementQuote(view.snapshotId(),view.memberRefHash(),view.levelCode(),view.benefitVersionId(),
            view.storeId(),view.memberPriceEligible(),view.stackingAllowed(),instant(view.effectiveAt()),
            instant(view.expiresAt()),view.revocationEpoch(),view.rightsDigest(),view.contentSha256());
    }

    private PolicyVersionView transition(VersionAction c, VersionState from, VersionState to,
                                         boolean enforceSeparation, boolean publish) {
        requireAction(c, publish);
        PolicyVersionView current = lock(c);
        if (state(current.state()) != from) throw invalidTransition();
        return transitionLocked(c,current,from,to,enforceSeparation,publish);
    }

    private PolicyVersionView transitionLocked(VersionAction c, PolicyVersionView current, VersionState from,
                                               VersionState to, boolean separate, boolean publish) {
        if (!BenefitStates.canTransition(from,to)) throw invalidTransition();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        if (!Objects.equals(c.contentSha256(),current.contentSha256())) {
            throw new ServiceException("MEM-BENEFIT-012: 权益版本摘要不匹配", 409);
        }
        if (separate && Objects.equals(current.createdBy(), principal.userId())) {
            throw new ServiceException("MEM-BENEFIT-013: 创建人与批准人必须分离", 409);
        }
        String requestHash = hash(Map.of("versionId",c.versionId(),"from",from.name(),"to",to.name(),
            "contentSha256",c.contentSha256(),"effectiveAt",optional(c.effectiveAt()),
            "expiresAt",optional(c.expiresAt()),"reasonCode",safe(c.reasonCode())));
        StoredCommand replay = replay(principal.tenantId(), "BENEFIT_"+to.name(), c.commandId(), requestHash);
        if (replay != null) return requireVersion(principal.tenantId(),c.policyId(),c.versionId());
        LocalDateTime effective = publish ? utc(c.effectiveAt()) : current.effectiveAt();
        LocalDateTime expires = publish ? utc(c.expiresAt()) : current.expiresAt();
        long epoch = to == VersionState.REVOKED ? current.revocationEpoch()+1 : current.revocationEpoch();
        Long approvedBy = to == VersionState.APPROVED ? principal.userId() : current.approvedBy();
        if (persistence.transitionVersion(new VersionTransition(principal.tenantId(),c.versionId(),from.name(),
            to.name(),current.version(),principal.userId(),approvedBy,effective,expires,epoch,now())) != 1) {
            throw new ServiceException("MEM-BENEFIT-014: 权益版本并发状态冲突",409);
        }
        appendState(principal,c.versionId(),from,to,safe(c.reasonCode()),safe(c.reason()),c.correlationId(),
            current.contentSha256(),current.version()+1);
        writeCommand(principal.tenantId(),"BENEFIT_"+to.name(),c.commandId(),requestHash,c.versionId(),
            current.contentSha256(),now());
        return requireVersion(principal.tenantId(),c.policyId(),c.versionId());
    }

    private void appendState(TrustedPrincipal principal,String versionId,VersionState from,VersionState to,
                             String reasonCode,String reason,String correlationId,String contentHash,int version) {
        LocalDateTime at=now(); String summary=hash(Map.of("from",from==null?"NONE":from.name(),"to",to.name(),
            "reasonCode",safe(reasonCode),"reason",safe(reason)));
        persistence.insertStateEvent(new StateEventWrite(principal.tenantId(),ids.next(),versionId,
            from==null?null:from.name(),to.name(),safe(reasonCode),hash(Map.of("reason",safe(reason))),
            principal.userId(),correlationId,at,summary));
        persistence.insertAudit(new AuditWrite(principal.tenantId(),ids.next(),"MEMBER_BENEFIT_"+to.name(),
            versionId,principal.userId(),safe(reasonCode),summary,correlationId,at));
        appendFact(principal,"member.benefit.version.changed.v1",versionId,version,correlationId,contentHash,
            Map.of("state",to.name(),"revocation",to==VersionState.REVOKED));
    }

    private void appendFact(TrustedPrincipal principal,String eventType,String aggregateId,int version,
                            String correlationId,String contentHash,Map<String,Object> details) {
        String eventId=ids.next(); LocalDateTime at=now(); Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("eventId",eventId); payload.put("eventType",eventType); payload.put("schemaVersion","1.0");
        payload.put("tenantContext",Map.of("tenantId",principal.tenantId())); payload.put("aggregateId",aggregateId);
        payload.put("aggregateVersion",version); payload.put("contentSha256",contentHash);
        payload.put("correlationId",correlationId); payload.put("details",details); payload.put("occurredAt",at+"Z");
        CanonicalJson.Result canonical=CanonicalJson.from(payload);
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(),eventId,eventType,aggregateId,version,
            canonical.json(),canonical.sha256(),at));
    }

    private StoredCommand replay(String tenant,String type,String key,String requestHash) {
        StoredCommand stored=persistence.findCommand(tenant,type,key);
        if(stored!=null && !stored.requestSha256().equals(requestHash))
            throw new ServiceException("MEM-BENEFIT-015: 同幂等键对应不同内容",409);
        return stored;
    }
    private void writeCommand(String tenant,String type,String key,String requestHash,String aggregate,
                              String resultHash,LocalDateTime at) {
        String result=CanonicalJson.from(Map.of("aggregateId",aggregate,"resultSha256",resultHash)).json();
        persistence.insertCommand(new CommandWrite(tenant,ids.next(),type,key,requestHash,aggregate,resultHash,result,at));
    }
    private PolicyVersionView lock(VersionAction c) {
        authorization.requireTenantAdministrator();
        PolicyVersionView value=persistence.lockVersion(tenantContext.requireTenantId(),c.policyId(),c.versionId());
        if(value==null) throw new ServiceException("MEM-BENEFIT-016: 权益版本不存在",404);
        return value;
    }
    private PolicyVersionView requireVersion(String tenant,String policy,String version) {
        PolicyVersionView value=persistence.findVersion(tenant,policy,version);
        if(value==null) throw new ServiceException("MEM-BENEFIT-016: 权益版本不存在",404);
        return value;
    }
    private EntitlementSnapshotView requireSnapshot(String tenant,String id) {
        EntitlementSnapshotView value=persistence.findSnapshot(tenant,id);
        if(value==null) throw new ServiceException("MEM-BENEFIT-017: 权益快照不存在",404);
        return value;
    }
    private void requireCreate(CreateDraft c) {
        if(c==null) throw new ServiceException("MEM-BENEFIT-018: 创建命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.policyId(),"权益策略");
        MemberRules.requireUlid(c.versionId(),"权益版本"); MemberRules.requireUlid(c.correlationId(),"关联标识");
        if(c.policyCode()==null || !c.policyCode().matches("^[A-Z0-9_-]{1,64}$") || c.displayName()==null
            || c.displayName().isBlank() || c.displayName().length()>100)
            throw new ServiceException("MEM-BENEFIT-019: 权益策略资料无效",400);
    }
    private void requireAction(VersionAction c,boolean publish) {
        if(c==null) throw new ServiceException("MEM-BENEFIT-020: 版本命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.policyId(),"权益策略");
        MemberRules.requireUlid(c.versionId(),"权益版本"); MemberRules.requireUlid(c.correlationId(),"关联标识");
        if(c.contentSha256()==null || !c.contentSha256().matches(SHA256))
            throw new ServiceException("MEM-BENEFIT-021: 版本摘要无效",400);
        if(publish && c.effectiveAt()==null) throw new ServiceException("MEM-BENEFIT-006: 权益生效窗口无效",400);
    }
    private void requireIssue(IssueEntitlement c) {
        if(c==null || c.storeId()==null || c.storeId()<=0 || c.quoteAt()==null)
            throw new ServiceException("MEM-BENEFIT-022: 权益发行命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.snapshotId(),"权益快照");
        MemberRules.requireUlid(c.memberId(),"会员"); MemberRules.requireUlid(c.correlationId(),"关联标识");
    }
    private VersionState state(String value) { try { return VersionState.valueOf(value); }
        catch(Exception e) { throw new ServiceException("MEM-BENEFIT-023: 存储的权益状态无效",409); } }
    private ServiceException invalidTransition() { return new ServiceException("MEM-BENEFIT-024: 权益版本状态迁移无效",409); }
    private String hash(Map<String,Object> value) { return CanonicalJson.from(value).sha256(); }
    private String safe(String value) { return value==null?"UNSPECIFIED":value.trim(); }
    private String optional(Instant value) { return value==null?"NONE":value.toString(); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    private LocalDateTime utc(Instant value) { return value==null?null:LocalDateTime.ofInstant(value,ZoneOffset.UTC); }
    private Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
}
