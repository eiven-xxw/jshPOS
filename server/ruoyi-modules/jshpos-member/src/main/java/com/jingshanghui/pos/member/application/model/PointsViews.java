package com.jingshanghui.pos.member.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 积分账户、流水和等级的无 PII 视图。 */
public final class PointsViews {
    public record AccountView(String memberId,BigDecimal availablePoints,BigDecimal frozenPoints,
                              BigDecimal debtPoints,int version,String lastLedgerId) { }
    public record LedgerView(String ledgerId,String memberId,String eventType,BigDecimal amount,
                             BigDecimal availableDelta,BigDecimal frozenDelta,BigDecimal debtDelta,
                             String sourceType,String sourceId,String originalLedgerId,String policyVersion,
                             Long storeId,LocalDate businessDate,String reasonCode,Long actorUserId,
                             Long approvalUserId,String approvalRef,LocalDateTime occurredAt,LocalDateTime expiresAt,String requestSha256,
                             String contentSha256) { }
    public record LevelView(String historyId,String memberId,String levelCode,String policyVersion,
                            String reasonCode,Long storeId,LocalDate businessDate,Long actorUserId,
                            Long approvalUserId,String approvalRef,LocalDateTime effectiveAt) { }
    private PointsViews() { }
}
