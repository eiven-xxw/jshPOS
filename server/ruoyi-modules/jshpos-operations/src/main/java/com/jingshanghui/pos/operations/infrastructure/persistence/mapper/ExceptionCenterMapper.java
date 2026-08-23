package com.jingshanghui.pos.operations.infrastructure.persistence.mapper;

import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;
import com.jingshanghui.pos.operations.application.port.ExceptionPersistencePort.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** T2-EXC-001 复杂控制面与只读投影 XML Mapper。 */
@Mapper
public interface ExceptionCenterMapper {
    CaseRecord find(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    CaseRecord lock(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    CaseRecord findByDedup(@Param("tenantId") String tenantId, @Param("storeId") Long storeId, @Param("dedupKey") String dedupKey);
    ObservationRecord findObservation(@Param("tenantId") String tenantId, @Param("ownerCode") String ownerCode, @Param("sourceEventId") String sourceEventId);
    List<CaseRecord> list(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                          @Param("state") String state, @Param("severity") String severity, @Param("limit") int limit);
    void insertCase(CaseWrite value);
    int updateObservationHead(ObservationHead value);
    int changeState(StateChange value);
    void insertObservation(ObservationWrite value);
    void insertLeaseEvent(LeaseEventWrite value);
    void insertPlan(PlanWrite value);
    PlanRecord latestPlan(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    void insertRepair(RepairWrite value);
    int updateRepairResult(RepairResultWrite value);
    void insertReview(ReviewWrite value);
    ReviewRecord latestApprovedReview(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    void appendState(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);
    CommandRecord findCommand(@Param("tenantId") String tenantId, @Param("operation") String operation,
                              @Param("idempotencyKey") String idempotencyKey);
    void insertCommand(CommandWrite value);
    List<ObservationRecord> listObservations(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    List<PlanRecord> listPlans(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    List<RepairRecord> listRepairs(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    List<ReviewRecord> listReviews(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    List<StateEventRecord> listStates(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
    List<AuditRecord> listAudits(@Param("tenantId") String tenantId, @Param("caseId") String caseId);
}
