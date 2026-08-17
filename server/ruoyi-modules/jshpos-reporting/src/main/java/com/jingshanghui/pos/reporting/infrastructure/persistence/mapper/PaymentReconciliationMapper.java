package com.jingshanghui.pos.reporting.infrastructure.persistence.mapper;

import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.AuditView;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort.*;
import com.jingshanghui.pos.reporting.infrastructure.persistence.PaymentReconciliationPersistenceParams.*;

import java.util.List;

/** RPT-002 XML Mapper；禁止跨 Owner 私有表和隐式 tenant 条件。 */
public interface PaymentReconciliationMapper {
    FactRow findFactByEvent(ObjectKey key);
    FactRow findFactByKey(ObjectKey key);
    int insertFact(FactInsert param);
    BillRow findBillByEntry(ObjectKey key);
    BillRow findBillByKey(ObjectKey key);
    int insertBill(BillInsert param);
    ReconciliationRow lockReconciliation(ObjectKey key);
    ReconciliationRow findReconciliation(ObjectKey key);
    int insertReconciliation(ProjectionParam param);
    int updateReconciliation(ProjectionParam param);
    int deleteProjection(RangeParam param);
    List<String> listKeys(RangeParam param);
    ManualState latestManualState(ObjectKey key);
    List<ReconciliationView> query(QueryParam param);
    long count(CountParam param);
    List<ReconciliationView> listForDigest(RangeParam param);
    int insertAudit(AuditParam param);
    List<AuditView> listAudit(ObjectKey key);
}
