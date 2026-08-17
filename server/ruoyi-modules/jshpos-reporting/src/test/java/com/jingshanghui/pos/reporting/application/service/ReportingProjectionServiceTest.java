package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.*;
import com.jingshanghui.pos.reporting.domain.*;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 来源幂等、缺口晚到、业务日、精度和影子重建应用层回归。 */
class ReportingProjectionServiceTest {
    private static final String TENANT="tenant_alpha";
    private static final Instant NOW=Instant.parse("2026-08-17T02:00:00Z");
    private ReportingPersistencePort persistence;
    private TrustedTenantContext context;
    private ScopeAuthorizationService auth;
    private StoreService stores;
    private ReportingDifferenceService differences;
    private DomainAuditService audit;
    private ReportingProjectionService service;

    @BeforeEach void setUp() {
        persistence=mock(ReportingPersistencePort.class); context=mock(TrustedTenantContext.class);
        auth=mock(ScopeAuthorizationService.class); stores=mock(StoreService.class);
        differences=mock(ReportingDifferenceService.class); audit=mock(DomainAuditService.class);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,1L,"synthetic"));
        when(stores.list()).thenReturn(List.of(new StoreView(11L,1L,1L,"S11","Synthetic","Asia/Shanghai",
            LocalTime.MIDNIGHT,"ACTIVE",0)));
        when(stores.businessDate(eq(11L),any())).thenReturn(new BusinessDateView(11L,"Asia/Shanghai",
            LocalTime.MIDNIGHT,NOW,LocalDate.of(2026,8,17)));
        when(persistence.insertInboxIfAbsent(anyString(),any())).thenReturn(true);
        when(persistence.activeProjectionVersion(TENANT,"SALES")).thenReturn(ReportRules.ENGINE_VERSION);
        when(persistence.activeProjectionVersion(TENANT,"INVENTORY_COST")).thenReturn(ReportRules.ENGINE_VERSION);
        when(persistence.updateCheckpoint(anyString(),anyString(),anyString(),anyLong(),anyLong(),anyString(),anyInt()))
            .thenReturn(1);
        service=new ReportingProjectionService(persistence,context,auth,stores,differences,audit,Clock.fixed(NOW,ZoneOffset.UTC));
    }

    @Test void appliesSalesOnceAndFreezesCheckpoint() {
        SourceEvent event=salesEvent(1,"01ARZ3NDEKTSV4RRFFQ69G5FAV");
        var result=service.ingest(event);
        assertThat(result.applied()).isTrue(); assertThat(result.projectionStatus()).isEqualTo("CURRENT");
        assertThat(result.contiguousSequence()).isEqualTo(1);
        verify(persistence).upsertSalesProjection(TENANT,ReportRules.ENGINE_VERSION,event);
        verify(persistence).markInboxApplied(eq(TENANT),eq(event.sourceEventId()),eq(NOW));
        verify(persistence).upsertProjectionLineage(eq(TENANT),eq(ReportRules.ENGINE_VERSION),eq(event),any(),
            matches("[a-f0-9]{64}"),eq(NOW));
        verify(persistence).insertCheckpoint(TENANT,"ORDER","store:11:order",1,1,"CURRENT");
    }

    @Test void returnsOriginalForDuplicateSameHashAndRejectsDifferentHash() {
        SourceEvent event=salesEvent(1,"01ARZ3NDEKTSV4RRFFQ69G5FAV");
        when(persistence.findInbox(TENANT,event.sourceEventId()))
            .thenReturn(new InboxRow(event.sourceEventId(),event.contentSha256(),"APPLIED"));
        when(persistence.lockCheckpoint(TENANT,"ORDER","store:11:order"))
            .thenReturn(new CheckpointRow("ORDER","store:11:order",1,1,"CURRENT",0));
        assertThat(service.ingest(event).applied()).isFalse();
        SalesDelta changed=new SalesDelta(2,0,0,200,20,0,180,0,180,0,0,2);
        SourceEvent changedRaw=new SourceEvent(event.sourceEventId(),event.sourceOwner(),event.sourceAggregateId(),
            event.sourceSequence(),event.partitionKey(),event.schemaVersion(),event.projectionVersion(),"b".repeat(64),
            event.occurredAt(),event.businessDate(),event.orgId(),event.storeId(),event.terminalId(),event.cashierId(),
            null,null,event.currency(),event.metricFamily(),changed,null,event.correlationId());
        SourceEvent conflict=copyWithHash(changedRaw,
            CanonicalReportHash.sha256(ReportingProjectionService.canonical(changedRaw)));
        when(persistence.findInbox(TENANT,event.sourceEventId()))
            .thenReturn(new InboxRow(event.sourceEventId(),"a".repeat(64),"APPLIED"));
        assertThatThrownBy(() -> service.ingest(conflict)).isInstanceOf(ServiceException.class);
        verify(differences).record(eq("CONTENT_CONFLICT"),eq(event.sourceEventId()),anyString());
    }

    @Test void exposesGapThenLateEventConvergesCheckpoint() {
        SourceEvent third=salesEvent(3,"01ARZ3NDEKTSV4RRFFQ69G5FAW");
        var gap=service.ingest(third);
        assertThat(gap.projectionStatus()).isEqualTo("INCOMPLETE");
        verify(differences).record(eq("SEQUENCE_GAP"),eq(third.sourceEventId()),anyString());

        reset(differences);
        SourceEvent second=salesEvent(2,"01ARZ3NDEKTSV4RRFFQ69G5FAX");
        when(persistence.lockCheckpoint(TENANT,"ORDER","store:11:order"))
            .thenReturn(new CheckpointRow("ORDER","store:11:order",1,3,"INCOMPLETE",2));
        when(persistence.existsAppliedSequence(TENANT,"ORDER","store:11:order",2)).thenReturn(true);
        when(persistence.existsAppliedSequence(TENANT,"ORDER","store:11:order",3)).thenReturn(true);
        var converged=service.ingest(second);
        assertThat(converged.projectionStatus()).isEqualTo("CURRENT");
        assertThat(converged.contiguousSequence()).isEqualTo(3);
        verify(persistence).updateCheckpoint(TENANT,"ORDER","store:11:order",3,3,"CURRENT",2);
        verifyNoInteractions(differences);
    }

    @Test void normalizesInventoryDecimalsAndRejectsExcessScale() {
        SourceEvent event=inventoryEvent(new BigDecimal("1.25"));
        service.ingest(event);
        verify(persistence).upsertInventoryCostProjection(eq(TENANT),eq(ReportRules.ENGINE_VERSION),
            argThat(value -> value.inventoryCost().onHandDelta().scale()==6));
        SourceEvent invalid=inventoryEvent(new BigDecimal("1.0000001"));
        assertThatThrownBy(() -> service.ingest(invalid)).isInstanceOf(ServiceException.class);
    }

    @Test void rejectsWrongBusinessDateHashAndOwnerFamily() {
        SourceEvent event=salesEvent(1,"01ARZ3NDEKTSV4RRFFQ69G5FAV");
        when(stores.businessDate(eq(11L),any())).thenReturn(new BusinessDateView(11L,"Asia/Shanghai",
            LocalTime.MIDNIGHT,NOW,LocalDate.of(2026,8,16)));
        assertThatThrownBy(() -> service.ingest(event)).isInstanceOf(ServiceException.class);
        reset(stores); when(stores.list()).thenReturn(List.of(new StoreView(11L,1L,1L,"S11","Synthetic",
            "Asia/Shanghai",LocalTime.MIDNIGHT,"ACTIVE",0)));
        when(stores.businessDate(eq(11L),any())).thenReturn(new BusinessDateView(11L,"Asia/Shanghai",
            LocalTime.MIDNIGHT,NOW,LocalDate.of(2026,8,17)));
        assertThatThrownBy(() -> service.ingest(copyWithHash(event,"b".repeat(64))))
            .isInstanceOf(ServiceException.class);
        SourceEvent wrongOwner=new SourceEvent(event.sourceEventId(),"COSTING",event.sourceAggregateId(),1,
            event.partitionKey(),event.schemaVersion(),event.projectionVersion(),event.contentSha256(),event.occurredAt(),
            event.businessDate(),event.orgId(),event.storeId(),event.terminalId(),event.cashierId(),null,null,"CNY",
            "SALES",event.sales(),null,event.correlationId());
        assertThatThrownBy(() -> service.ingest(wrongOwner)).isInstanceOf(ServiceException.class);
    }

    @Test void rebuildsInStableOrderAndAtomicallyActivatesBothFamilies() {
        SourceEvent sales=salesEvent(1,"01ARZ3NDEKTSV4RRFFQ69G5FAV");
        SourceEvent inventory=inventoryEvent(new BigDecimal("1.000000"));
        when(persistence.listAppliedEvents(TENANT,LocalDate.of(2026,8,17),LocalDate.of(2026,8,17)))
            .thenReturn(List.of(new StoredSourceEvent(TENANT,inventory),new StoredSourceEvent(TENANT,sales)));
        when(persistence.lockCheckpoint(TENANT,"ORDER","store:11:order"))
            .thenReturn(new CheckpointRow("ORDER","store:11:order",1,1,"CURRENT",1));
        when(persistence.lockCheckpoint(TENANT,"INVENTORY","wh:1:sku:9"))
            .thenReturn(new CheckpointRow("INVENTORY","wh:1:sku:9",1,1,"CURRENT",1));
        when(persistence.projectionDigest(TENANT,"g5d-r1",LocalDate.of(2026,8,17),LocalDate.of(2026,8,17)))
            .thenReturn("c".repeat(64));
        when(persistence.insertRebuildIfAbsent(eq(TENANT),any())).thenReturn(true);
        var result=service.rebuild(new Rebuild("01ARZ3NDEKTSV4RRFFQ69G5FAY","g5d-r1",
            LocalDate.of(2026,8,17),LocalDate.of(2026,8,17),"01ARZ3NDEKTSV4RRFFQ69G5FAZ"));
        assertThat(result.state()).isEqualTo("COMPLETED"); assertThat(result.sourceEventCount()).isEqualTo(2);
        verify(persistence).activateProjectionVersion(TENANT,"SALES","g5d-r1");
        verify(persistence).activateProjectionVersion(TENANT,"INVENTORY_COST","g5d-r1");
        verify(persistence).completeRebuild(TENANT,"01ARZ3NDEKTSV4RRFFQ69G5FAY","COMPLETED",2,"c".repeat(64),NOW);
    }

    @Test void refusesToActivateRebuildWhileAnySourcePartitionIsIncomplete() {
        SourceEvent sales=salesEvent(3,"01ARZ3NDEKTSV4RRFFQ69G5FAV");
        when(persistence.listAppliedEvents(TENANT,LocalDate.of(2026,8,17),LocalDate.of(2026,8,17)))
            .thenReturn(List.of(new StoredSourceEvent(TENANT,sales)));
        when(persistence.lockCheckpoint(TENANT,"ORDER","store:11:order"))
            .thenReturn(new CheckpointRow("ORDER","store:11:order",0,3,"INCOMPLETE",1));
        when(persistence.insertRebuildIfAbsent(eq(TENANT),any())).thenReturn(true);
        Rebuild command=new Rebuild("01ARZ3NDEKTSV4RRFFQ69G5FAY","g5d-r1",LocalDate.of(2026,8,17),
            LocalDate.of(2026,8,17),"01ARZ3NDEKTSV4RRFFQ69G5FAZ");
        assertThatThrownBy(() -> service.rebuild(command)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("禁止切换");
        verify(persistence,never()).activateProjectionVersion(anyString(),anyString(),anyString());
        verify(persistence,never()).completeRebuild(anyString(),anyString(),anyString(),anyLong(),anyString(),any());
    }

    @Test void refusesRebuildBeforeWritingShadowRowsWhenAnotherPartitionIsIncomplete() {
        when(persistence.hasIncompleteCheckpoint(TENANT,"SALES")).thenReturn(true);
        Rebuild command=new Rebuild("01ARZ3NDEKTSV4RRFFQ69G5FAY","g5d-r1",LocalDate.of(2026,8,17),
            LocalDate.of(2026,8,17),"01ARZ3NDEKTSV4RRFFQ69G5FAZ");
        assertThatThrownBy(() -> service.rebuild(command)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("禁止切换");
        verify(persistence,never()).insertRebuildIfAbsent(anyString(),any());
        verify(persistence,never()).clearProjectionVersion(anyString(),anyString(),any(),any());
    }

    @Test void returnsExistingRebuildAndRejectsSameIdDifferentScope() {
        RebuildRow row=new RebuildRow("01ARZ3NDEKTSV4RRFFQ69G5FAY","g5d-r1",LocalDate.of(2026,8,17),
            LocalDate.of(2026,8,17),"COMPLETED",7L,"01ARZ3NDEKTSV4RRFFQ69G5FAZ",2,"c".repeat(64),NOW);
        when(persistence.findRebuild(TENANT,row.rebuildId())).thenReturn(row);
        assertThat(service.rebuild(new Rebuild(row.rebuildId(),row.projectionVersion(),row.fromDate(),row.toDate(),
            row.correlationId())).state()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.rebuild(new Rebuild(row.rebuildId(),"different",row.fromDate(),row.toDate(),
            row.correlationId()))).isInstanceOf(ServiceException.class);
    }

    private SourceEvent salesEvent(long sequence,String id) {
        SourceEvent raw=new SourceEvent(id,"ORDER","ORDER-1",sequence,"store:11:order","1.0",ReportRules.ENGINE_VERSION,
            "a".repeat(64),NOW,LocalDate.of(2026,8,17),1L,11L,"T1",7L,null,null,"CNY","SALES",
            new SalesDelta(1,0,0,100,10,0,90,0,90,0,0,1),null,"01ARZ3NDEKTSV4RRFFQ69G5FB0");
        return copyWithHash(raw,CanonicalReportHash.sha256(ReportingProjectionService.canonical(raw)));
    }
    private SourceEvent inventoryEvent(BigDecimal value) {
        InventoryCostDelta delta=new InventoryCostDelta(value,value,BigDecimal.ZERO,value,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("100"),new BigDecimal("50"),BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO);
        SourceEvent raw=new SourceEvent("01ARZ3NDEKTSV4RRFFQ69G5FB1","INVENTORY","LEDGER-1",1,"wh:1:sku:9","1.0",
            ReportRules.ENGINE_VERSION,"a".repeat(64),NOW,LocalDate.of(2026,8,17),1L,11L,null,null,
            "01ARZ3NDEKTSV4RRFFQ69G5FB2",9L,"CNY","INVENTORY_COST",null,delta,
            "01ARZ3NDEKTSV4RRFFQ69G5FB3");
        InventoryCostDelta normalized=new InventoryCostDelta(scale(value),scale(value),scale(BigDecimal.ZERO),scale(value),
            scale(BigDecimal.ZERO),scale(BigDecimal.ZERO),scale(BigDecimal.ZERO),scale(new BigDecimal("100")),
            scale(new BigDecimal("50")),scale(BigDecimal.ZERO),scale(BigDecimal.ZERO),scale(BigDecimal.ZERO));
        SourceEvent forHash=new SourceEvent(raw.sourceEventId(),raw.sourceOwner(),raw.sourceAggregateId(),raw.sourceSequence(),
            raw.partitionKey(),raw.schemaVersion(),raw.projectionVersion(),raw.contentSha256(),raw.occurredAt(),raw.businessDate(),
            raw.orgId(),raw.storeId(),raw.terminalId(),raw.cashierId(),raw.warehouseId(),raw.skuId(),raw.currency(),
            raw.metricFamily(),null,normalized,raw.correlationId());
        return copyWithHash(raw,CanonicalReportHash.sha256(ReportingProjectionService.canonical(forHash)));
    }
    private BigDecimal scale(BigDecimal value) { return value.scale()>6?value:value.setScale(6); }
    private SourceEvent copyWithHash(SourceEvent e,String hash) {
        return new SourceEvent(e.sourceEventId(),e.sourceOwner(),e.sourceAggregateId(),e.sourceSequence(),e.partitionKey(),
            e.schemaVersion(),e.projectionVersion(),hash,e.occurredAt(),e.businessDate(),e.orgId(),e.storeId(),
            e.terminalId(),e.cashierId(),e.warehouseId(),e.skuId(),e.currency(),e.metricFamily(),e.sales(),
            e.inventoryCost(),e.correlationId());
    }
}
