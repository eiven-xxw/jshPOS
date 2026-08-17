package com.jingshanghui.pos.member.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.member.application.model.PointsCommands.*;
import com.jingshanghui.pos.member.application.model.PointsViews.*;
import com.jingshanghui.pos.member.application.port.MemberPersistencePort;
import com.jingshanghui.pos.member.application.port.PointsPersistencePort;
import com.jingshanghui.pos.member.application.port.PointsPersistencePort.*;
import com.jingshanghui.pos.member.domain.MemberRules;
import com.jingshanghui.pos.member.domain.PointsRules;
import com.jingshanghui.pos.member.domain.PointsRules.*;
import com.jingshanghui.pos.member.infrastructure.id.MemberIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** T2-MEM-002 等级历史、积分流水、FEFO 批次和可重建账户投影服务。 */
@Service
@RequiredArgsConstructor
public class MemberPointsService {
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final StoreService stores;
    private final DomainAuditService audit;
    private final MemberPersistencePort members;
    private final PointsPersistencePort points;
    private final MemberIdGenerator ids;
    private final Clock clock;

    /** 仅消费已同步完成订单的冻结事实；相同命令异内容失败关闭。 */
    @Transactional
    public LedgerView earn(Earn command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId());
        MemberRules.requireUlid(command.sourceOrderId(),"来源订单");
        BigDecimal amount=PointsRules.positive(command.amount(),"获赠积分");
        if(command.expiresAt()!=null && !command.expiresAt().isAfter(command.occurredAt()))
            throw new ServiceException("MEM-POINTS-007: 到期时间必须晚于入账时间",400);
        String hash=requestHash(Map.of("memberId",command.memberId(),"sourceOrderId",command.sourceOrderId(),
            "storeId",command.storeId(),
            "amount",amount.toPlainString(),"policyVersion",command.policyVersion(),"expiresAt",
            optionalTime(command.expiresAt())));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        requireActive(principal.tenantId(),command.memberId());
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        Delta delta=PointsRules.earn(balance(account),amount);
        points.insertLot(new LotWrite(principal.tenantId(),command.ledgerId(),command.memberId(),command.ledgerId(),
            amount,delta.available(),PointsRules.ZERO,command.policyVersion(),utc(command.expiresAt()),
            utc(command.occurredAt())));
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),"EARN",amount,delta,
            "ORDER",command.sourceOrderId(),null,command.policyVersion(),command.occurredAt(),command.expiresAt(),
            command.correlationId(),hash,account,scope,"ORDER_EARN",null,null);
    }

    /** 按到期时间优先冻结可用批次；不足时整个事务回滚。 */
    @Transactional
    public LedgerView freeze(Freeze command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId());
        BigDecimal amount=PointsRules.positive(command.amount(),"冻结积分");
        String hash=requestHash(Map.of("memberId",command.memberId(),"storeId",command.storeId(),"amount",amount.toPlainString(),
            "policyVersion",command.policyVersion(),"occurredAt",command.occurredAt().toString()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        requireActive(principal.tenantId(),command.memberId());
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        if(account.availablePoints().compareTo(amount)<0) throw insufficient();
        BigDecimal remaining=amount;
        for(LotRow lot:points.listFefoAvailableLots(principal.tenantId(),command.memberId(),utc(command.occurredAt()))) {
            if(remaining.signum()==0) break;
            BigDecimal allocated=lot.availablePoints().min(remaining);
            if(allocated.signum()==0) continue;
            updateLot(principal.tenantId(),lot,lot.availablePoints().subtract(allocated),lot.frozenPoints().add(allocated));
            points.insertAllocation(new AllocationWrite(principal.tenantId(),ids.next(),command.ledgerId(),
                lot.lotId(),null,allocated,"FREEZE",utc(command.occurredAt())));
            remaining=remaining.subtract(allocated);
        }
        if(remaining.signum()!=0) throw new ServiceException("MEM-POINTS-009: 批次投影与账户不一致",409);
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),"FREEZE",amount,
            PointsRules.freeze(amount),"ONLINE_REDEMPTION",command.ledgerId(),null,command.policyVersion(),
            command.occurredAt(),null,command.correlationId(),hash,account,scope,"ONLINE_FREEZE",null,null);
    }

    /** 消费或解冻时严格沿用原冻结批次，不允许重新执行 FEFO。 */
    @Transactional
    public LedgerView settleFrozen(FrozenSettlement command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId());
        MemberRules.requireUlid(command.freezeLedgerId(),"冻结流水");
        if(!Set.of("SPEND","UNFREEZE").contains(command.action()))
            throw new ServiceException("MEM-POINTS-010: 冻结结算动作无效",400);
        BigDecimal amount=PointsRules.positive(command.amount(),"冻结结算积分");
        String hash=requestHash(Map.of("memberId",command.memberId(),"freezeLedgerId",command.freezeLedgerId(),
            "storeId",command.storeId(),"amount",amount.toPlainString(),"action",command.action(),"policyVersion",command.policyVersion()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        LedgerView original=requireLedger(principal.tenantId(),command.freezeLedgerId(),command.memberId(),"FREEZE");
        if(!original.policyVersion().equals(command.policyVersion()))
            throw new ServiceException("MEM-POINTS-011: 冻结结算必须使用原策略版本",409);
        if(!Objects.equals(original.storeId(),command.storeId()))
            throw new ServiceException("MEM-POINTS-022: 冻结结算必须在原冻结门店完成",409);
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        if(account.frozenPoints().compareTo(amount)<0) throw insufficient();
        BigDecimal remaining=amount;
        for(FrozenAllocationRow allocation:points.listFrozenAllocations(principal.tenantId(),command.freezeLedgerId())) {
            if(remaining.signum()==0) break;
            BigDecimal open=allocation.frozenPoints().subtract(allocation.releasedPoints());
            BigDecimal used=open.min(remaining); if(used.signum()==0) continue;
            LotRow lot=requireLot(principal.tenantId(),allocation.lotId());
            BigDecimal nextAvailable="UNFREEZE".equals(command.action())?lot.availablePoints().add(used):lot.availablePoints();
            updateLot(principal.tenantId(),lot,nextAvailable,lot.frozenPoints().subtract(used));
            points.insertAllocation(new AllocationWrite(principal.tenantId(),ids.next(),command.ledgerId(),
                lot.lotId(),command.freezeLedgerId(),used,command.action(),utc(command.occurredAt())));
            remaining=remaining.subtract(used);
        }
        if(remaining.signum()!=0) throw new ServiceException("MEM-POINTS-012: 原冻结分配余额不足",409);
        Delta delta="SPEND".equals(command.action())?PointsRules.spendFrozen(amount):PointsRules.unfreeze(amount);
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),command.action(),amount,
            delta,"ONLINE_REDEMPTION",command.freezeLedgerId(),command.freezeLedgerId(),command.policyVersion(),
            command.occurredAt(),null,command.correlationId(),hash,account,scope,
            "SPEND".equals(command.action())?"ONLINE_SPEND":"ONLINE_UNFREEZE",null,null);
    }

    /** 退货扣回只引用原获赠流水；已消费部分形成显式债务。 */
    @Transactional
    public LedgerView reverseEarn(ReturnEarn command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId());
        MemberRules.requireUlid(command.returnId(),"退货"); MemberRules.requireUlid(command.originalEarnLedgerId(),"原获赠流水");
        BigDecimal amount=PointsRules.positive(command.amount(),"退货扣回积分");
        String hash=requestHash(Map.of("memberId",command.memberId(),"returnId",command.returnId(),
            "originalEarnLedgerId",command.originalEarnLedgerId(),"storeId",command.storeId(),"amount",amount.toPlainString(),
            "policyVersion",command.policyVersion()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        LedgerView original=requireLedger(principal.tenantId(),command.originalEarnLedgerId(),command.memberId(),"EARN");
        requireOriginalPolicy(original.policyVersion(),command.policyVersion(),"退货扣回");
        requireCumulativeLimit(principal.tenantId(),original,amount,"RETURN_EARN_REVERSAL");
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        LotRow lot=requireLot(principal.tenantId(),command.originalEarnLedgerId());
        if(lot.frozenPoints().signum()!=0)
            throw new ServiceException("MEM-POINTS-021: 原获赠批次仍有冻结积分，必须先完成冻结结算",409);
        Delta delta=PointsRules.reverseEarn(lot.availablePoints(),amount);
        BigDecimal fromLot=delta.available().abs();
        updateLot(principal.tenantId(),lot,lot.availablePoints().subtract(fromLot),lot.frozenPoints());
        if(fromLot.signum()>0) points.insertAllocation(new AllocationWrite(principal.tenantId(),ids.next(),
            command.ledgerId(),lot.lotId(),original.ledgerId(),fromLot,"RETURN_EARN_REVERSAL",utc(command.occurredAt())));
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),"RETURN_EARN_REVERSAL",
            amount,delta,"RETURN",command.returnId(),original.ledgerId(),original.policyVersion(),
            command.occurredAt(),null,command.correlationId(),hash,account,scope,"RETURN_EARN",null,null);
    }

    /** 退还原积分消费，按原消费分配校验累计上限并创建恢复批次。 */
    @Transactional
    public LedgerView reverseSpend(ReturnSpend command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId());
        MemberRules.requireUlid(command.returnId(),"退货"); MemberRules.requireUlid(command.originalSpendLedgerId(),"原消费流水");
        BigDecimal amount=PointsRules.positive(command.amount(),"退还积分");
        if(command.restoredExpiresAt()==null || !command.restoredExpiresAt().isAfter(command.occurredAt()))
            throw new ServiceException("MEM-POINTS-013: 恢复积分到期时间无效",400);
        String hash=requestHash(Map.of("memberId",command.memberId(),"returnId",command.returnId(),
            "originalSpendLedgerId",command.originalSpendLedgerId(),"storeId",command.storeId(),"amount",amount.toPlainString(),
            "policyVersion",command.policyVersion(),"restoredExpiresAt",command.restoredExpiresAt().toString()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        LedgerView original=requireLedger(principal.tenantId(),command.originalSpendLedgerId(),command.memberId(),"SPEND");
        requireOriginalPolicy(original.policyVersion(),command.policyVersion(),"退还积分");
        requireCumulativeLimit(principal.tenantId(),original,amount,"RETURN_SPEND_REVERSAL");
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        BigDecimal remaining=amount;
        for(SpendAllocationRow allocation:points.listSpendAllocations(principal.tenantId(),original.ledgerId())) {
            if(remaining.signum()==0) break;
            BigDecimal open=allocation.spentPoints().subtract(allocation.restoredPoints());
            BigDecimal restored=open.min(remaining); if(restored.signum()==0) continue;
            points.insertAllocation(new AllocationWrite(principal.tenantId(),ids.next(),command.ledgerId(),
                allocation.lotId(),original.ledgerId(),restored,"RETURN_SPEND_REVERSAL",utc(command.occurredAt())));
            remaining=remaining.subtract(restored);
        }
        if(remaining.signum()!=0) throw new ServiceException("MEM-POINTS-014: 原消费分配可恢复积分不足",409);
        Delta delta=PointsRules.reverseSpend(balance(account),amount);
        points.insertLot(new LotWrite(principal.tenantId(),command.ledgerId(),command.memberId(),command.ledgerId(),
            amount,delta.available(),PointsRules.ZERO,original.policyVersion(),utc(command.restoredExpiresAt()),
            utc(command.occurredAt())));
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),"RETURN_SPEND_REVERSAL",
            amount,delta,"RETURN",command.returnId(),original.ledgerId(),original.policyVersion(),command.occurredAt(),
            command.restoredExpiresAt(),command.correlationId(),hash,account,scope,"RETURN_SPEND",null,null);
    }

    /** 到期任务必须指定并锁定批次，重复任务由命令幂等收敛。 */
    @Transactional
    public LedgerView expire(ExpireLot command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId()); MemberRules.requireUlid(command.lotId(),"积分批次");
        String hash=requestHash(Map.of("memberId",command.memberId(),"lotId",command.lotId(),
            "storeId",command.storeId(),"policyVersion",command.policyVersion(),"occurredAt",command.occurredAt().toString()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        LotRow lot=requireLot(principal.tenantId(),command.lotId());
        requireOriginalPolicy(lot.policyVersion(),command.policyVersion(),"积分到期");
        if(lot.expiresAt()==null || lot.expiresAt().isAfter(utc(command.occurredAt())) || lot.availablePoints().signum()==0)
            throw new ServiceException("MEM-POINTS-015: 批次尚未到期或无可到期积分",409);
        BigDecimal amount=lot.availablePoints(); updateLot(principal.tenantId(),lot,PointsRules.ZERO,lot.frozenPoints());
        points.insertAllocation(new AllocationWrite(principal.tenantId(),ids.next(),command.ledgerId(),lot.lotId(),
            null,amount,"EXPIRE",utc(command.occurredAt())));
        return post(principal,command.commandId(),command.ledgerId(),command.memberId(),"EXPIRE",amount,
            PointsRules.expire(amount),"SYSTEM_EXPIRY",lot.lotId(),null,lot.policyVersion(),command.occurredAt(),
            null,command.correlationId(),hash,account,scope,"SYSTEM_EXPIRY",null,null);
    }

    /** 人工调整需要租户管理员，负向调整按 FEFO 且不足显式形成债务。 */
    @Transactional
    public LedgerView adjust(ManualAdjust command) {
        requireCommon(command.commandId(),command.ledgerId(),command.memberId(),command.policyVersion(),
            command.occurredAt(),command.correlationId()); authorization.requireTenantAdministrator();
        String reason=MemberRules.requireReason(command.reason());
        requireApproval(command.approvalUserId(),command.approvalRef());
        BigDecimal amount=PointsRules.exact(command.signedAmount(),"人工调整积分");
        String hash=requestHash(Map.of("memberId",command.memberId(),"signedAmount",amount.toPlainString(),
            "storeId",command.storeId(),"policyVersion",command.policyVersion(),"reason",reason,
            "approvalUserId",command.approvalUserId(),"approvalRef",command.approvalRef()));
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.occurredAt());
        if(Objects.equals(principal.userId(),command.approvalUserId()))
            throw new ServiceException("MEM-POINTS-023: 人工调整操作人与审批人必须分离",409);
        LedgerView replay=replay(principal.tenantId(),command.commandId(),hash); if(replay!=null) return replay;
        requireActive(principal.tenantId(),command.memberId()); AccountView account=lockOrCreate(principal.tenantId(),command.memberId());
        Delta delta=PointsRules.manual(balance(account),amount);
        if(delta.available().signum()<0) consumeAvailableLots(principal.tenantId(),command.memberId(),command.ledgerId(),
            delta.available().abs(),"MANUAL_ADJUST",utc(command.occurredAt()));
        if(delta.available().signum()>0) points.insertLot(new LotWrite(principal.tenantId(),command.ledgerId(),
            command.memberId(),command.ledgerId(),amount.max(BigDecimal.ZERO).setScale(PointsRules.SCALE),
            delta.available(),PointsRules.ZERO,command.policyVersion(),null,utc(command.occurredAt())));
        LedgerView result=post(principal,command.commandId(),command.ledgerId(),command.memberId(),"MANUAL_ADJUST",
            amount.abs(),delta,"MANUAL",command.ledgerId(),null,command.policyVersion(),command.occurredAt(),null,
            command.correlationId(),hash,account,scope,"MANUAL_ADJUST",command.approvalUserId(),command.approvalRef());
        audit.append("MEMBER_POINTS_MANUAL_ADJUSTED","MEMBER",command.memberId(),null,
            Map.of("ledgerId",command.ledgerId(),"contentSha256",result.contentSha256()),
            Map.of("reasonSha256",requestHash(Map.of("reason",reason))));
        return result;
    }

    /** 追加等级事实；不覆盖既有等级历史。 */
    @Transactional
    public LevelView changeLevel(ChangeLevel command) {
        requireCommon(command.commandId(),command.historyId(),command.memberId(),command.policyVersion(),
            command.effectiveAt(),command.correlationId()); authorization.requireTenantAdministrator();
        if(command.levelCode()==null || !command.levelCode().matches("^[A-Z][A-Z0-9_-]{0,31}$")
            || command.reasonCode()==null || !command.reasonCode().matches("^[A-Z][A-Z0-9_-]{0,31}$"))
            throw new ServiceException("MEM-LEVEL-001: 等级或原因编码无效",400);
        requireApproval(command.approvalUserId(),command.approvalRef());
        TrustedPrincipal principal=tenantContext.requirePrincipal();
        OperationScope scope=scope(command.storeId(),command.effectiveAt());
        if(Objects.equals(principal.userId(),command.approvalUserId()))
            throw new ServiceException("MEM-LEVEL-003: 等级变更操作人与审批人必须分离",409);
        requireActive(principal.tenantId(),command.memberId());
        String hash=requestHash(Map.of("memberId",command.memberId(),"historyId",command.historyId(),
            "storeId",command.storeId(),"levelCode",command.levelCode(),"policyVersion",command.policyVersion(),
            "reasonCode",command.reasonCode(),"approvalUserId",command.approvalUserId(),"approvalRef",command.approvalRef(),
            "effectiveAt",command.effectiveAt().toString()));
        MemberPersistencePort.StoredCommand stored=members.findCommand(principal.tenantId(),"CHANGE_LEVEL",command.commandId());
        if(stored!=null) {
            if(!stored.requestSha256().equals(hash)) throw idempotency();
            return requireLevel(principal.tenantId(),command.memberId());
        }
        points.insertLevel(new LevelWrite(principal.tenantId(),command.historyId(),command.memberId(),command.levelCode(),
            command.policyVersion(),command.reasonCode(),scope.storeId(),scope.businessDate(),principal.userId(),
            command.approvalUserId(),command.approvalRef(),command.correlationId(),utc(command.effectiveAt())));
        CanonicalJson.Result result=CanonicalJson.from(Map.of("historyId",command.historyId(),"levelCode",command.levelCode()));
        members.insertCommand(new MemberPersistencePort.CommandWrite(principal.tenantId(),ids.next(),"CHANGE_LEVEL",
            command.commandId(),hash,"MEMBER_LEVEL",command.historyId(),result.sha256(),result.json()));
        audit.append("MEMBER_LEVEL_CHANGED","MEMBER",command.memberId(),null,
            Map.of("historyId",command.historyId(),"levelCode",command.levelCode()),
            Map.of("reasonCode",command.reasonCode(),"approvalRef",command.approvalRef(),
                "businessDate",scope.businessDate().toString()));
        appendOutbox(principal,"member.level.changed.v1",command.memberId(),0,command.correlationId(),
            Map.of("historyId",command.historyId(),"levelCode",command.levelCode(),"policyVersion",command.policyVersion()));
        return requireLevel(principal.tenantId(),command.memberId());
    }

    @Transactional(readOnly=true)
    public AccountView account(String memberId,Long storeId) {
        MemberRules.requireUlid(memberId,"会员"); requireStoreScope(storeId); String tenant=tenantContext.requireTenantId();
        AccountView value=points.findAccount(tenant,memberId);
        if(value==null) return new AccountView(memberId,PointsRules.ZERO,PointsRules.ZERO,PointsRules.ZERO,0,null);
        return value;
    }

    /** 从不可变流水全量重建账户并以乐观锁替换损坏投影。 */
    @Transactional
    public AccountView rebuild(String memberId,Long storeId) {
        MemberRules.requireUlid(memberId,"会员"); authorization.requireTenantAdministrator(); requireStoreScope(storeId);
        TrustedPrincipal principal=tenantContext.requirePrincipal(); AccountView current=lockOrCreate(principal.tenantId(),memberId);
        BigDecimal available=PointsRules.ZERO,frozen=PointsRules.ZERO,debt=PointsRules.ZERO; String last=null;
        List<LedgerView> ledgers=points.listLedgers(principal.tenantId(),memberId);
        for(LedgerView ledger:ledgers) {
            available=available.add(ledger.availableDelta()); frozen=frozen.add(ledger.frozenDelta());
            debt=debt.add(ledger.debtDelta()); last=ledger.ledgerId();
        }
        Balance rebuilt=PointsRules.apply(new Balance(PointsRules.ZERO,PointsRules.ZERO,PointsRules.ZERO,-1),
            new Delta(available,frozen,debt));
        AccountWrite write=new AccountWrite(principal.tenantId(),memberId,rebuilt.available(),rebuilt.frozen(),
            rebuilt.debt(),current.version(),last);
        if(points.replaceAccountProjection(write)!=1) throw conflict();
        audit.append("MEMBER_POINTS_REBUILT","MEMBER",memberId,null,Map.of("lastLedgerId",last==null?"NONE":last),
            Map.of("ledgerCount",ledgers.size(),"storeId",storeId));
        return requireAccount(principal.tenantId(),memberId);
    }

    private LedgerView post(TrustedPrincipal principal,String commandId,String ledgerId,String memberId,String type,
                            BigDecimal amount,Delta delta,String sourceType,String sourceId,String originalLedgerId,
                            String policyVersion,OffsetDateTime occurredAt,OffsetDateTime expiresAt,
                            String correlationId,String requestHash,AccountView current,OperationScope scope,
                            String reasonCode,Long approvalUserId,String approvalRef) {
        Balance next=PointsRules.apply(balance(current),delta);
        Map<String,Object> fact=new LinkedHashMap<>(); fact.put("ledgerId",ledgerId); fact.put("memberId",memberId);
        fact.put("eventType",type); fact.put("amount",amount.toPlainString());
        fact.put("availableDelta",delta.available().toPlainString()); fact.put("frozenDelta",delta.frozen().toPlainString());
        fact.put("debtDelta",delta.debt().toPlainString()); fact.put("sourceType",sourceType); fact.put("sourceId",sourceId);
        if(originalLedgerId!=null) fact.put("originalLedgerId",originalLedgerId); fact.put("policyVersion",policyVersion);
        fact.put("storeId",scope.storeId()); fact.put("businessDate",scope.businessDate().toString());
        fact.put("reasonCode",reasonCode); fact.put("actorUserId",principal.userId());
        if(approvalUserId!=null) fact.put("approvalUserId",approvalUserId);
        if(approvalRef!=null) fact.put("approvalRef",approvalRef);
        fact.put("occurredAt",occurredAt.toString()); if(expiresAt!=null) fact.put("expiresAt",expiresAt.toString());
        CanonicalJson.Result canonical=CanonicalJson.from(fact);
        points.insertLedger(new LedgerWrite(principal.tenantId(),ledgerId,memberId,type,amount,delta.available(),
            delta.frozen(),delta.debt(),sourceType,sourceId,originalLedgerId,policyVersion,commandId,requestHash,
            correlationId,scope.storeId(),scope.businessDate(),reasonCode,principal.userId(),approvalUserId,approvalRef,
            utc(occurredAt),utc(expiresAt),canonical.sha256()));
        AccountWrite account=new AccountWrite(principal.tenantId(),memberId,next.available(),next.frozen(),next.debt(),
            current.version(),ledgerId);
        if(points.updateAccount(account)!=1) throw conflict();
        appendOutbox(principal,"member.points.posted.v1",memberId,next.version(),correlationId,
            Map.of("ledgerId",ledgerId,"eventType",type,"contentSha256",canonical.sha256()));
        return requireLedger(principal.tenantId(),ledgerId,memberId,type);
    }

    private void appendOutbox(TrustedPrincipal principal,String eventType,String memberId,int version,
                              String correlationId,Map<String,Object> fact) {
        String eventId=ids.next(); LocalDateTime now=LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        Map<String,Object> payload=new LinkedHashMap<>(); payload.put("eventId",eventId);
        payload.put("tenantContext",Map.of("tenantId",principal.tenantId())); payload.put("schemaVersion","1.0");
        payload.put("occurredAt",now.toString()+"Z"); payload.put("correlationId",correlationId);
        payload.put("memberId",memberId); payload.putAll(fact); CanonicalJson.Result canonical=CanonicalJson.from(payload);
        members.insertOutbox(new MemberPersistencePort.OutboxWrite(principal.tenantId(),eventId,eventType,memberId,
            version,canonical.json(),canonical.sha256(),now));
    }

    private void consumeAvailableLots(String tenant,String memberId,String ledgerId,BigDecimal amount,
                                      String type,LocalDateTime at) {
        BigDecimal remaining=amount;
        for(LotRow lot:points.listFefoAvailableLots(tenant,memberId,at)) {
            if(remaining.signum()==0) break; BigDecimal used=lot.availablePoints().min(remaining);
            if(used.signum()==0) continue; updateLot(tenant,lot,lot.availablePoints().subtract(used),lot.frozenPoints());
            points.insertAllocation(new AllocationWrite(tenant,ids.next(),ledgerId,lot.lotId(),null,used,type,at));
            remaining=remaining.subtract(used);
        }
        if(remaining.signum()!=0) throw new ServiceException("MEM-POINTS-009: 批次投影与账户不一致",409);
    }

    private AccountView lockOrCreate(String tenant,String memberId) {
        AccountView account=points.lockAccount(tenant,memberId);
        if(account!=null) return account;
        points.insertAccount(new AccountWrite(tenant,memberId,PointsRules.ZERO,PointsRules.ZERO,PointsRules.ZERO,0,null));
        return requireAccount(tenant,memberId);
    }
    private AccountView requireAccount(String tenant,String memberId) {
        AccountView value=points.findAccount(tenant,memberId);
        if(value==null) throw new ServiceException("MEM-POINTS-016: 积分账户不存在",404); return value;
    }
    private Balance balance(AccountView value) {
        return new Balance(value.availablePoints(),value.frozenPoints(),value.debtPoints(),value.version());
    }
    private LedgerView replay(String tenant,String commandId,String hash) {
        LedgerView value=points.findLedgerByCommand(tenant,commandId);
        if(value!=null && !value.requestSha256().equals(hash)) throw idempotency(); return value;
    }
    private LedgerView requireLedger(String tenant,String ledgerId,String memberId,String type) {
        LedgerView value=points.findLedger(tenant,ledgerId);
        if(value==null || !value.memberId().equals(memberId) || !value.eventType().equals(type))
            throw new ServiceException("MEM-POINTS-017: 原积分流水不存在或类型不符",404); return value;
    }
    private LotRow requireLot(String tenant,String lotId) {
        LotRow value=points.lockLot(tenant,lotId);
        if(value==null) throw new ServiceException("MEM-POINTS-018: 积分批次不存在",404); return value;
    }
    private void updateLot(String tenant,LotRow lot,BigDecimal available,BigDecimal frozen) {
        if(points.updateLot(new LotUpdate(tenant,lot.lotId(),available,frozen,lot.version()))!=1) throw conflict();
    }
    private void requireCumulativeLimit(String tenant,LedgerView original,BigDecimal amount,String type) {
        BigDecimal reversed=Optional.ofNullable(points.sumReversedAmount(tenant,original.ledgerId(),type))
            .orElse(PointsRules.ZERO);
        if(reversed.add(amount).compareTo(original.amount())>0)
            throw new ServiceException("MEM-POINTS-019: 退货累计积分超过原流水",409);
    }
    private void requireOriginalPolicy(String originalPolicy,String commandPolicy,String action) {
        if(!Objects.equals(originalPolicy,commandPolicy))
            throw new ServiceException("MEM-POINTS-011: "+action+"必须使用原策略版本",409);
    }
    private OperationScope scope(Long storeId,OffsetDateTime occurredAt) {
        if(storeId==null || storeId<=0) throw new ServiceException("MEM-SCOPE-001: 门店标识无效",400);
        BusinessDateView view=stores.businessDate(storeId,occurredAt.toInstant());
        return new OperationScope(view.storeId(),view.businessDate());
    }
    private void requireStoreScope(Long storeId) {
        if(storeId==null || storeId<=0) throw new ServiceException("MEM-SCOPE-001: 门店标识无效",400);
        authorization.requireStoreAccess(storeId);
    }
    private void requireApproval(Long approvalUserId,String approvalRef) {
        if(approvalUserId==null || approvalUserId<=0) throw new ServiceException("MEM-APPROVAL-001: 审批人无效",400);
        MemberRules.requireUlid(approvalRef,"审批引用");
    }
    private LevelView requireLevel(String tenant,String memberId) {
        LevelView value=points.findCurrentLevel(tenant,memberId);
        if(value==null) throw new ServiceException("MEM-LEVEL-002: 等级事实不存在",404); return value;
    }
    private void requireActive(String tenant,String memberId) {
        var member=members.findMember(tenant,memberId);
        if(member==null || !"ACTIVE".equals(member.state()))
            throw new ServiceException("MEM-PROFILE-002: 会员状态不允许该操作",409);
    }
    private void requireCommon(String commandId,String aggregateId,String memberId,String policyVersion,
                               OffsetDateTime occurredAt,String correlationId) {
        MemberRules.requireUlid(commandId,"命令"); MemberRules.requireUlid(aggregateId,"事实");
        MemberRules.requireUlid(memberId,"会员"); MemberRules.requireUlid(correlationId,"关联标识");
        if(policyVersion==null || !policyVersion.matches("^[A-Za-z0-9._-]{1,64}$") || occurredAt==null)
            throw new ServiceException("MEM-POINTS-020: 策略版本或发生时间无效",400);
    }
    private String requestHash(Map<String,Object> map) { return CanonicalJson.from(map).sha256(); }
    private String optionalTime(OffsetDateTime value) { return value==null?"NONE":value.toString(); }
    private LocalDateTime utc(OffsetDateTime value) {
        return value==null?null:LocalDateTime.ofInstant(value.toInstant(),ZoneOffset.UTC);
    }
    private ServiceException insufficient() { return new ServiceException("MEM-POINTS-008: 可用或冻结积分不足",409); }
    private ServiceException conflict() { return new ServiceException("MEM-CONCURRENCY-001: 状态已被并发修改",409); }
    private ServiceException idempotency() { return new ServiceException("MEM-IDEMP-001: 同幂等键对应不同内容",409); }
    private record OperationScope(Long storeId,LocalDate businessDate) { }
}
