package com.jingshanghui.pos.reporting.infrastructure.persistence;

import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.PaymentFact;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.SyntheticBillEntry;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort.AuditRow;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort.ReconciliationRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** RPT-002 Mapper 具名参数，避免易错长参数列表。 */
public final class PaymentReconciliationPersistenceParams {
    private PaymentReconciliationPersistenceParams() {
    }

    public record ObjectKey(String tenantId, String objectId) {
    }
    public record FactInsert(String tenantId, PaymentFact fact, Instant appliedAt) {
    }
    public record BillInsert(String tenantId, SyntheticBillEntry bill, Long importedBy, Instant importedAt) {
    }
    public record ProjectionParam(String tenantId, ReconciliationRow row, int expectedVersion) {
    }
    public record RangeParam(String tenantId, LocalDate fromDate, LocalDate toDate) {
    }
    public record QueryParam(String tenantId, LocalDate fromDate, LocalDate toDate, Long storeId,
                             String differenceType, String handlingState) {
    }
    public record CountParam(String tenantId, LocalDate fromDate, LocalDate toDate, List<Long> storeIds) {
        public CountParam { storeIds = List.copyOf(storeIds); }
    }
    public record AuditParam(String tenantId, AuditRow row) {
    }
}
