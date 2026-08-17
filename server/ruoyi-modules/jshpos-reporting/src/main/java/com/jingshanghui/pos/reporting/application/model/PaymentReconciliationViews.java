package com.jingshanghui.pos.reporting.application.model;

import java.time.Instant;
import java.time.LocalDate;

/** T2-RPT-002 Provider 无关对账只读视图。 */
public final class PaymentReconciliationViews {
    private PaymentReconciliationViews() {
    }

    /** 幂等接收结果。 */
    public record IngestView(String objectId, String reconciliationId, boolean applied,
                             String differenceType, String handlingState) {
    }

    /**
     * 支付退款内部合成对账条目；不包含 Provider 私有字段或支付敏感数据。
     */
    public record ReconciliationView(String reconciliationId, String reconciliationKey, String factType,
                                     String sourceEventId, String billEntryId, LocalDate businessDate,
                                     Long orgId, Long storeId, String terminalId, String currency,
                                     Long internalAmountMinor, Long billAmountMinor, String internalStatus,
                                     String billStatus, LocalDate internalBusinessDate,
                                     LocalDate billBusinessDate, String differenceType, String handlingState,
                                     Long handlerId, Instant detectedAt, Instant updatedAt, int version) {
    }

    /** 只追加处理审计视图。 */
    public record AuditView(String auditId, String reconciliationId, String actionType,
                            String fromDifferenceType, String toDifferenceType,
                            String fromHandlingState, String toHandlingState, Long operatorId,
                            String reasonSha256, String correlationId, Instant occurredAt) {
    }

    /** 全量重建结果。 */
    public record RebuildView(String rebuildId, long keyCount, String projectionDigest, String state) {
    }
}
