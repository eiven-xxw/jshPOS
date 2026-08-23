package com.jingshanghui.pos.operations.application.port;

import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** Operations Owner 唯一持久化端口；不暴露通用删除或跨 Owner 写入。 */
public interface DailyClosePersistencePort {
    CloseRecord find(String tenantId, String closeId);
    CloseRecord lock(String tenantId, String closeId);
    CloseRecord findByCreateKey(String tenantId, String idempotencyKey);
    List<CloseRecord> list(String tenantId, Long storeId, LocalDate businessDate, int limit);
    int nextVersion(String tenantId, Long storeId, LocalDate businessDate);
    void insertClose(CloseWrite value);
    int changeState(StateChange value);
    int nextPreflightRun(String tenantId, String closeId);
    void insertSnapshot(SnapshotWrite value);
    void insertCheckpoint(CheckpointWrite value);
    void insertPreflight(PreflightWrite value);
    void insertDifference(DifferenceWrite value);
    void insertApproval(ApprovalWrite value);
    void insertSignature(SignatureWrite value);
    CommandRecord findCommand(String tenantId, String operation, String idempotencyKey);
    void insertCommand(CommandWrite value);
    void appendState(StateEventWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);
    List<SnapshotRecord> listSnapshots(String tenantId, String closeId);
    List<CheckpointRecord> listCheckpoints(String tenantId, String closeId);
    List<PreflightRecord> listPreflights(String tenantId, String closeId);
    List<DifferenceRecord> listDifferences(String tenantId, String closeId);
    List<ApprovalRecord> listApprovals(String tenantId, String closeId);
    List<SignatureRecord> listSignatures(String tenantId, String closeId);

    record CloseWrite(String closeId, String tenantId, Long storeId, LocalDate businessDate,
                      String zoneId, LocalTime businessDayStart, int closeVersion,
                      String correctionOfCloseId, String correctionReasonSha256, String state,
                      String snapshotSha256, String manifestSha256, String idempotencyKey,
                      String requestSha256, Long creatorUserId, LocalDateTime at) { }
    record StateChange(String tenantId, String closeId, String fromState, String toState,
                       int expectedVersion, Integer preflightRun, String snapshotSha256,
                       String manifestSha256, LocalDateTime at) { }
    record SnapshotWrite(String snapshotId, String tenantId, String closeId, int runNo, String currency,
                         long orderCount, long cancelledOrderCount, long returnCount, long grossMinor,
                         long discountMinor, long surchargeMinor, long receivableMinor, long refundMinor,
                         long cashReceivedMinor, long cashRefundedMinor, long electronicReceivedMinor,
                         long electronicRefundedMinor, long unknownPaymentCount, long unknownRefundCount,
                         long shiftDifferenceMinor,
                         String contentSha256, LocalDateTime at) { }
    record CheckpointWrite(String checkpointId, String tenantId, String closeId, int runNo,
                           String ownerCode, String sourceVersion, long sourceSequence,
                           String sourceStatus, String contentSha256, LocalDateTime at) { }
    record PreflightWrite(String preflightId, String tenantId, String closeId, int runNo,
                          String checkCode, String ownerCode, boolean required, boolean external,
                          CheckStatus status, String evidenceSha256, String maskedMessage,
                          LocalDateTime at) { }
    record DifferenceWrite(String differenceId, String tenantId, String closeId, String type,
                           String state, String expectedSha256, String actualSha256,
                           String detailSha256, LocalDateTime at) { }
    record ApprovalWrite(String approvalId, String tenantId, String closeId, Long approverUserId,
                         String reasonSha256, String idempotencyKey, String requestSha256,
                         LocalDateTime at) { }
    record SignatureWrite(String signatureId, String tenantId, String closeId, Long signatoryUserId,
                          String snapshotSha256, String manifestSha256, String signatureSha256,
                          String idempotencyKey, String requestSha256, LocalDateTime at) { }
    record CommandWrite(String commandId, String tenantId, String closeId, String operation,
                        String idempotencyKey, String requestSha256, String resultState,
                        String resultSha256, LocalDateTime at) { }
    record StateEventWrite(String eventId, String tenantId, String closeId, String fromState,
                           String toState, String requestSha256, String correlationId,
                           Long actorUserId, LocalDateTime at) { }
    record AuditWrite(String auditId, String tenantId, String closeId, String actionCode,
                      String result, String requestSha256, String correlationId,
                      Long actorUserId, String maskedSummary, LocalDateTime at) { }
    record OutboxWrite(String outboxId, String tenantId, String closeId, String eventType,
                       int schemaVersion, String payloadJson, String payloadSha256,
                       String correlationId, LocalDateTime at) { }
}
