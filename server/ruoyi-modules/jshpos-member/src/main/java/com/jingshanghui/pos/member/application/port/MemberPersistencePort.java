package com.jingshanghui.pos.member.application.port;

import com.jingshanghui.pos.member.application.model.MemberViews.*;

import java.time.LocalDateTime;

/** 会员复杂事实持久化端口；所有实现都必须显式携带可信 tenant_id。 */
public interface MemberPersistencePort {
    record MemberWrite(String tenantId, String memberId, String displayName, Long createdBy) { }
    record IdentityWrite(String tenantId, String identityId, String memberId, String identityType,
                         String lookupHmac, String cipherText, String maskedValue, int keyVersion,
                         Long actorUserId, LocalDateTime occurredAt) { }
    record ConsentWrite(String tenantId, String consentLedgerId, String consentId, String memberId,
                        String purposeCode, String policyVersion, String state, String evidenceSha256,
                        Long actorUserId, String correlationId, LocalDateTime occurredAt) { }
    record PrivacyWrite(String tenantId, String requestId, String memberId, String requestType, String state,
                        String reason, Long actorUserId, String correlationId, LocalDateTime occurredAt) { }
    record PrivacyHistoryWrite(String tenantId, String historyId, String requestId, String fromState,
                               String toState, String reason, Long actorUserId, String correlationId,
                               LocalDateTime occurredAt) { }
    record LinkWrite(String tenantId, String linkLedgerId, String linkId, String sourceMemberId,
                     String targetMemberId, String action, String reason, Long actorUserId,
                     String correlationId, LocalDateTime occurredAt) { }
    record CommandWrite(String tenantId, String commandResultId, String commandType, String idempotencyKey,
                        String requestSha256, String aggregateType, String aggregateId,
                        String resultSha256, String resultJson) { }
    record StoredCommand(String requestSha256, String aggregateId, String resultSha256, String resultJson) { }
    record OutboxWrite(String tenantId, String outboxId, String eventType, String aggregateId,
                       int aggregateVersion, String payloadJson, String payloadSha256,
                       LocalDateTime availableAt) { }

    int insertMember(MemberWrite value);
    MemberView findMember(String tenantId, String memberId);
    MemberView lockMember(String tenantId, String memberId);
    int changeMemberState(String tenantId, String memberId, String fromState, String toState, int expectedVersion);
    int insertIdentity(IdentityWrite value);
    IdentityView findIdentityByLookup(String tenantId, String identityType, String lookupHmac);
    IdentityView findIdentity(String tenantId, String identityId);
    int revokeIdentity(String tenantId, String identityId, String memberId, Long actorUserId, LocalDateTime occurredAt);
    int insertConsent(ConsentWrite value);
    ConsentView findConsent(String tenantId, String consentId);
    int insertPrivacyRequest(PrivacyWrite value);
    PrivacyRequestView findPrivacyRequest(String tenantId, String requestId);
    PrivacyRequestView lockPrivacyRequest(String tenantId, String requestId);
    int changePrivacyState(String tenantId, String requestId, String fromState, String toState,
                           int expectedVersion, LocalDateTime completedAt);
    int insertPrivacyHistory(PrivacyHistoryWrite value);
    int insertMemberLink(LinkWrite value);
    MemberLinkView findLatestMemberLink(String tenantId, String linkId);
    MemberLinkView findLatestMemberLinkForSource(String tenantId, String sourceMemberId);
    StoredCommand findCommand(String tenantId, String commandType, String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertOutbox(OutboxWrite value);
}
