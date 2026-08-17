package com.jingshanghui.pos.member.application.port;

import com.jingshanghui.pos.member.application.model.PointsViews.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 等级与积分只追加事实及可重建投影端口。 */
public interface PointsPersistencePort {
    record AccountWrite(String tenantId,String memberId,BigDecimal availablePoints,BigDecimal frozenPoints,
                        BigDecimal debtPoints,int expectedVersion,String lastLedgerId) { }
    record LedgerWrite(String tenantId,String ledgerId,String memberId,String eventType,BigDecimal amount,
                       BigDecimal availableDelta,BigDecimal frozenDelta,BigDecimal debtDelta,String sourceType,
                       String sourceId,String originalLedgerId,String policyVersion,String idempotencyKey,
                       String requestSha256,String correlationId,Long storeId,LocalDate businessDate,String reasonCode,
                       Long actorUserId,Long approvalUserId,String approvalRef,LocalDateTime occurredAt,
                       LocalDateTime expiresAt,String contentSha256) { }
    record LotWrite(String tenantId,String lotId,String memberId,String earnLedgerId,BigDecimal originalPoints,
                    BigDecimal availablePoints,BigDecimal frozenPoints,String policyVersion,
                    LocalDateTime expiresAt,LocalDateTime occurredAt) { }
    record LotRow(String lotId,String earnLedgerId,BigDecimal originalPoints,BigDecimal availablePoints,
                  BigDecimal frozenPoints,String policyVersion,LocalDateTime expiresAt,int version) { }
    record LotUpdate(String tenantId,String lotId,BigDecimal availablePoints,BigDecimal frozenPoints,
                     int expectedVersion) { }
    record AllocationWrite(String tenantId,String allocationId,String ledgerId,String lotId,
                           String parentLedgerId,BigDecimal points,String allocationType,
                           LocalDateTime occurredAt) { }
    record FrozenAllocationRow(String lotId,BigDecimal frozenPoints,BigDecimal releasedPoints,
                               LocalDateTime expiresAt) { }
    record SpendAllocationRow(String lotId,BigDecimal spentPoints,BigDecimal restoredPoints,
                              LocalDateTime sourceExpiresAt) { }
    record LevelWrite(String tenantId,String historyId,String memberId,String levelCode,String policyVersion,
                      String reasonCode,Long storeId,LocalDate businessDate,Long actorUserId,Long approvalUserId,
                      String approvalRef,String correlationId,LocalDateTime effectiveAt) { }

    AccountView lockAccount(String tenantId,String memberId);
    AccountView findAccount(String tenantId,String memberId);
    int insertAccount(AccountWrite value);
    int updateAccount(AccountWrite value);
    int replaceAccountProjection(AccountWrite value);
    int insertLedger(LedgerWrite value);
    LedgerView findLedger(String tenantId,String ledgerId);
    LedgerView findLedgerByCommand(String tenantId,String idempotencyKey);
    List<LedgerView> listLedgers(String tenantId,String memberId);
    int insertLot(LotWrite value);
    LotRow lockLot(String tenantId,String lotId);
    List<LotRow> listFefoAvailableLots(String tenantId,String memberId,LocalDateTime occurredAt);
    int updateLot(LotUpdate value);
    int insertAllocation(AllocationWrite value);
    List<FrozenAllocationRow> listFrozenAllocations(String tenantId,String freezeLedgerId);
    List<SpendAllocationRow> listSpendAllocations(String tenantId,String spendLedgerId);
    BigDecimal sumReversedAmount(String tenantId,String originalLedgerId,String reversalType);
    int insertLevel(LevelWrite value);
    LevelView findCurrentLevel(String tenantId,String memberId);
}
