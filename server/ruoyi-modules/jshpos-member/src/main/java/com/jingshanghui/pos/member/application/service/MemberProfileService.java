package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.model.MemberCommands.*;
import com.jingshanghui.pos.member.application.model.MemberViews.*;
import com.jingshanghui.pos.member.application.port.MemberIdentityProtector;
import com.jingshanghui.pos.member.application.port.MemberIdentityProtector.ProtectedIdentity;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort.*;
import com.jingshanghui.pos.member.domain.MemberRules;
import com.jingshanghui.pos.member.domain.MemberStates;
import com.jingshanghui.pos.member.domain.MemberStates.*;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** T2-MEM-001 会员身份、同意、隐私请求和可逆合并应用服务。 */
@Service
@RequiredArgsConstructor
public class MemberProfileService {
    private static final Set<String> PRIVACY_TYPES = Set.of("ACCESS", "EXPORT", "CORRECT", "DELETE");
    private static final String SHA256 = "^[a-f0-9]{64}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final DomainAuditService audit;
    private final MemberPersistencePort persistence;
    private final MemberIdentityProtector identities;
    private final MemberIdGenerator ids;
    private final Clock clock;

    /** 原子创建会员最小主体和首个加密身份；数据库和事件均不保存身份明文。 */
    @Transactional
    public MemberView create(CreateMember command) {
        requireCreate(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String normalized = MemberRules.normalizeIdentity(command.identityType(), command.identityValue());
        ProtectedIdentity protectedValue = identities.protect(command.identityType(), normalized);
        String requestHash = hash(Map.of("memberId", command.memberId(), "identityId", command.identityId(),
            "identityType", command.identityType(), "lookupHmac", protectedValue.lookupHmac()));
        StoredCommand replay = replay(principal.tenantId(), "CREATE_MEMBER", command.commandId(), requestHash);
        if (replay != null) return requireMember(principal.tenantId(), replay.aggregateId());
        if (persistence.findIdentityByLookup(principal.tenantId(), command.identityType(),
            protectedValue.lookupHmac()) != null) {
            throw new ServiceException("MEM-IDENTITY-002: 身份已绑定", 409);
        }
        String alias = "会员-" + command.memberId().substring(20);
        persistence.insertMember(new MemberWrite(principal.tenantId(), command.memberId(), alias, principal.userId()));
        LocalDateTime now = now();
        persistence.insertIdentity(new IdentityWrite(principal.tenantId(), command.identityId(), command.memberId(),
            command.identityType(), protectedValue.lookupHmac(), protectedValue.cipherText(),
            protectedValue.maskedValue(), protectedValue.keyVersion(), principal.userId(), now));
        appendFacts(principal, "MEMBER_CREATED", command.memberId(), 0, command.correlationId(),
            Map.of("identityType", command.identityType(), "keyVersion", protectedValue.keyVersion()));
        writeCommand(principal.tenantId(), "CREATE_MEMBER", command.commandId(), requestHash, command.memberId(), 0);
        return requireMember(principal.tenantId(), command.memberId());
    }

    /** 使用标准化值的 HMAC 精确解析，仅返回脱敏身份；合并别名可路由到目标主体。 */
    @Transactional(readOnly = true)
    public ResolvedMemberView resolve(Long storeId, String identityType, String identityValue) {
        if (storeId == null || storeId <= 0) throw new ServiceException("MEM-SCOPE-001: 门店标识无效", 400);
        authorization.requireStoreAccess(storeId);
        String tenantId = tenantContext.requireTenantId();
        String normalized = MemberRules.normalizeIdentity(identityType, identityValue);
        IdentityView identity = persistence.findIdentityByLookup(tenantId, identityType,
            identities.lookupHmac(identityType, normalized));
        if (identity == null) throw new ServiceException("MEM-IDENTITY-003: 身份不存在或已撤销", 404);
        MemberView member = requireMember(tenantId, identity.memberId());
        if (MemberState.MERGED.name().equals(member.state())) {
            MemberLinkView link = persistence.findLatestMemberLinkForSource(tenantId, member.memberId());
            if (link == null || !"MERGE".equals(link.action())) {
                throw new ServiceException("MEM-LINK-001: 合并别名缺少可验证目标", 409);
            }
            member = requireMember(tenantId, link.targetMemberId());
        }
        if (MemberState.ANONYMIZED.name().equals(member.state())) {
            throw new ServiceException("MEM-IDENTITY-003: 身份不存在或已撤销", 404);
        }
        return new ResolvedMemberView(member, identity);
    }

    /** 为活动会员追加一个加密身份绑定。 */
    @Transactional
    public IdentityView bindIdentity(IdentityCommand command) {
        requireIdentity(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireActiveMember(principal.tenantId(), command.memberId());
        ProtectedIdentity value = identities.protect(command.identityType(),
            MemberRules.normalizeIdentity(command.identityType(), command.identityValue()));
        String requestHash = hash(Map.of("memberId", command.memberId(), "identityId", command.identityId(),
            "identityType", command.identityType(), "lookupHmac", value.lookupHmac(),
            "reason", MemberRules.requireReason(command.reason())));
        StoredCommand replay = replay(principal.tenantId(), "BIND_IDENTITY", command.commandId(), requestHash);
        if (replay != null) return requireIdentity(principal.tenantId(), replay.aggregateId());
        if (persistence.findIdentityByLookup(principal.tenantId(), command.identityType(), value.lookupHmac()) != null) {
            throw new ServiceException("MEM-IDENTITY-002: 身份已绑定", 409);
        }
        persistence.insertIdentity(new IdentityWrite(principal.tenantId(), command.identityId(), command.memberId(),
            command.identityType(), value.lookupHmac(), value.cipherText(), value.maskedValue(), value.keyVersion(),
            principal.userId(), now()));
        appendFacts(principal, "MEMBER_IDENTITY_BOUND", command.memberId(), 0, command.correlationId(),
            Map.of("identityId", command.identityId(), "identityType", command.identityType(),
                "keyVersion", value.keyVersion()));
        writeCommand(principal.tenantId(), "BIND_IDENTITY", command.commandId(), requestHash, command.identityId(), 0);
        return requireIdentity(principal.tenantId(), command.identityId());
    }

    /** 撤销身份绑定，不删除历史记录。 */
    @Transactional
    public IdentityView revokeIdentity(RevokeIdentity command) {
        requireRevoke(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String reason = MemberRules.requireReason(command.reason());
        String requestHash = hash(Map.of("memberId", command.memberId(), "identityId", command.identityId(),
            "reason", reason));
        StoredCommand replay = replay(principal.tenantId(), "REVOKE_IDENTITY", command.commandId(), requestHash);
        if (replay != null) return requireIdentity(principal.tenantId(), replay.aggregateId());
        IdentityView existing = requireIdentity(principal.tenantId(), command.identityId());
        if (!existing.memberId().equals(command.memberId()) || !IdentityState.ACTIVE.name().equals(existing.state())) {
            throw new ServiceException("MEM-IDENTITY-004: 身份不属于活动绑定", 409);
        }
        if (persistence.revokeIdentity(principal.tenantId(), command.identityId(), command.memberId(),
            principal.userId(), now()) != 1) throw conflict();
        appendFacts(principal, "MEMBER_IDENTITY_REVOKED", command.memberId(), 0, command.correlationId(),
            Map.of("identityId", command.identityId(), "reasonSha256", hash(Map.of("reason", reason))));
        writeCommand(principal.tenantId(), "REVOKE_IDENTITY", command.commandId(), requestHash,
            command.identityId(), 0);
        return requireIdentity(principal.tenantId(), command.identityId());
    }

    /** 追加同意或撤回事实；同一 purpose 的当前状态由时间顺序投影。 */
    @Transactional
    public ConsentView recordConsent(ConsentCommand command) {
        requireConsent(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireMember(principal.tenantId(), command.memberId());
        String requestHash = hash(Map.of("memberId", command.memberId(), "consentId", command.consentId(),
            "purposeCode", command.purposeCode(), "policyVersion", command.policyVersion(),
            "state", command.state(), "evidenceSha256", command.evidenceSha256()));
        StoredCommand replay = replay(principal.tenantId(), "RECORD_CONSENT", command.commandId(), requestHash);
        if (replay != null) return requireConsent(principal.tenantId(), replay.aggregateId());
        LocalDateTime now = now();
        persistence.insertConsent(new ConsentWrite(principal.tenantId(), ids.next(), command.consentId(),
            command.memberId(), command.purposeCode(), command.policyVersion(), command.state(),
            command.evidenceSha256(), principal.userId(), command.correlationId(), now));
        appendFacts(principal, "MEMBER_CONSENT_RECORDED", command.memberId(), 0, command.correlationId(),
            Map.of("consentId", command.consentId(), "purposeCode", command.purposeCode(), "state", command.state()));
        writeCommand(principal.tenantId(), "RECORD_CONSENT", command.commandId(), requestHash, command.consentId(), 0);
        return requireConsent(principal.tenantId(), command.consentId());
    }

    /** 提交访问、导出、更正或删除请求，首次状态固定为 REQUESTED。 */
    @Transactional
    public PrivacyRequestView requestPrivacy(PrivacyCommand command) {
        requirePrivacy(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireMember(principal.tenantId(), command.memberId());
        String reason = MemberRules.requireReason(command.reason());
        String requestHash = hash(Map.of("memberId", command.memberId(), "requestId", command.requestId(),
            "requestType", command.requestType(), "reason", reason));
        StoredCommand replay = replay(principal.tenantId(), "REQUEST_PRIVACY", command.commandId(), requestHash);
        if (replay != null) return requirePrivacy(principal.tenantId(), replay.aggregateId());
        LocalDateTime now = now();
        persistence.insertPrivacyRequest(new PrivacyWrite(principal.tenantId(), command.requestId(),
            command.memberId(), command.requestType(), PrivacyRequestState.REQUESTED.name(), reason,
            principal.userId(), command.correlationId(), now));
        persistence.insertPrivacyHistory(new PrivacyHistoryWrite(principal.tenantId(), ids.next(),
            command.requestId(), null, PrivacyRequestState.REQUESTED.name(), reason, principal.userId(),
            command.correlationId(), now));
        appendFacts(principal, "MEMBER_PRIVACY_REQUESTED", command.memberId(), 0, command.correlationId(),
            Map.of("requestId", command.requestId(), "requestType", command.requestType()));
        writeCommand(principal.tenantId(), "REQUEST_PRIVACY", command.commandId(), requestHash, command.requestId(), 0);
        return requirePrivacy(principal.tenantId(), command.requestId());
    }

    /** 由租户管理员按乐观锁迁移隐私请求并追加不可变历史。 */
    @Transactional
    public PrivacyRequestView transitionPrivacy(PrivacyTransition command) {
        requirePrivacyTransition(command);
        authorization.requireTenantAdministrator();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String reason = MemberRules.requireReason(command.reason());
        String requestHash = hash(Map.of("requestId", command.requestId(), "toState", command.toState(),
            "expectedVersion", command.expectedVersion(), "reason", reason));
        StoredCommand replay = replay(principal.tenantId(), "TRANSITION_PRIVACY", command.commandId(), requestHash);
        if (replay != null) return requirePrivacy(principal.tenantId(), replay.aggregateId());
        PrivacyRequestView current = persistence.lockPrivacyRequest(principal.tenantId(), command.requestId());
        if (current == null) throw new ServiceException("MEM-PRIVACY-001: 隐私请求不存在", 404);
        PrivacyRequestState from = enumValue(PrivacyRequestState.class, current.state(), "当前隐私状态");
        PrivacyRequestState to = enumValue(PrivacyRequestState.class, command.toState(), "目标隐私状态");
        if (current.version() != command.expectedVersion() || !MemberStates.canTransition(from, to)) {
            throw new ServiceException("MEM-PRIVACY-002: 非法或并发状态迁移", 409);
        }
        LocalDateTime now = now();
        LocalDateTime completed = Set.of(PrivacyRequestState.FULFILLED,
            PrivacyRequestState.PARTIALLY_FULFILLED, PrivacyRequestState.REJECTED).contains(to) ? now : null;
        if (persistence.changePrivacyState(principal.tenantId(), command.requestId(), from.name(), to.name(),
            command.expectedVersion(), completed) != 1) throw conflict();
        persistence.insertPrivacyHistory(new PrivacyHistoryWrite(principal.tenantId(), ids.next(),
            command.requestId(), from.name(), to.name(), reason, principal.userId(), command.correlationId(), now));
        appendFacts(principal, "MEMBER_PRIVACY_STATE_CHANGED", current.memberId(), command.expectedVersion() + 1,
            command.correlationId(), Map.of("requestId", command.requestId(), "from", from.name(), "to", to.name()));
        writeCommand(principal.tenantId(), "TRANSITION_PRIVACY", command.commandId(), requestHash,
            command.requestId(), command.expectedVersion() + 1);
        return requirePrivacy(principal.tenantId(), command.requestId());
    }

    /** 将来源主体转为可追踪别名；不搬移或改写历史事实。 */
    @Transactional
    public MemberLinkView merge(MergeCommand command) {
        requireMerge(command.commandId(), command.sourceMemberId(), command.targetMemberId(), command.linkId(),
            command.reason(), command.correlationId());
        authorization.requireTenantAdministrator();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String reason = MemberRules.requireReason(command.reason());
        String requestHash = linkHash(command.sourceMemberId(), command.targetMemberId(), command.linkId(), reason);
        StoredCommand replay = replay(principal.tenantId(), "MERGE_MEMBER", command.commandId(), requestHash);
        if (replay != null) return requireLink(principal.tenantId(), replay.aggregateId());
        MemberView source = persistence.lockMember(principal.tenantId(), command.sourceMemberId());
        MemberView target = persistence.lockMember(principal.tenantId(), command.targetMemberId());
        requireActive(source); requireActive(target);
        if (persistence.changeMemberState(principal.tenantId(), source.memberId(), MemberState.ACTIVE.name(),
            MemberState.MERGED.name(), source.version()) != 1) throw conflict();
        LocalDateTime now = now();
        persistence.insertMemberLink(new LinkWrite(principal.tenantId(), ids.next(), command.linkId(),
            source.memberId(), target.memberId(), "MERGE", reason, principal.userId(), command.correlationId(), now));
        appendFacts(principal, "MEMBER_MERGED", source.memberId(), source.version() + 1, command.correlationId(),
            Map.of("linkId", command.linkId(), "targetMemberId", target.memberId()));
        writeCommand(principal.tenantId(), "MERGE_MEMBER", command.commandId(), requestHash, command.linkId(), 1);
        return requireLink(principal.tenantId(), command.linkId());
    }

    /** 只有原合并关系仍是最新动作时才允许拆分恢复来源主体。 */
    @Transactional
    public MemberLinkView split(SplitCommand command) {
        requireMerge(command.commandId(), command.sourceMemberId(), command.targetMemberId(), command.linkId(),
            command.reason(), command.correlationId());
        authorization.requireTenantAdministrator();
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String reason = MemberRules.requireReason(command.reason());
        String requestHash = linkHash(command.sourceMemberId(), command.targetMemberId(), command.linkId(), reason);
        StoredCommand replay = replay(principal.tenantId(), "SPLIT_MEMBER", command.commandId(), requestHash);
        if (replay != null) return requireLink(principal.tenantId(), replay.aggregateId());
        MemberLinkView latest = requireLink(principal.tenantId(), command.linkId());
        if (!"MERGE".equals(latest.action()) || !latest.sourceMemberId().equals(command.sourceMemberId())
            || !latest.targetMemberId().equals(command.targetMemberId())) {
            throw new ServiceException("MEM-LINK-002: 不存在可拆分的活动合并关系", 409);
        }
        MemberView source = persistence.lockMember(principal.tenantId(), command.sourceMemberId());
        requireState(source, MemberState.MERGED);
        requireActiveMember(principal.tenantId(), command.targetMemberId());
        if (persistence.changeMemberState(principal.tenantId(), source.memberId(), MemberState.MERGED.name(),
            MemberState.ACTIVE.name(), source.version()) != 1) throw conflict();
        persistence.insertMemberLink(new LinkWrite(principal.tenantId(), ids.next(), command.linkId(),
            command.sourceMemberId(), command.targetMemberId(), "SPLIT", reason, principal.userId(),
            command.correlationId(), now()));
        appendFacts(principal, "MEMBER_SPLIT", source.memberId(), source.version() + 1, command.correlationId(),
            Map.of("linkId", command.linkId(), "targetMemberId", command.targetMemberId()));
        writeCommand(principal.tenantId(), "SPLIT_MEMBER", command.commandId(), requestHash, command.linkId(), 2);
        return requireLink(principal.tenantId(), command.linkId());
    }

    private void appendFacts(TrustedPrincipal principal, String action, String memberId, int version,
                             String correlationId, Map<String, Object> summary) {
        audit.append(action, "MEMBER", memberId, null, Map.of("memberId", memberId, "version", version), summary);
        String eventId=ids.next(); LocalDateTime occurredAt=now();
        Map<String,Object> payload = new LinkedHashMap<>(); payload.put("eventId",eventId);
        payload.put("tenantContext",Map.of("tenantId",principal.tenantId())); payload.put("schemaVersion","1.0");
        payload.put("occurredAt",occurredAt.toString()+"Z"); payload.put("memberId", memberId);
        payload.put("version", version); payload.put("correlationId", correlationId); payload.put("action", action);
        CanonicalJson.Result canonical = CanonicalJson.from(payload);
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), eventId, eventType(action), memberId,
            version, canonical.json(), canonical.sha256(), occurredAt));
    }

    private String eventType(String action) {
        if (action.contains("CONSENT")) return "member.consent.changed.v1";
        if (action.contains("PRIVACY")) return "member.privacy.changed.v1";
        if (action.contains("IDENTITY")) return "member.identity.changed.v1";
        return "member.profile.changed.v1";
    }

    private StoredCommand replay(String tenantId, String type, String key, String requestHash) {
        StoredCommand stored = persistence.findCommand(tenantId, type, key);
        if (stored != null && !stored.requestSha256().equals(requestHash)) {
            throw new ServiceException("MEM-IDEMP-001: 同幂等键对应不同内容", 409);
        }
        return stored;
    }

    private void writeCommand(String tenantId, String type, String key, String requestHash,
                              String aggregateId, int version) {
        CanonicalJson.Result result = CanonicalJson.from(Map.of("aggregateId", aggregateId, "version", version));
        persistence.insertCommand(new CommandWrite(tenantId, ids.next(), type, key, requestHash, "MEMBER",
            aggregateId, result.sha256(), result.json()));
    }

    private MemberView requireMember(String tenantId, String memberId) {
        MemberView value = persistence.findMember(tenantId, memberId);
        if (value == null) throw new ServiceException("MEM-PROFILE-001: 会员不存在或不可见", 404);
        return value;
    }
    private MemberView requireActiveMember(String tenantId, String memberId) {
        MemberView value = requireMember(tenantId, memberId); requireActive(value); return value;
    }
    private void requireActive(MemberView value) { requireState(value, MemberState.ACTIVE); }
    private void requireState(MemberView value, MemberState state) {
        if (value == null || !state.name().equals(value.state())) {
            throw new ServiceException("MEM-PROFILE-002: 会员状态不允许该操作", 409);
        }
    }
    private IdentityView requireIdentity(String tenantId, String identityId) {
        IdentityView value = persistence.findIdentity(tenantId, identityId);
        if (value == null) throw new ServiceException("MEM-IDENTITY-003: 身份不存在或已撤销", 404);
        return value;
    }
    private ConsentView requireConsent(String tenantId, String consentId) {
        ConsentView value = persistence.findConsent(tenantId, consentId);
        if (value == null) throw new ServiceException("MEM-CONSENT-001: 同意事实不存在", 404);
        return value;
    }
    private PrivacyRequestView requirePrivacy(String tenantId, String requestId) {
        PrivacyRequestView value = persistence.findPrivacyRequest(tenantId, requestId);
        if (value == null) throw new ServiceException("MEM-PRIVACY-001: 隐私请求不存在", 404);
        return value;
    }
    private MemberLinkView requireLink(String tenantId, String linkId) {
        MemberLinkView value = persistence.findLatestMemberLink(tenantId, linkId);
        if (value == null) throw new ServiceException("MEM-LINK-003: 会员关联不存在", 404);
        return value;
    }
    private String linkHash(String source, String target, String link, String reason) {
        return hash(Map.of("sourceMemberId", source, "targetMemberId", target, "linkId", link, "reason", reason));
    }
    private String hash(Map<String,Object> value) { return CanonicalJson.from(value).sha256(); }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private ServiceException conflict() { return new ServiceException("MEM-CONCURRENCY-001: 状态已被并发修改", 409); }

    private void requireCreate(CreateMember c) {
        if (c == null) throw new ServiceException("MEM-INPUT-003: 创建命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.memberId(),"会员");
        MemberRules.requireUlid(c.identityId(),"身份"); MemberRules.requireUlid(c.correlationId(),"关联标识");
    }
    private void requireIdentity(IdentityCommand c) {
        if (c == null) throw new ServiceException("MEM-INPUT-004: 身份命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.memberId(),"会员");
        MemberRules.requireUlid(c.identityId(),"身份"); MemberRules.requireUlid(c.correlationId(),"关联标识");
    }
    private void requireRevoke(RevokeIdentity c) {
        if (c == null) throw new ServiceException("MEM-INPUT-004: 身份命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.memberId(),"会员");
        MemberRules.requireUlid(c.identityId(),"身份"); MemberRules.requireUlid(c.correlationId(),"关联标识");
    }
    private void requireConsent(ConsentCommand c) {
        if (c == null) throw new ServiceException("MEM-CONSENT-002: 同意命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.memberId(),"会员");
        MemberRules.requireUlid(c.consentId(),"同意"); MemberRules.requireUlid(c.correlationId(),"关联标识");
        if (c.purposeCode()==null || !c.purposeCode().matches("^[A-Z][A-Z0-9_]{0,63}$")
            || c.policyVersion()==null || !c.policyVersion().matches("^[A-Za-z0-9._-]{1,64}$")
            || !Set.of(ConsentState.GRANTED.name(),ConsentState.REVOKED.name()).contains(c.state())
            || c.evidenceSha256()==null || !c.evidenceSha256().matches(SHA256))
            throw new ServiceException("MEM-CONSENT-002: 同意命令无效",400);
    }
    private void requirePrivacy(PrivacyCommand c) {
        if (c == null) throw new ServiceException("MEM-PRIVACY-003: 隐私命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.memberId(),"会员");
        MemberRules.requireUlid(c.requestId(),"隐私请求"); MemberRules.requireUlid(c.correlationId(),"关联标识");
        if (!PRIVACY_TYPES.contains(c.requestType())) throw new ServiceException("MEM-PRIVACY-003: 隐私命令无效",400);
    }
    private void requirePrivacyTransition(PrivacyTransition c) {
        if (c == null || c.expectedVersion()<0) throw new ServiceException("MEM-PRIVACY-004: 状态命令无效",400);
        MemberRules.requireUlid(c.commandId(),"命令"); MemberRules.requireUlid(c.requestId(),"隐私请求");
        MemberRules.requireUlid(c.correlationId(),"关联标识");
    }
    private void requireMerge(String commandId,String source,String target,String link,String reason,String correlation) {
        MemberRules.requireUlid(commandId,"命令"); MemberRules.requireUlid(source,"来源会员");
        MemberRules.requireUlid(target,"目标会员"); MemberRules.requireUlid(link,"关联");
        MemberRules.requireUlid(correlation,"关联标识"); MemberRules.requireReason(reason);
        if (source.equals(target)) throw new ServiceException("MEM-LINK-004: 来源和目标会员不得相同",400);
    }
    private <E extends Enum<E>> E enumValue(Class<E> type,String value,String field) {
        try { return Enum.valueOf(type,value); }
        catch (RuntimeException e) { throw new ServiceException("MEM-INPUT-005: "+field+"无效",400); }
    }
}
