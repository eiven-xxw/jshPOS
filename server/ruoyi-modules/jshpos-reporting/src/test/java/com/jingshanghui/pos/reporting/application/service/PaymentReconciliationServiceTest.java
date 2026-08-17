package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands.*;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.*;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort;
import com.jingshanghui.pos.reporting.application.port.PaymentReconciliationPersistencePort.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.RebuildRow;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.infrastructure.id.ReportingIdGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** RPT-002 幂等、乱序收敛、UNKNOWN、处理审计、重建与租户门店边界回归。 */
class PaymentReconciliationServiceTest {
    private static final String TENANT="tenant_alpha";
    private static final Instant NOW=Instant.parse("2026-08-17T02:00:00Z");
    private static final LocalDate DAY=LocalDate.of(2026,8,17);
    private static final String KEY="01ARZ3NDEKTSV4RRFFQ69G5FAV";
    private PaymentReconciliationPersistencePort persistence;
    private ReportingPersistencePort reportingPersistence;
    private TrustedTenantContext context;
    private ScopeAuthorizationService auth;
    private StoreService stores;
    private DomainAuditService audit;
    private PaymentReconciliationService service;

    @BeforeEach void setUp() {
        persistence=mock(PaymentReconciliationPersistencePort.class);
        reportingPersistence=mock(ReportingPersistencePort.class); context=mock(TrustedTenantContext.class);
        auth=mock(ScopeAuthorizationService.class); stores=mock(StoreService.class); audit=mock(DomainAuditService.class);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,1L,"synthetic"));
        when(stores.list()).thenReturn(List.of(new StoreView(11L,1L,1L,"S11","Synthetic","Asia/Shanghai",
            LocalTime.MIDNIGHT,"ACTIVE",0)));
        when(stores.businessDate(eq(11L),any())).thenReturn(new BusinessDateView(11L,"Asia/Shanghai",
            LocalTime.MIDNIGHT,NOW,DAY));
        Clock clock=Clock.fixed(NOW,ZoneOffset.UTC);
        service=new PaymentReconciliationService(persistence,reportingPersistence,context,auth,stores,audit,
            new ReportingIdGenerator(clock),clock);
    }

    @Test void factBeforeBillCreatesMissingBillAndIsIdempotent() {
        PaymentFact fact=fact("SUCCEEDED",100);
        FactRow stored=stored(fact);
        when(persistence.findFactByKey(TENANT,KEY)).thenReturn(null,stored);
        when(persistence.insertFact(TENANT,fact,NOW)).thenReturn(true);
        var result=service.ingestFact(fact);
        assertThat(result.applied()).isTrue(); assertThat(result.differenceType()).isEqualTo("MISSING_BILL");
        verify(persistence).insertReconciliation(eq(TENANT),argThat(row -> row.reconciliationId().equals(KEY)
            && row.sourceEventId().equals(fact.sourceEventId()) && row.billEntryId()==null
            && row.handlingState().equals("OPEN")));
        verify(persistence).insertAudit(eq(TENANT),argThat(row -> row.actionType().equals("SYSTEM_CLASSIFIED")));

        ReconciliationRow projection=projection("MISSING_BILL","OPEN",0);
        when(persistence.findFactByEvent(TENANT,fact.sourceEventId())).thenReturn(stored);
        when(persistence.findReconciliation(TENANT,KEY)).thenReturn(projection);
        assertThat(service.ingestFact(fact).applied()).isFalse();
    }

    @Test void billBeforeFactCreatesMissingInternalThenLateFactMatches() {
        SyntheticBillEntry bill=bill("SUCCEEDED",100,DAY);
        BillRow storedBill=stored(bill);
        when(persistence.findBillByKey(TENANT,KEY)).thenReturn(null,storedBill);
        when(persistence.insertBill(TENANT,bill,7L,NOW)).thenReturn(true);
        assertThat(service.ingestSyntheticBill(bill).differenceType()).isEqualTo("MISSING_INTERNAL");

        reset(persistence);
        PaymentFact fact=fact("SUCCEEDED",100); FactRow storedFact=stored(fact);
        when(persistence.findFactByKey(TENANT,KEY)).thenReturn(null,storedFact);
        when(persistence.findBillByKey(TENANT,KEY)).thenReturn(storedBill);
        when(persistence.insertFact(TENANT,fact,NOW)).thenReturn(true);
        when(persistence.lockReconciliation(TENANT,KEY)).thenReturn(projection("MISSING_INTERNAL","OPEN",0));
        when(persistence.updateReconciliation(eq(TENANT),any(),eq(0))).thenReturn(1);
        var converged=service.ingestFact(fact);
        assertThat(converged.differenceType()).isEqualTo("MATCHED");
        assertThat(converged.handlingState()).isEqualTo("MATCHED");
    }

    @Test void rejectsDifferentContentAndCrossStoreMatchingKey() {
        PaymentFact fact=fact("SUCCEEDED",100);
        when(persistence.findFactByEvent(TENANT,fact.sourceEventId())).thenReturn(new FactRow(fact.sourceEventId(),
            "PAYMENT",1,"store:11:payment","1.0","b".repeat(64),NOW,DAY,1L,11L,"T1","PAYMENT",KEY,
            fact.orderId(),100,"CNY","SUCCEEDED",fact.correlationId()));
        assertThatThrownBy(() -> service.ingestFact(fact)).isInstanceOf(ServiceException.class);

        reset(persistence);
        when(persistence.findBillByKey(TENANT,KEY)).thenReturn(new BillRow("01ARZ3NDEKTSV4RRFFQ69G5FBB",
            "01ARZ3NDEKTSV4RRFFQ69G5FBC","INTERNAL_SYNTHETIC",true,"1.0","c".repeat(64),DAY,1L,12L,
            "T1","PAYMENT",KEY,100,"CNY","SUCCEEDED",fact.correlationId()));
        assertThatThrownBy(() -> service.ingestFact(fact)).isInstanceOf(ServiceException.class);
        verify(persistence,never()).insertFact(anyString(),any(),any());
    }

    @Test void transitionsOpenDifferenceWithOptimisticVersionAndAppendsAudit() {
        ReconciliationRow current=projection("AMOUNT_MISMATCH","OPEN",2);
        when(persistence.lockReconciliation(TENANT,KEY)).thenReturn(current);
        when(persistence.updateReconciliation(eq(TENANT),any(),eq(2))).thenReturn(1);
        var changed=service.transition(new Transition(KEY,"ASSIGNED","synthetic review",2,
            "01ARZ3NDEKTSV4RRFFQ69G5FBD"));
        assertThat(changed.handlingState()).isEqualTo("ASSIGNED"); assertThat(changed.handlerId()).isEqualTo(7L);
        assertThat(changed.version()).isEqualTo(3);
        verify(auth).requireTenantAdministrator(); verify(auth).requireStoreAccess(11L);
        verify(persistence).insertAudit(eq(TENANT),argThat(row -> row.actionType().equals("MANUAL_TRANSITION")
            && row.toHandlingState().equals("ASSIGNED") && row.operatorId().equals(7L)));
    }

    @Test void queriesOnlyAuthorizedStoreAndValidatesFilters() {
        when(persistence.query(TENANT,DAY,DAY,11L,"AMOUNT_MISMATCH","OPEN")).thenReturn(List.of());
        assertThat(service.query(new Query(DAY,DAY,11L,"AMOUNT_MISMATCH","OPEN"))).isEmpty();
        verify(auth).requireStoreAccess(11L);
        assertThatThrownBy(() -> service.query(new Query(DAY,DAY,11L,"OTHER",null)))
            .isInstanceOf(ServiceException.class);
    }

    @Test void rebuildsFromBothAppendOnlyInboxesAndPreservesExternalEvidenceBoundary() {
        String rebuildId="01ARZ3NDEKTSV4RRFFQ69G5FBE";
        PaymentFact fact=fact("UNKNOWN",100); SyntheticBillEntry bill=bill("UNKNOWN",100,DAY);
        when(reportingPersistence.insertRebuildIfAbsent(eq(TENANT),any())).thenReturn(true);
        when(persistence.listKeys(TENANT,DAY,DAY)).thenReturn(List.of(KEY));
        when(persistence.findFactByKey(TENANT,KEY)).thenReturn(stored(fact));
        when(persistence.findBillByKey(TENANT,KEY)).thenReturn(stored(bill));
        when(persistence.listForDigest(TENANT,DAY,DAY)).thenReturn(List.of(view("MATCHED","MATCHED",0)));
        var result=service.rebuild(new Rebuild(rebuildId,DAY,DAY,"01ARZ3NDEKTSV4RRFFQ69G5FBF"));
        assertThat(result.state()).isEqualTo("COMPLETED"); assertThat(result.keyCount()).isEqualTo(1);
        assertThat(result.projectionDigest()).matches("[a-f0-9]{64}");
        verify(persistence).deleteProjection(TENANT,DAY,DAY);
        verify(reportingPersistence).completeRebuild(eq(TENANT),eq(rebuildId),eq("COMPLETED"),eq(1L),
            matches("[a-f0-9]{64}"),eq(NOW));
        verify(audit).append(eq("REPORT_PAYMENT_RECONCILIATION_REBUILT"),eq("REPORT_REBUILD"),eq(rebuildId),
            isNull(),isNull(),argThat(values -> values.get("externalEvidence").equals(0)));
    }

    @Test void concurrentRebuildIdempotentlyReturnsTheWinningRequestWithoutDeletingProjection() {
        String rebuildId="01ARZ3NDEKTSV4RRFFQ69G5FBE";
        RebuildRow winner=new RebuildRow(rebuildId,"g5d-rpt2-v1",DAY,DAY,"RUNNING",7L,
            "01ARZ3NDEKTSV4RRFFQ69G5FBF",0,null,NOW);
        when(reportingPersistence.insertRebuildIfAbsent(eq(TENANT),any())).thenReturn(false);
        when(reportingPersistence.findRebuild(TENANT,rebuildId)).thenReturn(null,winner);
        var result=service.rebuild(new Rebuild(rebuildId,DAY,DAY,"01ARZ3NDEKTSV4RRFFQ69G5FBF"));
        assertThat(result.state()).isEqualTo("RUNNING");
        verify(persistence,never()).deleteProjection(anyString(),any(),any());
    }

    @Test void rejectsBillThatIsNotExplicitlyInternalSynthetic() {
        SyntheticBillEntry raw=bill("SUCCEEDED",100,DAY);
        SyntheticBillEntry invalid=new SyntheticBillEntry(raw.billEntryId(),raw.batchId(),"PROVIDER",false,
            raw.schemaVersion(),raw.contentSha256(),raw.businessDate(),raw.orgId(),raw.storeId(),raw.terminalId(),
            raw.factType(),raw.reconciliationKey(),raw.amountMinor(),raw.currency(),raw.lifecycleStatus(),
            raw.correlationId());
        assertThatThrownBy(() -> service.ingestSyntheticBill(invalid)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(reportingPersistence);
    }

    private PaymentFact fact(String status,long amount) {
        PaymentFact raw=new PaymentFact("01ARZ3NDEKTSV4RRFFQ69G5FAW","PAYMENT",1,"store:11:payment","1.0",
            "a".repeat(64),NOW,DAY,1L,11L,"T1","PAYMENT",KEY,
            "01ARZ3NDEKTSV4RRFFQ69G5FAX",amount,"CNY",status,"01ARZ3NDEKTSV4RRFFQ69G5FAY");
        return new PaymentFact(raw.sourceEventId(),raw.sourceOwner(),raw.sourceSequence(),raw.partitionKey(),
            raw.schemaVersion(),CanonicalReportHash.sha256(PaymentReconciliationService.canonical(raw)),raw.occurredAt(),
            raw.businessDate(),raw.orgId(),raw.storeId(),raw.terminalId(),raw.factType(),raw.reconciliationKey(),
            raw.orderId(),raw.amountMinor(),raw.currency(),raw.lifecycleStatus(),raw.correlationId());
    }

    private SyntheticBillEntry bill(String status,long amount,LocalDate date) {
        SyntheticBillEntry raw=new SyntheticBillEntry("01ARZ3NDEKTSV4RRFFQ69G5FBB","01ARZ3NDEKTSV4RRFFQ69G5FBC",
            "INTERNAL_SYNTHETIC",true,"1.0","a".repeat(64),date,1L,11L,"T1","PAYMENT",KEY,amount,"CNY",
            status,"01ARZ3NDEKTSV4RRFFQ69G5FAY");
        return new SyntheticBillEntry(raw.billEntryId(),raw.batchId(),raw.sourceType(),raw.synthetic(),
            raw.schemaVersion(),CanonicalReportHash.sha256(PaymentReconciliationService.canonical(raw)),raw.businessDate(),
            raw.orgId(),raw.storeId(),raw.terminalId(),raw.factType(),raw.reconciliationKey(),raw.amountMinor(),
            raw.currency(),raw.lifecycleStatus(),raw.correlationId());
    }

    private FactRow stored(PaymentFact f) {
        return new FactRow(f.sourceEventId(),f.sourceOwner(),f.sourceSequence(),f.partitionKey(),f.schemaVersion(),
            f.contentSha256(),f.occurredAt(),f.businessDate(),f.orgId(),f.storeId(),f.terminalId(),f.factType(),
            f.reconciliationKey(),f.orderId(),f.amountMinor(),f.currency(),f.lifecycleStatus(),f.correlationId());
    }
    private BillRow stored(SyntheticBillEntry b) {
        return new BillRow(b.billEntryId(),b.batchId(),b.sourceType(),b.synthetic(),b.schemaVersion(),
            b.contentSha256(),b.businessDate(),b.orgId(),b.storeId(),b.terminalId(),b.factType(),
            b.reconciliationKey(),b.amountMinor(),b.currency(),b.lifecycleStatus(),b.correlationId());
    }
    private ReconciliationRow projection(String difference,String handling,int version) {
        return new ReconciliationRow(KEY,KEY,"PAYMENT","01ARZ3NDEKTSV4RRFFQ69G5FAW",null,DAY,1L,11L,"T1",
            "CNY",100L,null,"SUCCEEDED",null,DAY,null,difference,handling,null,"a".repeat(64),null,NOW,NOW,version);
    }
    private ReconciliationView view(String difference,String handling,int version) {
        return new ReconciliationView(KEY,KEY,"PAYMENT","01ARZ3NDEKTSV4RRFFQ69G5FAW",
            "01ARZ3NDEKTSV4RRFFQ69G5FBB",DAY,1L,11L,"T1","CNY",100L,100L,"UNKNOWN","UNKNOWN",DAY,DAY,
            difference,handling,null,NOW,NOW,version);
    }
}
