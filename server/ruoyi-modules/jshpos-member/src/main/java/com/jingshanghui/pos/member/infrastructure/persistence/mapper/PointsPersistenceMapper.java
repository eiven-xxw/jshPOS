package com.jingshanghui.pos.member.infrastructure.persistence.mapper;

import com.jingshanghui.pos.member.application.model.PointsViews.*;
import com.jingshanghui.pos.member.application.port.PointsPersistencePort.*;
import org.apache.ibatis.annotations.Param;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 积分流水、批次、分配和等级历史 Mapper；SQL 只能存在于配套 XML。 */
public interface PointsPersistenceMapper {
    AccountView lockAccount(@Param("tenantId") String tenantId,@Param("memberId") String memberId);
    AccountView findAccount(@Param("tenantId") String tenantId,@Param("memberId") String memberId);
    int insertAccount(AccountWrite value);
    int updateAccount(AccountWrite value);
    int replaceAccountProjection(AccountWrite value);
    int insertLedger(LedgerWrite value);
    LedgerView findLedger(@Param("tenantId") String tenantId,@Param("ledgerId") String ledgerId);
    LedgerView findLedgerByCommand(@Param("tenantId") String tenantId,@Param("idempotencyKey") String idempotencyKey);
    List<LedgerView> listLedgers(@Param("tenantId") String tenantId,@Param("memberId") String memberId);
    int insertLot(LotWrite value);
    LotRow lockLot(@Param("tenantId") String tenantId,@Param("lotId") String lotId);
    List<LotRow> listFefoAvailableLots(@Param("tenantId") String tenantId,@Param("memberId") String memberId,
                                       @Param("occurredAt") LocalDateTime occurredAt);
    int updateLot(LotUpdate value);
    int insertAllocation(AllocationWrite value);
    List<FrozenAllocationRow> listFrozenAllocations(@Param("tenantId") String tenantId,
                                                     @Param("freezeLedgerId") String freezeLedgerId);
    List<SpendAllocationRow> listSpendAllocations(@Param("tenantId") String tenantId,
                                                   @Param("spendLedgerId") String spendLedgerId);
    BigDecimal sumReversedAmount(@Param("tenantId") String tenantId,
                                 @Param("originalLedgerId") String originalLedgerId,
                                 @Param("reversalType") String reversalType);
    int insertLevel(LevelWrite value);
    LevelView findCurrentLevel(@Param("tenantId") String tenantId,@Param("memberId") String memberId);
}
