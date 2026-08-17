package com.jingshanghui.pos.reporting.application.port;

import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.PaymentFact;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.SyntheticBillEntry;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.AuditView;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** RPT-002 XML_ONLY/READ_PROJECTION 持久化端口；所有访问必须显式携带可信 tenantId。 */
public interface PaymentReconciliationPersistencePort {
    FactRow findFactByEvent(String tenantId, String sourceEventId);
    FactRow findFactByKey(String tenantId, String reconciliationKey);
    boolean insertFact(String tenantId, PaymentFact fact, Instant appliedAt);
    BillRow findBillByEntry(String tenantId, String billEntryId);
    BillRow findBillByKey(String tenantId, String reconciliationKey);
    boolean insertBill(String tenantId, SyntheticBillEntry bill, Long importedBy, Instant importedAt);
    ReconciliationRow lockReconciliation(String tenantId, String reconciliationId);
    ReconciliationRow findReconciliation(String tenantId, String reconciliationId);
    void insertReconciliation(String tenantId, ReconciliationRow row);
    int updateReconciliation(String tenantId, ReconciliationRow row, int expectedVersion);
    int deleteProjection(String tenantId, LocalDate fromDate, LocalDate toDate);
    List<String> listKeys(String tenantId, LocalDate fromDate, LocalDate toDate);
    ManualState latestManualState(String tenantId, String reconciliationId);
    List<ReconciliationView> query(String tenantId, LocalDate fromDate, LocalDate toDate, Long storeId,
                                   String differenceType, String handlingState);
    long count(String tenantId, LocalDate fromDate, LocalDate toDate, List<Long> storeIds);
    List<ReconciliationView> listForDigest(String tenantId, LocalDate fromDate, LocalDate toDate);
    void insertAudit(String tenantId, AuditRow row);
    List<AuditView> listAudit(String tenantId, String reconciliationId);

    /** 已校验的内部支付退款事实。 */
    record FactRow(String sourceEventId, String sourceOwner, long sourceSequence, String partitionKey,
                   String schemaVersion, String contentSha256, Instant occurredAt, LocalDate businessDate,
                   Long orgId, Long storeId, String terminalId, String factType, String reconciliationKey,
                   String orderId, long amountMinor, String currency, String lifecycleStatus,
                   String correlationId) {
    }

    /** 已校验的内部合成账单事实。 */
    record BillRow(String billEntryId, String batchId, String sourceType, boolean synthetic,
                   String schemaVersion, String contentSha256, LocalDate businessDate, Long orgId,
                   Long storeId, String terminalId, String factType, String reconciliationKey,
                   long amountMinor, String currency, String lifecycleStatus, String correlationId) {
    }

    /** 可丢弃对账投影持久化模型。 */
    record ReconciliationRow(String reconciliationId, String reconciliationKey, String factType,
                             String sourceEventId, String billEntryId, LocalDate businessDate,
                             Long orgId, Long storeId, String terminalId, String currency,
                             Long internalAmountMinor, Long billAmountMinor, String internalStatus,
                             String billStatus, LocalDate internalBusinessDate, LocalDate billBusinessDate,
                             String differenceType, String handlingState, Long handlerId,
                             String sourceContentSha256, String billContentSha256,
                             Instant detectedAt, Instant updatedAt, int version) {
    }

    /** 对账系统分类或人工处理只追加审计。 */
    record AuditRow(String auditId, String reconciliationId, String actionType,
                    String fromDifferenceType, String toDifferenceType,
                    String fromHandlingState, String toHandlingState, Long operatorId,
                    String reasonSha256, String correlationId, Instant occurredAt) {
    }

    /** 重建时从只追加人工审计恢复的最后处理状态。 */
    record ManualState(String handlingState, Long handlerId) {
    }
}
