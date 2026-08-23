package com.jingshanghui.pos.operations.application.model;

import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** T2-CLS-001 命令、Owner 快照、持久化投影与安全 REST 视图。 */
public final class DailyCloseModels {
    private DailyCloseModels() {
    }

    public record CreateClose(Long storeId, LocalDate businessDate, String correctionOfCloseId,
                              String correctionReason, String idempotencyKey, String correlationId) { }
    public record CloseCommand(String closeId, String idempotencyKey, String correlationId) { }
    public record ApprovalCommand(String closeId, String reason, String idempotencyKey, String correlationId) { }

    /** Operations 日结头；tenantId 仅来自可信上下文。 */
    public record CloseRecord(String closeId, String tenantId, Long storeId, LocalDate businessDate,
                              String zoneId, LocalTime businessDayStart, Integer closeVersion,
                              String correctionOfCloseId, String correctionReasonSha256, String state,
                              String snapshotSha256, String manifestSha256, String idempotencyKey,
                              String requestSha256, Long creatorUserId, Integer preflightRun,
                              Integer recordVersion, LocalDateTime createdAt, LocalDateTime updatedAt) { }
    public record SnapshotRecord(String snapshotId, String closeId, Integer runNo, String currency,
                                 long orderCount, long cancelledOrderCount, long returnCount,
                                 long grossMinor, long discountMinor, long surchargeMinor,
                                 long receivableMinor, long refundMinor, long cashReceivedMinor,
                                 long cashRefundedMinor, long electronicReceivedMinor,
                                 long electronicRefundedMinor, long unknownPaymentCount,
                                 long unknownRefundCount, long shiftDifferenceMinor, String contentSha256,
                                 LocalDateTime createdAt) { }
    public record CheckpointRecord(String checkpointId, String closeId, Integer runNo, String ownerCode,
                                   String sourceVersion, long sourceSequence, String sourceStatus,
                                   String contentSha256, LocalDateTime createdAt) { }
    public record PreflightRecord(String preflightId, String closeId, Integer runNo, String checkCode,
                                  String ownerCode, boolean required, boolean external, CheckStatus status,
                                  String evidenceSha256, String maskedMessage, LocalDateTime checkedAt) { }
    public record DifferenceRecord(String differenceId, String closeId, String type, String state,
                                   String expectedSha256, String actualSha256, String detailSha256,
                                   LocalDateTime detectedAt) { }
    public record ApprovalRecord(String approvalId, String closeId, Long approverUserId,
                                 String reasonSha256, LocalDateTime approvedAt) { }
    public record SignatureRecord(String signatureId, String closeId, Long signatoryUserId,
                                  String snapshotSha256, String manifestSha256,
                                  String signatureSha256, LocalDateTime signedAt) { }
    public record CommandRecord(String commandId, String closeId, String operation, String idempotencyKey,
                                String requestSha256, String resultState, String resultSha256,
                                LocalDateTime createdAt) { }

    public record CloseDetail(CloseRecord close, List<SnapshotRecord> snapshots,
                              List<CheckpointRecord> checkpoints, List<PreflightRecord> preflights,
                              List<DifferenceRecord> differences, List<ApprovalRecord> approvals,
                              List<SignatureRecord> signatures, boolean correctionRequired) {
        public CloseDetail {
            snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
            preflights = preflights == null ? List.of() : List.copyOf(preflights);
            differences = differences == null ? List.of() : List.copyOf(differences);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            signatures = signatures == null ? List.of() : List.copyOf(signatures);
        }
    }

    /** 所有来源端口的冻结结果；Map 只包含 JSON 原语并用于规范摘要。 */
    public record OwnerSnapshot(String zoneId, LocalTime businessDayStart, SnapshotAmounts amounts,
                                List<SourceCheckpoint> checkpoints, List<PreflightFact> checks,
                                Map<String, Object> canonicalContent) { }
    public record SnapshotAmounts(String currency, long orderCount, long cancelledOrderCount,
                                  long returnCount, long grossMinor, long discountMinor,
                                  long surchargeMinor, long receivableMinor, long refundMinor,
                                  long cashReceivedMinor, long cashRefundedMinor,
                                  long electronicReceivedMinor, long electronicRefundedMinor,
                                  long unknownPaymentCount, long unknownRefundCount,
                                  long shiftDifferenceMinor) { }
    public record SourceCheckpoint(String ownerCode, String sourceVersion, long sourceSequence,
                                   String sourceStatus, String contentSha256) { }
    public record PreflightFact(String checkCode, String ownerCode, boolean required, boolean external,
                                CheckStatus status, String evidenceSha256, String maskedMessage) { }
}
