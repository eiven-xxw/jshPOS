package com.jingshanghui.pos.saas.infrastructure.persistence;

import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.infrastructure.persistence.mapper.SaasPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 将应用端口适配到受控 MyBatis XML。 */
@Repository
@RequiredArgsConstructor
public class SaasPersistenceAdapter implements SaasPersistencePort {
    private final SaasPersistenceMapper mapper;
    public ApplicationRecord findApplication(String id){return mapper.findApplication(id);} public ApplicationRecord findApplicationByCode(String code){return mapper.findApplicationByCode(code);} public ApplicationRecord lockApplication(String id){return mapper.lockApplication(id);}
    public void insertApplication(ApplicationWrite w){mapper.insertApplication(w);} public int changeApplication(ApplicationChange c){return mapper.changeApplication(c);} public void appendApplicationState(StateEventWrite w){mapper.appendApplicationState(w);}
    public EntitlementVersionRecord findVersion(String id){return mapper.findVersion(id);} public EntitlementVersionRecord lockVersion(String id){return mapper.lockVersion(id);} public EntitlementVersionRecord findVersionByPlanNo(Long p,Integer v){return mapper.findVersionByPlanNo(p,v);}
    public EntitlementVersionRecord findEffectiveVersion(Long p,java.time.LocalDateTime at){return mapper.findEffectiveVersion(p,at);} public int countOverlappingVersions(Long p,String v,java.time.LocalDateTime e,java.time.LocalDateTime x){return mapper.countOverlappingVersions(p,v,e,x);}
    public void insertVersion(VersionWrite w){mapper.insertVersion(w);} public void insertItem(ItemWrite w){mapper.insertItem(w);} public List<EntitlementItemRecord> listItems(String id){return mapper.listItems(id);} public int changeVersion(VersionChange c){return mapper.changeVersion(c);}
    public TenantEntitlementRecord findTenantEntitlement(String t){return mapper.findTenantEntitlement(t);} public void bindTenant(TenantBindingWrite w){mapper.bindTenant(w);} public void seedQuota(TenantQuotaWrite w){mapper.seedQuota(w);} public int changeLifecycle(LifecycleChange c){return mapper.changeLifecycle(c);} public void appendLifecycle(LifecycleEventWrite w){mapper.appendLifecycle(w);}
    public void insertCheckpoint(CheckpointWrite w){mapper.insertCheckpoint(w);} public List<String> listCheckpoints(String a){return mapper.listCheckpoints(a);} public CommandRecord findCommand(String s,String o,String k){return mapper.findCommand(s,o,k);} public void insertCommand(CommandWrite w){mapper.insertCommand(w);}
    public void appendAudit(AuditWrite w){mapper.appendAudit(w);} public void appendOutbox(OutboxWrite w){mapper.appendOutbox(w);} public Long quotaUsed(String t,String f){return mapper.quotaUsed(t,f);} public int consumeQuota(QuotaWrite w){return mapper.consumeQuota(w);}
}
