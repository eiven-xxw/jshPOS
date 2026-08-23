package com.jingshanghui.pos.member.infrastructure.persistence;

import com.jingshanghui.pos.member.application.model.BenefitViews.EntitlementSnapshotView;
import com.jingshanghui.pos.member.application.model.BenefitViews.PolicyVersionView;
import com.jingshanghui.pos.member.application.port.BenefitPersistencePort;
import com.jingshanghui.pos.member.infrastructure.persistence.mapper.BenefitPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 将 T2-MEM-003 XML Mapper 适配为 Member Owner 端口。 */
@Repository
@RequiredArgsConstructor
public class MyBatisBenefitPersistenceAdapter implements BenefitPersistencePort {
    private final BenefitPersistenceMapper mapper;
    @Override public int insertPolicy(PolicyWrite value) { return mapper.insertPolicy(value); }
    @Override public int insertVersion(VersionWrite value) { return mapper.insertVersion(value); }
    @Override public int insertScopes(List<ScopeWrite> values) { return mapper.insertScopes(values); }
    @Override public int insertMappings(List<MappingWrite> values) { return mapper.insertMappings(values); }
    @Override public PolicyVersionView findVersion(String t,String p,String v) { return mapper.findVersion(t,p,v); }
    @Override public PolicyVersionView lockVersion(String t,String p,String v) { return mapper.lockVersion(t,p,v); }
    @Override public int transitionVersion(VersionTransition value) { return mapper.transitionVersion(value); }
    @Override public int insertStateEvent(StateEventWrite value) { return mapper.insertStateEvent(value); }
    @Override public PolicyVersionView findActiveVersion(String t,Long s,String l,LocalDateTime at) {
        return mapper.findActiveVersion(new BenefitPersistenceParams.ActiveLookup(t,s,l,at));
    }
    @Override public int insertSnapshot(SnapshotWrite value) { return mapper.insertSnapshot(value); }
    @Override public EntitlementSnapshotView findSnapshot(String t,String s) { return mapper.findSnapshot(t,s); }
    @Override public StoredCommand findCommand(String t,String c,String k) { return mapper.findCommand(t,c,k); }
    @Override public int insertCommand(CommandWrite value) { return mapper.insertCommand(value); }
    @Override public int insertAudit(AuditWrite value) { return mapper.insertAudit(value); }
    @Override public int insertOutbox(OutboxWrite value) { return mapper.insertOutbox(value); }
}
