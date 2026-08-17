package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.*;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.member.application.model.MemberViews.MemberView;
import com.jingshanghui.pos.member.application.model.PointsCommands.*;
import com.jingshanghui.pos.member.application.model.PointsViews.*;
import com.jingshanghui.pos.member.application.port.*;
import com.jingshanghui.pos.member.application.port.PointsPersistencePort.*;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证积分 FEFO、冻结引用、退货债务、累计上限、到期和投影重建。 */
class MemberPointsServiceTest {
    private static final String TENANT="TENANT_A", MEMBER="01K5C000000000000000000001";
    private static final String COMMAND="01K5C000000000000000000010", LEDGER="01K5C000000000000000000011";
    private static final String SOURCE="01K5C000000000000000000012", CORRELATION="01K5C000000000000000000013";
    private static final OffsetDateTime NOW=OffsetDateTime.parse("2026-08-17T10:00:00Z");
    private final TrustedTenantContext tenants=mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization=mock(ScopeAuthorizationService.class);
    private final StoreService stores=mock(StoreService.class);
    private final DomainAuditService audit=mock(DomainAuditService.class);
    private final MemberPersistencePort members=mock(MemberPersistencePort.class);
    private final PointsPersistencePort points=mock(PointsPersistencePort.class);
    private final MemberIdGenerator ids=mock(MemberIdGenerator.class);
    private MemberPointsService service;

