package com.jingshanghui.pos.member.application.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 积分与等级命令；tenant_id 只来自可信上下文。 */
public final class PointsCommands {
    public record Earn(String commandId,String ledgerId,String memberId,String sourceOrderId,Long storeId,BigDecimal amount,
                       String policyVersion,OffsetDateTime expiresAt,OffsetDateTime occurredAt,String correlationId) { }
    public record Freeze(String commandId,String ledgerId,String memberId,Long storeId,BigDecimal amount,String policyVersion,
                         OffsetDateTime occurredAt,String correlationId) { }
    public record FrozenSettlement(String commandId,String ledgerId,String memberId,String freezeLedgerId,
                                   Long storeId,BigDecimal amount,String action,String policyVersion,OffsetDateTime occurredAt,
                                   String correlationId) { }
    public record ReturnEarn(String commandId,String ledgerId,String memberId,String returnId,
                             String originalEarnLedgerId,Long storeId,BigDecimal amount,String policyVersion,
                             OffsetDateTime occurredAt,String correlationId) { }
    public record ReturnSpend(String commandId,String ledgerId,String memberId,String returnId,
                              String originalSpendLedgerId,Long storeId,BigDecimal amount,String policyVersion,
                              OffsetDateTime restoredExpiresAt,OffsetDateTime occurredAt,String correlationId) { }
    public record ExpireLot(String commandId,String ledgerId,String memberId,String lotId,Long storeId,String policyVersion,
                            OffsetDateTime occurredAt,String correlationId) { }
    public record ManualAdjust(String commandId,String ledgerId,String memberId,Long storeId,BigDecimal signedAmount,
                               String policyVersion,String reason,Long approvalUserId,String approvalRef,
                               OffsetDateTime occurredAt,String correlationId) { }
    public record ChangeLevel(String commandId,String historyId,String memberId,Long storeId,String levelCode,
                              String policyVersion,String reasonCode,Long approvalUserId,String approvalRef,OffsetDateTime effectiveAt,
                              String correlationId) { }
    private PointsCommands() { }
}
