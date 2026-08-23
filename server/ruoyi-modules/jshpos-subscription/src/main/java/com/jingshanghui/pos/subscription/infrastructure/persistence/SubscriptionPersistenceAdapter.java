package com.jingshanghui.pos.subscription.infrastructure.persistence;

import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.*;
import com.jingshanghui.pos.subscription.application.port.SubscriptionPersistencePort;
import com.jingshanghui.pos.subscription.infrastructure.persistence.mapper.SubscriptionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** 将 Subscription 应用端口适配到 MyBatis-Plus/XML 双边界。 */
@Repository
@RequiredArgsConstructor
public class SubscriptionPersistenceAdapter implements SubscriptionPersistencePort {
    private final SubscriptionMapper mapper;
    public SubscriptionRecord find(String id){return mapper.find(id);} public SubscriptionRecord lock(String id){return mapper.lock(id);}
    public SubscriptionRecord findByTenant(String tenant){return mapper.findByTenant(tenant);} public List<TermRecord> listTerms(String id){return mapper.listTerms(id);}
    public List<SubscriptionRecord> findDue(LocalDateTime at,int limit){return mapper.findDue(at,limit);} public void insertSubscription(SubscriptionWrite w){mapper.insertSubscription(w);}
    public int changeState(StateChange c){return mapper.changeState(c);} public int changeCurrentTerm(TermProjectionChange c){return mapper.changeCurrentTerm(c);}
    public void appendTerm(TermWrite w){mapper.appendTerm(w);} public void appendState(StateEventWrite w){mapper.appendState(w);} public void appendNotification(NotificationWrite w){mapper.appendNotification(w);}
    public CommandRecord findCommand(String s,String o,String k){return mapper.findCommand(s,o,k);} public void insertCommand(CommandWrite w){mapper.insertCommand(w);}
    public void appendAudit(AuditWrite w){mapper.appendAudit(w);} public void appendOutbox(OutboxWrite w){mapper.appendOutbox(w);}
    public void ensureCheckpoint(String j,LocalDateTime at){mapper.ensureCheckpoint(j,at);} public int acquireCheckpoint(LeaseWrite w){return mapper.acquireCheckpoint(w);} public int completeCheckpoint(CheckpointComplete w){return mapper.completeCheckpoint(w);}
}
