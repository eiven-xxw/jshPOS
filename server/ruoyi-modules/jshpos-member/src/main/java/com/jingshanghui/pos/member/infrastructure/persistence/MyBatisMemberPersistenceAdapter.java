package com.jingshanghui.pos.member.infrastructure.persistence;

import com.jingshanghui.pos.member.application.model.MemberViews.*;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort;
import com.jingshanghui.pos.member.infrastructure.persistence.mapper.MemberPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

/** 会员 XML Mapper 到领域端口的薄适配层。 */
@Repository
@RequiredArgsConstructor
public class MyBatisMemberPersistenceAdapter implements MemberPersistencePort {
    private final MemberPersistenceMapper mapper;
    @Override public int insertMember(MemberWrite value) { return mapper.insertMember(value); }
    @Override public MemberView findMember(String t,String id) { return mapper.findMember(t,id); }
    @Override public MemberView lockMember(String t,String id) { return mapper.lockMember(t,id); }
    @Override public int changeMemberState(String t,String id,String from,String to,int version) {
        return mapper.changeMemberState(new MemberPersistenceParams.MemberStateUpdate(t,id,from,to,version));
    }
    @Override public int insertIdentity(IdentityWrite value) { return mapper.insertIdentity(value); }
    @Override public IdentityView findIdentityByLookup(String t,String type,String hmac) {
        return mapper.findIdentityByLookup(t,type,hmac);
    }
    @Override public IdentityView findIdentity(String t,String id) { return mapper.findIdentity(t,id); }
    @Override public int revokeIdentity(String t,String id,String member,Long actor,LocalDateTime at) {
        return mapper.revokeIdentity(new MemberPersistenceParams.IdentityRevoke(t,id,member,actor,at));
    }
    @Override public int insertConsent(ConsentWrite value) { return mapper.insertConsent(value); }
    @Override public ConsentView findConsent(String t,String id) { return mapper.findConsent(t,id); }
    @Override public int insertPrivacyRequest(PrivacyWrite value) { return mapper.insertPrivacyRequest(value); }
    @Override public PrivacyRequestView findPrivacyRequest(String t,String id) { return mapper.findPrivacyRequest(t,id); }
    @Override public PrivacyRequestView lockPrivacyRequest(String t,String id) { return mapper.lockPrivacyRequest(t,id); }
    @Override public int changePrivacyState(String t,String id,String from,String to,int version,LocalDateTime completed) {
        return mapper.changePrivacyState(new MemberPersistenceParams.PrivacyStateUpdate(t,id,from,to,version,completed));
    }
    @Override public int insertPrivacyHistory(PrivacyHistoryWrite value) { return mapper.insertPrivacyHistory(value); }
    @Override public int insertMemberLink(LinkWrite value) { return mapper.insertMemberLink(value); }
    @Override public MemberLinkView findLatestMemberLink(String t,String id) { return mapper.findLatestMemberLink(t,id); }
    @Override public MemberLinkView findLatestMemberLinkForSource(String t,String id) {
        return mapper.findLatestMemberLinkForSource(t,id);
    }
    @Override public StoredCommand findCommand(String t,String type,String key) { return mapper.findCommand(t,type,key); }
    @Override public int insertCommand(CommandWrite value) { return mapper.insertCommand(value); }
    @Override public int insertOutbox(OutboxWrite value) { return mapper.insertOutbox(value); }
}
