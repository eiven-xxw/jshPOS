package com.jingshanghui.pos.operations.infrastructure.persistence;

import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;
import com.jingshanghui.pos.operations.application.port.ExceptionPersistencePort;
import com.jingshanghui.pos.operations.infrastructure.persistence.mapper.ExceptionCenterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 异常中心内层持久化端口到 MyBatis XML 的正式适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisExceptionPersistenceAdapter implements ExceptionPersistencePort {
    private final ExceptionCenterMapper mapper;
    @Override public CaseRecord find(String t,String id){return mapper.find(t,id);}
    @Override public CaseRecord lock(String t,String id){return mapper.lock(t,id);}
    @Override public CaseRecord findByDedup(String t,Long s,String k){return mapper.findByDedup(t,s,k);}
    @Override public ObservationRecord findObservation(String t,String o,String e){return mapper.findObservation(t,o,e);}
    @Override public List<CaseRecord> list(String t,Long s,String st,String v,int l){return mapper.list(t,s,st,v,l);}
    @Override public void insertCase(CaseWrite v){mapper.insertCase(v);}
    @Override public int updateObservationHead(ObservationHead v){return mapper.updateObservationHead(v);}
    @Override public int changeState(StateChange v){return mapper.changeState(v);}
    @Override public void insertObservation(ObservationWrite v){mapper.insertObservation(v);}
    @Override public void insertLeaseEvent(LeaseEventWrite v){mapper.insertLeaseEvent(v);}
    @Override public void insertPlan(PlanWrite v){mapper.insertPlan(v);}
    @Override public PlanRecord latestPlan(String t,String id){return mapper.latestPlan(t,id);}
    @Override public void insertRepair(RepairWrite v){mapper.insertRepair(v);}
    @Override public int updateRepairResult(RepairResultWrite v){return mapper.updateRepairResult(v);}
    @Override public void insertReview(ReviewWrite v){mapper.insertReview(v);}
    @Override public ReviewRecord latestApprovedReview(String t,String id){return mapper.latestApprovedReview(t,id);}
    @Override public void appendState(StateEventWrite v){mapper.appendState(v);}
    @Override public void appendAudit(AuditWrite v){mapper.appendAudit(v);}
    @Override public void appendOutbox(OutboxWrite v){mapper.appendOutbox(v);}
    @Override public CommandRecord findCommand(String t,String o,String k){return mapper.findCommand(t,o,k);}
    @Override public void insertCommand(CommandWrite v){mapper.insertCommand(v);}
    @Override public List<ObservationRecord> listObservations(String t,String id){return mapper.listObservations(t,id);}
    @Override public List<PlanRecord> listPlans(String t,String id){return mapper.listPlans(t,id);}
    @Override public List<RepairRecord> listRepairs(String t,String id){return mapper.listRepairs(t,id);}
    @Override public List<ReviewRecord> listReviews(String t,String id){return mapper.listReviews(t,id);}
    @Override public List<StateEventRecord> listStates(String t,String id){return mapper.listStates(t,id);}
    @Override public List<AuditRecord> listAudits(String t,String id){return mapper.listAudits(t,id);}
}
