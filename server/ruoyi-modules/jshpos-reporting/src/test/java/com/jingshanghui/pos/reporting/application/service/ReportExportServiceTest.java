package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews.ReconciliationView;
import com.jingshanghui.pos.reporting.application.port.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.*;
import com.jingshanghui.pos.reporting.domain.CanonicalReportHash;
import com.jingshanghui.pos.reporting.infrastructure.export.ReportCsvEncoder;
import com.jingshanghui.pos.reporting.infrastructure.security.HmacSalesPageCursorCodec;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 导出申请、审批分离、字段范围、制品摘要和单次令牌回归。 */
class ReportExportServiceTest {
    private static final String TENANT="tenant_alpha";
    private static final Instant NOW=Instant.parse("2026-08-17T03:00:00Z");
    private ReportingPersistencePort persistence;
    private ReportingBatchReadPort batchRead;
    private PaymentReconciliationPersistencePort paymentPersistence;
    private ReportArtifactStore store;
    private ReportDownloadTokenProtector tokens;
    private TrustedTenantContext context;
    private ScopeAuthorizationService auth;
    private ReportingDifferenceService differences;
    private DomainAuditService audit;
    private ReportExportService service;

    @BeforeEach void setUp() {
        persistence=mock(ReportingPersistencePort.class);
        batchRead=mock(ReportingBatchReadPort.class);
        paymentPersistence=mock(PaymentReconciliationPersistencePort.class); store=mock(ReportArtifactStore.class);
        tokens=mock(ReportDownloadTokenProtector.class); context=mock(TrustedTenantContext.class);
        auth=mock(ScopeAuthorizationService.class); differences=mock(ReportingDifferenceService.class);
        audit=mock(DomainAuditService.class);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,1L,"synthetic"));
        service=new ReportExportService(persistence,batchRead,paymentPersistence,store,tokens,
            new HmacSalesPageCursorCodec("sales-export-cursor-key-32-bytes!".getBytes()),context,auth,differences,audit,
            Clock.fixed(NOW,ZoneOffset.UTC),new ReportCsvEncoder());
    }

    @Test void requestsLowRiskSalesExportWithoutApprovalAndIsIdempotent() {
        LocalDate day=LocalDate.of(2026,8,17); String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        when(persistence.activeProjectionVersion(TENANT,"SALES")).thenReturn("g5d-v1");
        when(persistence.countSales(TENANT,"g5d-v1",day,day,List.of(11L))).thenReturn(2L);
        when(persistence.insertExportIfAbsent(eq(TENANT),any())).thenReturn(true);
        ExportView result=service.request(new ExportRequest(id,"SALES_DAILY",day,day,Set.of(11L),
            Set.of("businessDate","grossMinor"),"01ARZ3NDEKTSV4RRFFQ69G5FAW"));
        assertThat(result.approvalRequired()).isFalse(); assertThat(result.estimatedRows()).isEqualTo(2);
        verify(persistence).insertExportIfAbsent(eq(TENANT),argThat(row -> row.storeIdsCsv().equals("11")
            && row.fieldsCsv().equals("businessDate,grossMinor") && row.state().equals("REQUESTED")));
        ExportRow existing=row(id,"SALES_DAILY","businessDate,grossMinor","REQUESTED",false,7L,null,2,0,null,null);
        when(persistence.findExport(TENANT,id)).thenReturn(existing);
        assertThat(service.request(new ExportRequest(id,"SALES_DAILY",day,day,Set.of(11L),
            Set.of("businessDate","grossMinor"),"01ARZ3NDEKTSV4RRFFQ69G5FAX")).exportId()).isEqualTo(id);
    }

    @Test void readsExportOnlyAfterRecheckingEveryStoreScope() {
        String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        ExportRow existing=row(id,"SALES_DAILY","businessDate,grossMinor","REQUESTED",false,7L,null,2,0,null,null);
        when(persistence.findExport(TENANT,id)).thenReturn(existing);
        assertThat(service.get(id).exportId()).isEqualTo(id);
        verify(auth).requireStoreAccess(11L);
    }

    @Test void requiresIndependentApprovalForCostExportAndRejectsSelfApproval() {
        LocalDate day=LocalDate.of(2026,8,17); String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        when(persistence.activeProjectionVersion(TENANT,"INVENTORY_COST")).thenReturn("g5d-v1");
        when(persistence.countInventoryCost(TENANT,"g5d-v1",day,day,List.of(11L))).thenReturn(1L);
        when(persistence.insertExportIfAbsent(eq(TENANT),any())).thenReturn(true);
        assertThat(service.request(new ExportRequest(id,"INVENTORY_COST_DAILY",day,day,Set.of(11L),
            Set.of("businessDate","cogsDeltaMinor"),"01ARZ3NDEKTSV4RRFFQ69G5FAW")).approvalRequired()).isTrue();
        ExportRow requested=row(id,"INVENTORY_COST_DAILY","businessDate,cogsDeltaMinor","REQUESTED",true,7L,null,1,0,null,null);
        when(persistence.findExport(TENANT,id)).thenReturn(requested);
        assertThatThrownBy(() -> service.approve(new ExportApproval(id,true,"approved",0,
            "01ARZ3NDEKTSV4RRFFQ69G5FAX"))).isInstanceOf(ServiceException.class);
    }

    @Test void differentApproverCanApproveAndVersionConflictFailsClosed() {
        String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        ExportRow requested=row(id,"INVENTORY_COST_DAILY","businessDate,cogsDeltaMinor","REQUESTED",true,7L,null,1,0,null,null);
        ExportRow approved=row(id,"INVENTORY_COST_DAILY","businessDate,cogsDeltaMinor","APPROVED",true,7L,8L,1,1,null,null);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,8L,1L,"approver"));
        when(persistence.findExport(TENANT,id)).thenReturn(requested,approved);
        when(persistence.transitionExport(eq(TENANT),eq(id),eq("REQUESTED"),eq("APPROVED"),eq(8L),anyString(),eq(0),eq(NOW)))
            .thenReturn(1);
        assertThat(service.approve(new ExportApproval(id,true,"policy",0,
            "01ARZ3NDEKTSV4RRFFQ69G5FAX")).state()).isEqualTo("APPROVED");
        when(persistence.findExport(TENANT,id)).thenReturn(requested);
        when(persistence.transitionExport(anyString(),anyString(),anyString(),anyString(),any(),any(),anyInt(),any()))
            .thenReturn(0);
        assertThatThrownBy(() -> service.approve(new ExportApproval(id,false,"deny",0,
            "01ARZ3NDEKTSV4RRFFQ69G5FAY"))).isInstanceOf(ServiceException.class);
    }

    @Test void generatesSafeCsvAndPersistsTenantNamespacedArtifact() {
        String id="01ARZ3NDEKTSV4RRFFQ69G5FAV"; LocalDate day=LocalDate.of(2026,8,17);
        ExportRow requested=row(id,"SALES_DAILY","businessDate,grossMinor,terminalId","REQUESTED",false,7L,null,1,0,null,null);
        ExportRow ready=row(id,"SALES_DAILY","businessDate,grossMinor,terminalId","READY",false,7L,null,1,2,
            "a".repeat(64),NOW.plus(Duration.ofHours(24)));
        when(persistence.findExport(TENANT,id)).thenReturn(requested,ready);
        when(persistence.transitionExport(TENANT,id,"REQUESTED","GENERATING",null,null,0,NOW)).thenReturn(1);
        when(persistence.activeProjectionVersion(TENANT,"SALES")).thenReturn("g5d-v1");
        when(batchRead.readSales(any())).thenReturn(List.of(
            new SalesDailyView(day,1L,11L,"=cmd",7L,"CNY",1,0,0,100,0,0,100,0,100,0,0,0,"CURRENT")));
        when(store.writeResumable(eq("reporting/"+TENANT+"/"+id),anyString(),any())).thenAnswer(invocation -> {
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            ReportArtifactStore.ResumableWriter writer=invocation.getArgument(2);
            writer.write(output,null,cursor -> { });
            byte[] content=output.toByteArray();
            String digest=CanonicalReportHash.sha256(content);
            assertThat(new String(content,StandardCharsets.UTF_8)).contains("'=cmd");
            return new ReportArtifactStore.StoredArtifact("reporting/"+TENANT+"/"+id+"/"+digest+".csv",
                digest,content.length);
        });
        ExportView result=service.generate(new ExportGenerate(id,0,"01ARZ3NDEKTSV4RRFFQ69G5FAW"));
        assertThat(result.state()).isEqualTo("READY");
        verify(store).writeResumable(eq("reporting/"+TENANT+"/"+id),anyString(),any());
        verify(persistence).attachArtifact(eq(TENANT),argThat(artifact -> artifact.sizeBytes()>0
            && artifact.objectKey().startsWith("reporting/tenant_alpha/")));
    }

    @Test void issuesAndConsumesSingleUseTokenAfterDigestVerification() {
        String id="01ARZ3NDEKTSV4RRFFQ69G5FAV"; byte[] content="safe".getBytes(StandardCharsets.UTF_8);
        String digest=CanonicalReportHash.sha256("safe");
        ExportRow ready=row(id,"SALES_DAILY","businessDate","READY",false,7L,null,1,2,digest,NOW.plusSeconds(3600));
        ArtifactRow artifact=new ArtifactRow(id,"reporting/tenant_alpha/"+id+"/"+digest+".csv",digest,content.length,
            "text/csv;charset=UTF-8",NOW,NOW.plusSeconds(3600),null,null,null,null);
        when(persistence.findExport(TENANT,id)).thenReturn(ready);
        when(persistence.findArtifact(TENANT,id)).thenReturn(artifact);
        when(tokens.issue()).thenReturn(new ReportDownloadTokenProtector.TokenIssue("token-"+"x".repeat(40),"b".repeat(64)));
        when(persistence.issueDownloadToken(TENANT,id,"b".repeat(64),7L,NOW.plusSeconds(600),2)).thenReturn(1);
        assertThat(service.issueDownloadToken(id).token()).startsWith("token-");
        when(store.get(artifact.objectKey())).thenReturn(content);
        when(tokens.hash("token-"+"x".repeat(40))).thenReturn("b".repeat(64));
        when(persistence.consumeDownloadToken(TENANT,id,"b".repeat(64),7L,NOW)).thenReturn(1);
        assertThat(service.download(id,"token-"+"x".repeat(40)).content()).isEqualTo(content);
        when(persistence.consumeDownloadToken(TENANT,id,"b".repeat(64),7L,NOW)).thenReturn(0);
        assertThatThrownBy(() -> service.download(id,"token-"+"x".repeat(40))).isInstanceOf(ServiceException.class);
    }

    @Test void rejectsOversizedExportAndDetectsArtifactTampering() {
        LocalDate day=LocalDate.of(2026,8,17); String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        when(persistence.activeProjectionVersion(TENANT,"SALES")).thenReturn("g5d-v1");
        when(persistence.countSales(TENANT,"g5d-v1",day,day,List.of(11L))).thenReturn(100_001L);
        assertThatThrownBy(() -> service.request(new ExportRequest(id,"SALES_DAILY",day,day,Set.of(11L),
            Set.of("businessDate"),"01ARZ3NDEKTSV4RRFFQ69G5FAW"))).isInstanceOf(ServiceException.class);
        ExportRow ready=row(id,"SALES_DAILY","businessDate","READY",false,7L,null,1,2,"a".repeat(64),NOW.plusSeconds(3600));
        ArtifactRow artifact=new ArtifactRow(id,"reporting/tenant_alpha/"+id+"/"+"a".repeat(64)+".csv","a".repeat(64),3,
            "text/csv",NOW,NOW.plusSeconds(3600),null,null,null,null);
        when(persistence.findExport(TENANT,id)).thenReturn(ready); when(persistence.findArtifact(TENANT,id)).thenReturn(artifact);
        when(store.get(artifact.objectKey())).thenReturn("bad".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.download(id,"x".repeat(40))).isInstanceOf(ServiceException.class);
        verify(differences).record(eq("ARTIFACT_DIGEST_MISMATCH"),eq(id),anyString());
    }

    @Test void paymentReconciliationExportAlwaysRequiresApprovalAndUsesOnlyProviderNeutralProjection() {
        LocalDate day=LocalDate.of(2026,8,17); String id="01ARZ3NDEKTSV4RRFFQ69G5FAV";
        when(paymentPersistence.count(TENANT,day,day,List.of(11L))).thenReturn(1L);
        when(persistence.insertExportIfAbsent(eq(TENANT),any())).thenReturn(true);
        ExportView requested=service.request(new ExportRequest(id,"PAYMENT_RECONCILIATION",day,day,Set.of(11L),
            Set.of("businessDate","differenceType","internalAmountMinor","billAmountMinor"),
            "01ARZ3NDEKTSV4RRFFQ69G5FAW"));
        assertThat(requested.approvalRequired()).isTrue();
        verify(paymentPersistence).count(TENANT,day,day,List.of(11L));
        verify(persistence,never()).activeProjectionVersion(TENANT,"PAYMENT_RECONCILIATION");

        ExportRow approved=row(id,"PAYMENT_RECONCILIATION",
            "billAmountMinor,businessDate,differenceType,internalAmountMinor","APPROVED",true,7L,8L,1,1,null,null);
        ExportRow ready=row(id,"PAYMENT_RECONCILIATION",
            "billAmountMinor,businessDate,differenceType,internalAmountMinor","READY",true,7L,8L,1,3,
            "a".repeat(64),NOW.plus(Duration.ofHours(24)));
        when(persistence.findExport(TENANT,id)).thenReturn(approved,ready);
        when(persistence.transitionExport(TENANT,id,"APPROVED","GENERATING",8L,null,1,NOW)).thenReturn(1);
        when(paymentPersistence.query(TENANT,day,day,11L,null,null)).thenReturn(List.of(
            new ReconciliationView(id,id,"PAYMENT","01ARZ3NDEKTSV4RRFFQ69G5FAW",
                "01ARZ3NDEKTSV4RRFFQ69G5FAX",day,1L,11L,"T1","CNY",100L,90L,"SUCCEEDED",
                "SUCCEEDED",day,day,"AMOUNT_MISMATCH","OPEN",null,NOW,NOW,0)));
        assertThat(service.generate(new ExportGenerate(id,1,"01ARZ3NDEKTSV4RRFFQ69G5FAY")).state()).isEqualTo("READY");
        verify(store).put(matches("^reporting/tenant_alpha/"+id+"/[a-f0-9]{64}\\.csv$"),
            argThat(content -> new String(content,StandardCharsets.UTF_8).contains("AMOUNT_MISMATCH")));
    }

    private ExportRow row(String id,String type,String fields,String state,boolean approval,Long requestedBy,
                          Long approvedBy,int rows,int version,String sha,Instant expires) {
        String requestHash=CanonicalReportHash.sha256(type+"|2026-08-17|2026-08-17|11|"+fields);
        return new ExportRow(id,requestHash,type,LocalDate.of(2026,8,17),LocalDate.of(2026,8,17),"11",fields,
            state,approval,requestedBy,approvedBy,rows,"01ARZ3NDEKTSV4RRFFQ69G5FAW",sha,expires,version,NOW);
    }
}