    @BeforeEach void setUp(){
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT,7L,8L,"synthetic-user"));
        when(tenants.requireTenantId()).thenReturn(TENANT);
        when(stores.businessDate(eq(1001L),any())).thenReturn(new BusinessDateView(1001L,"Asia/Shanghai",
            LocalTime.MIDNIGHT,NOW.toInstant(),LocalDate.of(2026,8,17)));
        when(members.findMember(TENANT,MEMBER)).thenReturn(new MemberView(MEMBER,"ACTIVE","会员-000001",0,NOW.toLocalDateTime()));
        when(ids.next()).thenReturn("01K5C000000000000000000101","01K5C000000000000000000102",
            "01K5C000000000000000000103","01K5C000000000000000000104",
            "01K5C000000000000000000105","01K5C000000000000000000106");
        when(points.updateAccount(any())).thenReturn(1); when(points.updateLot(any())).thenReturn(1);
        service=new MemberPointsService(tenants,authorization,stores,audit,members,points,ids,
            Clock.fixed(NOW.toInstant(),ZoneOffset.UTC));
    }

    @Test void orderEarnRepaysDebtAndCreatesOnlyNetAvailableLot(){
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("0","0","3",2));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"EARN","5","2","0","-3",SOURCE,null));
        LedgerView result=service.earn(new Earn(COMMAND,LEDGER,MEMBER,SOURCE,1001L,d("5"),"points-v1",
            NOW.plusDays(30),NOW,CORRELATION));
        assertThat(result.eventType()).isEqualTo("EARN");
        verify(points).insertLot(argThat(lot -> lot.originalPoints().compareTo(d("5"))==0
            && lot.availablePoints().compareTo(d("2"))==0));
        verify(points).updateAccount(argThat(a -> a.availablePoints().compareTo(d("2"))==0
            && a.debtPoints().signum()==0 && a.expectedVersion()==2));
        verify(members).insertOutbox(argThat(event -> "member.points.posted.v1".equals(event.eventType())));
    }

    @Test void freezeConsumesEarliestExpiringLotsInStableOrder(){
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("8","0","0",1));
        LotRow first=lot("01K5C000000000000000000021","3","0",NOW.plusDays(1).toLocalDateTime(),0);
        LotRow second=lot("01K5C000000000000000000022","5","0",NOW.plusDays(2).toLocalDateTime(),0);
        when(points.listFefoAvailableLots(TENANT,MEMBER,NOW.toLocalDateTime())).thenReturn(List.of(first,second));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"FREEZE","6","-6","6","0",LEDGER,null));
        service.freeze(new Freeze(COMMAND,LEDGER,MEMBER,1001L,d("6"),"points-v1",NOW,CORRELATION));
        ArgumentCaptor<LotUpdate> updates=ArgumentCaptor.forClass(LotUpdate.class); verify(points,times(2)).updateLot(updates.capture());
        assertThat(updates.getAllValues().get(0).availablePoints()).isZero();
        assertThat(updates.getAllValues().get(1).availablePoints()).isEqualByComparingTo("2");
        verify(points,times(2)).insertAllocation(any());
    }

    @Test void spendUsesOriginalFreezeAllocationAndCannotReselectLots(){
        String freeze="01K5C000000000000000000020", lotId="01K5C000000000000000000021";
        when(points.findLedger(TENANT,freeze)).thenReturn(ledger(freeze,"FREEZE","5","-5","5","0",freeze,null));
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("0","5","0",2));
        when(points.listFrozenAllocations(TENANT,freeze)).thenReturn(List.of(
            new FrozenAllocationRow(lotId,d("5"),d("1"),NOW.plusDays(1).toLocalDateTime())));
        when(points.lockLot(TENANT,lotId)).thenReturn(lot(lotId,"0","4",NOW.plusDays(1).toLocalDateTime(),1));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"SPEND","3","0","-3","0",freeze,freeze));
        service.settleFrozen(new FrozenSettlement(COMMAND,LEDGER,MEMBER,freeze,1001L,d("3"),"SPEND","points-v1",NOW,CORRELATION));
        verify(points,never()).listFefoAvailableLots(anyString(),anyString(),any());
        verify(points).insertAllocation(argThat(a -> "SPEND".equals(a.allocationType())
            && freeze.equals(a.parentLedgerId()) && a.points().compareTo(d("3"))==0));
    }

    @Test void returnEarnUsesOriginalLotAndCreatesExplicitDebtForSpentPart(){
        String original="01K5C000000000000000000020";
        when(points.findLedger(TENANT,original)).thenReturn(ledger(original,"EARN","5","5","0","0",SOURCE,null));
        when(points.sumReversedAmount(TENANT,original,"RETURN_EARN_REVERSAL")).thenReturn(d("1"));
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("2","0","0",3));
        when(points.lockLot(TENANT,original)).thenReturn(lot(original,"2","0",NOW.plusDays(1).toLocalDateTime(),1));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"RETURN_EARN_REVERSAL","4","-2","0","2",SOURCE,original));
        service.reverseEarn(new ReturnEarn(COMMAND,LEDGER,MEMBER,SOURCE,original,1001L,d("4"),"points-v1",NOW,CORRELATION));
        verify(points).updateAccount(argThat(a -> a.availablePoints().signum()==0 && a.debtPoints().compareTo(d("2"))==0));
        assertThatThrownBy(() -> service.reverseEarn(new ReturnEarn("01K5C000000000000000000099",
            "01K5C000000000000000000098",MEMBER,SOURCE,original,1001L,d("5"),"points-v1",NOW,CORRELATION)))
            .hasMessageContaining("MEM-POINTS-019");
    }

    @Test void returnSpendRestoresAgainstOriginalAllocationAndFrozenPolicy(){
        String original="01K5C000000000000000000020", lotId="01K5C000000000000000000021";
        when(points.findLedger(TENANT,original)).thenReturn(ledger(original,"SPEND","5","0","-5","0",SOURCE,
            "01K5C000000000000000000019"));
        when(points.sumReversedAmount(TENANT,original,"RETURN_SPEND_REVERSAL")).thenReturn(d("1"));
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("0","0","2",4));
        when(points.listSpendAllocations(TENANT,original)).thenReturn(List.of(
            new SpendAllocationRow(lotId,d("5"),d("1"),NOW.plusDays(10).toLocalDateTime())));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"RETURN_SPEND_REVERSAL","3","1","0","-2",SOURCE,original));
        service.reverseSpend(new ReturnSpend(COMMAND,LEDGER,MEMBER,SOURCE,original,1001L,d("3"),"points-v1",
            NOW.plusDays(30),NOW,CORRELATION));
        verify(points).insertLot(argThat(lot -> "points-v1".equals(lot.policyVersion())
            && lot.availablePoints().compareTo(d("1"))==0));
        verify(points).insertAllocation(argThat(a -> "RETURN_SPEND_REVERSAL".equals(a.allocationType())));
    }

    @Test void expiryAndNegativeAdjustmentUpdateLotsWithoutNegativeAvailable(){
        String lotId="01K5C000000000000000000021";
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("4","0","0",1));
        when(points.lockLot(TENANT,lotId)).thenReturn(lot(lotId,"4","0",NOW.minusDays(1).toLocalDateTime(),0));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"EXPIRE","4","-4","0","0",lotId,null));
        service.expire(new ExpireLot(COMMAND,LEDGER,MEMBER,lotId,1001L,"points-v1",NOW,CORRELATION));
        verify(points).insertAllocation(argThat(a -> "EXPIRE".equals(a.allocationType())));

        reset(points); when(points.updateAccount(any())).thenReturn(1); when(points.updateLot(any())).thenReturn(1);
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("2","0","0",2));
        when(points.listFefoAvailableLots(TENANT,MEMBER,NOW.toLocalDateTime())).thenReturn(List.of(
            lot(lotId,"2","0",NOW.plusDays(1).toLocalDateTime(),1)));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"MANUAL_ADJUST","5","-2","0","3",LEDGER,null));
        service.adjust(new ManualAdjust(COMMAND,LEDGER,MEMBER,1001L,d("-5"),"points-v1","synthetic correction",
            9L,"01K5C000000000000000000015",NOW,CORRELATION));
        verify(points).insertLedger(argThat(value -> value.storeId()==1001L
            && value.businessDate().equals(LocalDate.of(2026,8,17)) && value.actorUserId()==7L
            && value.approvalUserId()==9L && "01K5C000000000000000000015".equals(value.approvalRef())));
        verify(authorization).requireTenantAdministrator(); verify(audit).append(eq("MEMBER_POINTS_MANUAL_ADJUSTED"),
            eq("MEMBER"),eq(MEMBER),isNull(),any(),any());
    }

    @Test void manualAdjustmentRejectsSelfApprovalBeforeAnyLedgerWrite(){
        assertThatThrownBy(() -> service.adjust(new ManualAdjust(COMMAND,LEDGER,MEMBER,1001L,d("1"),
            "points-v1","synthetic correction",7L,"01K5C000000000000000000015",NOW,CORRELATION)))
            .hasMessageContaining("操作人与审批人必须分离");
        verify(points,never()).insertLedger(any());
    }

    @Test void duplicatePointsCommandReturnsOriginalAndDifferentContentIsRejected(){
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("5","0","0",1));
        when(points.listFefoAvailableLots(TENANT,MEMBER,NOW.toLocalDateTime())).thenReturn(List.of(
            lot("01K5C000000000000000000021","5","0",NOW.plusDays(1).toLocalDateTime(),0)));
        when(points.findLedger(TENANT,LEDGER)).thenReturn(ledger(LEDGER,"FREEZE","2","-2","2","0",LEDGER,null));
        Freeze command=new Freeze(COMMAND,LEDGER,MEMBER,1001L,d("2"),"points-v1",NOW,CORRELATION);
        service.freeze(command);
        ArgumentCaptor<LedgerWrite> write=ArgumentCaptor.forClass(LedgerWrite.class);
        verify(points).insertLedger(write.capture());
        LedgerView replay=ledgerWithRequest(LEDGER,"FREEZE","2","-2","2","0",LEDGER,null,
            write.getValue().requestSha256());
        when(points.findLedgerByCommand(TENANT,COMMAND)).thenReturn(replay);
        assertThat(service.freeze(command)).isEqualTo(replay);
        assertThatThrownBy(() -> service.freeze(new Freeze(COMMAND,LEDGER,MEMBER,1001L,d("3"),
            "points-v1",NOW,CORRELATION))).hasMessageContaining("MEM-IDEMP-001");
        verify(points,times(1)).insertLedger(any());
    }

    @Test void lateReturnWithoutOriginalFactFailsClosedAndCanBeRetriedWithSameCommand(){
        assertThatThrownBy(() -> service.reverseEarn(new ReturnEarn(COMMAND,LEDGER,MEMBER,SOURCE,
            "01K5C000000000000000000020",1001L,d("1"),"points-v1",NOW,CORRELATION)))
            .hasMessageContaining("MEM-POINTS-017");
        verify(points,never()).insertLedger(any());
        verify(points,never()).updateAccount(any());
    }

    @Test void rebuildSumsImmutableDeltasAndUsesOptimisticProjectionReplacement(){
        when(points.lockAccount(TENANT,MEMBER)).thenReturn(account("999","0","0",7));
        when(points.listLedgers(TENANT,MEMBER)).thenReturn(List.of(
            ledger("01K5C000000000000000000020","EARN","5","5","0","0",SOURCE,null),
            ledger("01K5C000000000000000000021","FREEZE","2","-2","2","0",SOURCE,null),
            ledger("01K5C000000000000000000022","SPEND","1","0","-1","0",SOURCE,null)));
        when(points.replaceAccountProjection(any())).thenReturn(1);
        when(points.findAccount(TENANT,MEMBER)).thenReturn(account("3","1","0",8));
        AccountView rebuilt=service.rebuild(MEMBER,1001L);
        assertThat(rebuilt.availablePoints()).isEqualByComparingTo("3");
        verify(points).replaceAccountProjection(argThat(a -> a.availablePoints().compareTo(d("3"))==0
            && a.frozenPoints().compareTo(d("1"))==0 && a.expectedVersion()==7));
    }

    private static BigDecimal d(String v){return new BigDecimal(v).setScale(6);}
    private static AccountView account(String a,String f,String debt,int version){
        return new AccountView(MEMBER,d(a),d(f),d(debt),version,null);
    }
    private static LotRow lot(String id,String a,String f,LocalDateTime expires,int version){
        return new LotRow(id,id,d(a).add(d(f)),d(a),d(f),"points-v1",expires,version);
    }
    private static LedgerView ledger(String id,String type,String amount,String a,String f,String debt,
                                     String source,String original){
        return ledgerWithRequest(id,type,amount,a,f,debt,source,original,"a".repeat(64));
    }
    private static LedgerView ledgerWithRequest(String id,String type,String amount,String a,String f,String debt,
                                                String source,String original,String requestSha256){
        return new LedgerView(id,MEMBER,type,d(amount),d(a),d(f),d(debt),
            "EARN".equals(type)?"ORDER":"RETURN",source,original,"points-v1",1001L,LocalDate.of(2026,8,17),
            "SYNTHETIC",7L,null,null,NOW.toLocalDateTime(),
            null,requestSha256,"b".repeat(64));
    }
}
