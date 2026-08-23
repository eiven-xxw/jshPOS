package com.jingshanghui.pos.operations.infrastructure.persistence.mapper;

import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.port.DailyClosePersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/** Operations Owner XML_ONLY Mapper；每条 SQL 必须显式 tenant 条件。 */
public interface DailyCloseMapper {
    CloseRecord find(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    CloseRecord lock(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    CloseRecord findByCreateKey(@Param("tenantId") String tenantId, @Param("idempotencyKey") String key);
    List<CloseRecord> list(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                           @Param("businessDate") LocalDate businessDate, @Param("limit") int limit);
    int nextVersion(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                    @Param("businessDate") LocalDate businessDate);
    int insertClose(CloseWrite value);
    int changeState(StateChange value);
    int nextPreflightRun(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    int insertSnapshot(SnapshotWrite value);
    int insertCheckpoint(CheckpointWrite value);
    int insertPreflight(PreflightWrite value);
    int insertDifference(DifferenceWrite value);
    int insertApproval(ApprovalWrite value);
    int insertSignature(SignatureWrite value);
    CommandRecord findCommand(@Param("tenantId") String tenantId, @Param("operation") String operation,
                              @Param("idempotencyKey") String key);
    int insertCommand(CommandWrite value);
    int appendState(StateEventWrite value);
    int appendAudit(AuditWrite value);
    int appendOutbox(OutboxWrite value);
    List<SnapshotRecord> listSnapshots(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    List<CheckpointRecord> listCheckpoints(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    List<PreflightRecord> listPreflights(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    List<DifferenceRecord> listDifferences(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    List<ApprovalRecord> listApprovals(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
    List<SignatureRecord> listSignatures(@Param("tenantId") String tenantId, @Param("closeId") String closeId);
}
