package com.jingshanghui.pos.reporting.infrastructure.persistence;

import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.PaymentFact;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.SyntheticBillEntry;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.AuditView;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort;
import com.jingshanghui.pos.reporting.infrastructure.persistence.PaymentReconciliationPersistenceParams.*;
import com.jingshanghui.pos.reporting.infrastructure.persistence.mapper.PaymentReconciliationMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** RPT-002 MyBatis XML 适配器；只读投影与只追加 Inbox 均不暴露通用 CRUD。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPaymentReconciliationAdapter implements PaymentReconciliationPersistencePort {
    private final PaymentReconciliationMapper mapper;

    @Override public FactRow findFactByEvent(String tenantId, String sourceEventId) {
        return mapper.findFactByEvent(new ObjectKey(tenantId, sourceEventId));
    }
    @Override public FactRow findFactByKey(String tenantId, String reconciliationKey) {
        return mapper.findFactByKey(new ObjectKey(tenantId, reconciliationKey));
    }
    @Override public boolean insertFact(String tenantId, PaymentFact fact, Instant appliedAt) {
        return mapper.insertFact(new FactInsert(tenantId, fact, appliedAt)) == 1;
    }
    @Override public BillRow findBillByEntry(String tenantId, String billEntryId) {
        return mapper.findBillByEntry(new ObjectKey(tenantId, billEntryId));
    }
    @Override public BillRow findBillByKey(String tenantId, String reconciliationKey) {
        return mapper.findBillByKey(new ObjectKey(tenantId, reconciliationKey));
    }
    @Override public boolean insertBill(String tenantId, SyntheticBillEntry bill, Long importedBy,
                                        Instant importedAt) {
        return mapper.insertBill(new BillInsert(tenantId, bill, importedBy, importedAt)) == 1;
    }
    @Override public ReconciliationRow lockReconciliation(String tenantId, String reconciliationId) {
        return mapper.lockReconciliation(new ObjectKey(tenantId, reconciliationId));
    }
    @Override public ReconciliationRow findReconciliation(String tenantId, String reconciliationId) {
        return mapper.findReconciliation(new ObjectKey(tenantId, reconciliationId));
    }
    @Override public void insertReconciliation(String tenantId, ReconciliationRow row) {
        if (mapper.insertReconciliation(new ProjectionParam(tenantId, row, 0)) != 1) conflict("对账投影创建失败");
    }
    @Override public int updateReconciliation(String tenantId, ReconciliationRow row, int expectedVersion) {
        return mapper.updateReconciliation(new ProjectionParam(tenantId, row, expectedVersion));
    }
    @Override public int deleteProjection(String tenantId, LocalDate fromDate, LocalDate toDate) {
        return mapper.deleteProjection(new RangeParam(tenantId, fromDate, toDate));
    }
    @Override public List<String> listKeys(String tenantId, LocalDate fromDate, LocalDate toDate) {
        return mapper.listKeys(new RangeParam(tenantId, fromDate, toDate));
    }
    @Override public ManualState latestManualState(String tenantId, String reconciliationId) {
        return mapper.latestManualState(new ObjectKey(tenantId, reconciliationId));
    }
    @Override public List<ReconciliationView> query(String tenantId, LocalDate fromDate, LocalDate toDate,
                                                    Long storeId, String differenceType, String handlingState) {
        return mapper.query(new QueryParam(tenantId, fromDate, toDate, storeId, differenceType, handlingState));
    }
    @Override public long count(String tenantId, LocalDate fromDate, LocalDate toDate, List<Long> storeIds) {
        return mapper.count(new CountParam(tenantId, fromDate, toDate, storeIds));
    }
    @Override public List<ReconciliationView> listForDigest(String tenantId, LocalDate fromDate, LocalDate toDate) {
        return mapper.listForDigest(new RangeParam(tenantId, fromDate, toDate));
    }
    @Override public void insertAudit(String tenantId, AuditRow row) {
        if (mapper.insertAudit(new AuditParam(tenantId, row)) != 1) conflict("对账审计写入失败");
    }
    @Override public List<AuditView> listAudit(String tenantId, String reconciliationId) {
        return mapper.listAudit(new ObjectKey(tenantId, reconciliationId));
    }

    private void conflict(String message) {
        throw new ServiceException("RPT-G5D-299: " + message, 409);
    }
}
