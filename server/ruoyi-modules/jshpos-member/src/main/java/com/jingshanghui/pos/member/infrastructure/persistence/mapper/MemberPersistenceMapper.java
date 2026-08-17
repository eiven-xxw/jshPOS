package com.jingshanghui.pos.member.infrastructure.persistence.mapper;

import com.jingshanghui.pos.member.application.model.MemberViews.*;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort.*;
import com.jingshanghui.pos.member.infrastructure.persistence.MemberPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

/** 会员敏感身份、状态和只追加事实 Mapper；SQL 只能存在于配套 XML。 */
public interface MemberPersistenceMapper {
    int insertMember(MemberWrite value);
    MemberView findMember(@Param("tenantId") String tenantId, @Param("memberId") String memberId);
    MemberView lockMember(@Param("tenantId") String tenantId, @Param("memberId") String memberId);
    int changeMemberState(MemberStateUpdate value);
    int insertIdentity(IdentityWrite value);
    IdentityView findIdentityByLookup(@Param("tenantId") String tenantId,
                                      @Param("identityType") String identityType,
                                      @Param("lookupHmac") String lookupHmac);
    IdentityView findIdentity(@Param("tenantId") String tenantId, @Param("identityId") String identityId);
    int revokeIdentity(IdentityRevoke value);
    int insertConsent(ConsentWrite value);
    ConsentView findConsent(@Param("tenantId") String tenantId, @Param("consentId") String consentId);
    int insertPrivacyRequest(PrivacyWrite value);
    PrivacyRequestView findPrivacyRequest(@Param("tenantId") String tenantId,
                                          @Param("requestId") String requestId);
    PrivacyRequestView lockPrivacyRequest(@Param("tenantId") String tenantId,
                                          @Param("requestId") String requestId);
    int changePrivacyState(PrivacyStateUpdate value);
    int insertPrivacyHistory(PrivacyHistoryWrite value);
    int insertMemberLink(LinkWrite value);
    MemberLinkView findLatestMemberLink(@Param("tenantId") String tenantId, @Param("linkId") String linkId);
    MemberLinkView findLatestMemberLinkForSource(@Param("tenantId") String tenantId,
                                                  @Param("sourceMemberId") String sourceMemberId);
    StoredCommand findCommand(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                              @Param("idempotencyKey") String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertOutbox(OutboxWrite value);
}
