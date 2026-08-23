package com.jingshanghui.pos.operations.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.port.DailyCloseOwnerGateway;
import com.jingshanghui.pos.operations.application.port.DailyClosePersistencePort;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.*;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DailyCloseServiceTest {
    private static final String CLOSE_ID="01K3M000000000000000000001";
    private static final String HASH="a".repeat(64);
    @Mock TrustedTenantContext context;
    @Mock ScopeAuthorizationService authorization;
    @Mock DailyClosePersistencePort persistence;
    @Mock DailyCloseOwnerGateway owners;
    @Mock UlidGenerator ids;
    DailyCloseService service;

    @BeforeEach void setUp(){
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A",101L,1L,"admin"));
        lenient().when(ids.next()).thenReturn(CLOSE_ID);
        service=new DailyCloseService(context,authorization,persistence,owners,ids,
            Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"),ZoneOffset.UTC));
    }

    @Test
    void createsTrustedDraftAndReplaysSameCreateKey(){
        OwnerSnapshot snapshot=snapshot();
        CloseRecord created=close("DRAFT",0,"0".repeat(64),"0".repeat(64),101L);
        when(owners.capture(10L,LocalDate.of(2026,8,23))).thenReturn(snapshot);
        when(persistence.nextVersion("TENANT_A",10L,LocalDate.of(2026,8,23))).thenReturn(1);
        when(persistence.find("TENANT_A",CLOSE_ID)).thenReturn(created);
        CloseDetail result=service.create(new CreateClose(10L,LocalDate.of(2026,8,23),null,null,"cls-create-001","trace-001"));
        assertThat(result.close().state()).isEqualTo("DRAFT");
        verify(persistence).insertClose(any());verify(persistence).appendState(any());verify(persistence).appendAudit(any());

        var write=org.mockito.ArgumentCaptor.forClass(DailyClosePersistencePort.CloseWrite.class);
        verify(persistence).insertClose(write.capture());
        reset(owners);
        CloseRecord replay=new CloseRecord(created.closeId(),created.tenantId(),created.storeId(),created.businessDate(),created.zoneId(),
            created.businessDayStart(),created.closeVersion(),null,null,"DRAFT",created.snapshotSha256(),created.manifestSha256(),
            created.idempotencyKey(),write.getValue().requestSha256(),created.creatorUserId(),0,0,created.createdAt(),created.updatedAt());
        when(persistence.findByCreateKey("TENANT_A","cls-create-001")).thenReturn(replay);
        assertThat(service.create(new CreateClose(10L,LocalDate.of(2026,8,23),null,null,"cls-create-001","trace-999")).close()).isEqualTo(replay);
        verifyNoInteractions(owners);
    }

    @Test
    void preflightFreezesOwnerFactsAndKeepsExternalBlocked(){
        OwnerSnapshot snapshot=snapshot();
        String snapshotHash=CanonicalJson.from(snapshot.canonicalContent(),256*1024).sha256();
        String manifestHash=manifest(snapshot);
        CloseRecord draft=close("DRAFT",0,"0".repeat(64),"0".repeat(64),101L);
        CloseRecord preflighting=close("PREFLIGHTING",1,"0".repeat(64),"0".repeat(64),101L);
        CloseRecord ready=close("READY",2,snapshotHash,manifestHash,101L);
        when(persistence.lock("TENANT_A",CLOSE_ID)).thenReturn(draft);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find("TENANT_A",CLOSE_ID)).thenReturn(preflighting,ready,ready);
        when(persistence.nextPreflightRun("TENANT_A",CLOSE_ID)).thenReturn(1);
        when(owners.capture(10L,LocalDate.of(2026,8,23))).thenReturn(snapshot);

        CloseDetail result=service.preflight(new CloseCommand(CLOSE_ID,"cls-preflight-001","trace-001"));
        assertThat(result.close().state()).isEqualTo("READY");
        verify(persistence).insertSnapshot(any());
        verify(persistence,times(5)).insertCheckpoint(any());
        verify(persistence,times(2)).insertPreflight(any());
        verify(persistence).appendOutbox(any());
        verify(persistence).insertCommand(any());
    }

    @Test
    void rejectsSelfApprovalAndFailsSignatureWhenSourceDrifts(){
        CloseRecord ready=close("READY",1,HASH,HASH,101L);
        when(persistence.lock("TENANT_A",CLOSE_ID)).thenReturn(ready);
        assertThatThrownBy(() -> service.approve(new ApprovalCommand(CLOSE_ID,"已完成独立审批复核",
            "cls-approve-001","trace-001"))).isInstanceOf(ServiceException.class).hasMessageContaining("创建人不得审批");
        verify(persistence,never()).insertApproval(any());

        reset(persistence,owners);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A",102L,1L,"checker"));
        CloseRecord approved=close("APPROVED",2,HASH,HASH,101L);
        CloseRecord failed=close("FAILED",3,HASH,HASH,101L);
        when(persistence.lock("TENANT_A",CLOSE_ID)).thenReturn(approved);
        when(persistence.listApprovals("TENANT_A",CLOSE_ID)).thenReturn(List.of(
            new ApprovalRecord(CLOSE_ID,CLOSE_ID,102L,HASH,LocalDateTime.MIN)));
        when(owners.capture(10L,LocalDate.of(2026,8,23))).thenReturn(snapshot());
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find("TENANT_A",CLOSE_ID)).thenReturn(failed,failed);

        CloseDetail result=service.signAndClose(new CloseCommand(CLOSE_ID,"cls-sign-001","trace-002"));
        assertThat(result.close().state()).isEqualTo("FAILED");
        verify(persistence).insertDifference(any());
        verify(persistence,never()).insertSignature(any());
    }

    @Test
    void closesWithIndependentSignatureAndNeverRecomputesFrozenFacts(){
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A",102L,1L,"checker"));
        OwnerSnapshot frozen=snapshot();
        String snapshotHash=CanonicalJson.from(frozen.canonicalContent(),256*1024).sha256();
        String manifestHash=manifest(frozen);
        CloseRecord approved=close("APPROVED",2,snapshotHash,manifestHash,101L);
        CloseRecord closing=close("CLOSING",3,snapshotHash,manifestHash,101L);
        CloseRecord closed=close("CLOSED",4,snapshotHash,manifestHash,101L);
        when(persistence.lock("TENANT_A",CLOSE_ID)).thenReturn(approved);
        when(persistence.listApprovals("TENANT_A",CLOSE_ID)).thenReturn(List.of(
            new ApprovalRecord(CLOSE_ID,CLOSE_ID,102L,HASH,LocalDateTime.MIN)));
        when(owners.capture(10L,LocalDate.of(2026,8,23))).thenReturn(frozen);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.find("TENANT_A",CLOSE_ID)).thenReturn(closing,closed);

        CloseDetail result=service.signAndClose(new CloseCommand(CLOSE_ID,"cls-sign-002","trace-003"));

        assertThat(result.close().state()).isEqualTo("CLOSED");
        verify(persistence).insertSignature(any());
        verify(persistence,times(2)).changeState(any());
        verify(persistence,never()).insertSnapshot(any());
    }

    @Test
    void appendsLateFactDifferenceWithoutReopeningClosedFact(){
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A",102L,1L,"checker"));
        CloseRecord closed=close("CLOSED",4,HASH,HASH,101L);
        DifferenceRecord difference=new DifferenceRecord(CLOSE_ID,CLOSE_ID,"LATE_FACT_REQUIRES_CORRECTION",
            "OPEN",HASH,"b".repeat(64),"c".repeat(64),LocalDateTime.MIN);
        when(persistence.lock("TENANT_A",CLOSE_ID)).thenReturn(closed);
        when(owners.capture(10L,LocalDate.of(2026,8,23))).thenReturn(snapshot());
        when(persistence.listDifferences("TENANT_A",CLOSE_ID)).thenReturn(List.of(difference));

        CloseDetail result=service.detectLateFacts(new CloseCommand(CLOSE_ID,"cls-late-001","trace-004"));

        assertThat(result.close().state()).isEqualTo("CLOSED");
        assertThat(result.correctionRequired()).isTrue();
        verify(persistence).insertDifference(any());
        verify(persistence,never()).changeState(any());
        verify(persistence).appendOutbox(any());
    }

    @Test
    void listRequiresExplicitAuthorizedStoreScope(){
        assertThatThrownBy(() -> service.list(null, LocalDate.of(2026,8,23), 50))
            .isInstanceOf(ServiceException.class).hasMessageContaining("门店无效");
        verifyNoInteractions(authorization, persistence);

        when(persistence.list("TENANT_A",10L,LocalDate.of(2026,8,23),100)).thenReturn(List.of());
        assertThat(service.list(10L,LocalDate.of(2026,8,23),500)).isEmpty();
        verify(authorization).requireStoreAccess(10L);
        verify(persistence).list("TENANT_A",10L,LocalDate.of(2026,8,23),100);
    }

    private OwnerSnapshot snapshot(){
        SnapshotAmounts amounts=new SnapshotAmounts("CNY",1,0,0,100,10,0,90,0,90,0,0,0,0,0,0);
        List<SourceCheckpoint> checkpoints=List.of(
            cp("FOUNDATION",1),cp("SHIFT_ORDER",2),cp("PAYMENT_REFUND",3),cp("SYNC",4),cp("REPORTING",5));
        List<PreflightFact> checks=List.of(
            new PreflightFact("INTERNAL","SHIFT_ORDER",true,false,CheckStatus.PASS,HASH,"通过"),
            new PreflightFact("EXTERNAL_PROVIDER_RECONCILIATION","PAYMENT_PROVIDER",false,true,CheckStatus.BLOCKED,"0".repeat(64),"外部阻断"));
        return new OwnerSnapshot("Asia/Shanghai",LocalTime.of(3,0),amounts,checkpoints,checks,
            Map.of("businessDate","2026-08-23","grossMinor",100,"receivableMinor",90));
    }
    private SourceCheckpoint cp(String owner,long sequence){return new SourceCheckpoint(owner,"v"+sequence,sequence,"CURRENT",HASH);}
    private String manifest(OwnerSnapshot snapshot){
        var values=snapshot.checkpoints().stream().map(v -> Map.<String,Object>of("ownerCode",v.ownerCode(),"sourceVersion",v.sourceVersion(),
            "sourceSequence",v.sourceSequence(),"sourceStatus",v.sourceStatus(),"contentSha256",v.contentSha256())).toList();
        return CanonicalJson.from(Map.of("checkpoints",values)).sha256();
    }
    private CloseRecord close(String state,int version,String snapshot,String manifest,Long creator){return new CloseRecord(CLOSE_ID,"TENANT_A",10L,
        LocalDate.of(2026,8,23),"Asia/Shanghai",LocalTime.of(3,0),1,null,null,state,snapshot,manifest,
        "cls-create-001",HASH,creator,state.equals("DRAFT")?0:1,version,LocalDateTime.MIN,LocalDateTime.MIN);}
}
