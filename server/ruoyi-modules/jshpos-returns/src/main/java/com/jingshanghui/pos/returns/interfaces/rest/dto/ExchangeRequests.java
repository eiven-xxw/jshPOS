package com.jingshanghui.pos.returns.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

/** 换货 REST 输入；租户、操作者和审批人不得由客户端提供。 */
public final class ExchangeRequests {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String HASH = "^[a-f0-9]{64}$";
    private ExchangeRequests() { }

    /** 冻结已存在的原退货命令与预分配的新销售命令。 */
    public record Create(@Pattern(regexp = ULID) String commandId,
                         @NotBlank @Size(max = 96) String idempotencyKey,
                         @Pattern(regexp = ULID) String exchangeId,
                         @Pattern(regexp = ULID) String returnId,
                         @Pattern(regexp = ULID) String originalOrderId,
                         @Pattern(regexp = ULID) String originalReturnCommandId,
                         @Pattern(regexp = ULID) String newOrderId,
                         @Pattern(regexp = ULID) String newSaleCommandId,
                         @Positive Long storeId,
                         @Pattern(regexp = ULID) String terminalId,
                         @NotNull LocalDate businessDate,
                         @Positive long expectedRefundAmountMinor,
                         @Positive long expectedSaleReceivableMinor,
                         @Pattern(regexp = HASH) String quoteFingerprint,
                         @Pattern(regexp = HASH) String newSalePlanSha256,
                         @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                         @Pattern(regexp = ULID) String correlationId,
                         @NotNull Instant occurredAt) { }

    /** 职责分离审批。 */
    public record Approve(@Pattern(regexp = ULID) String commandId,
                          @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                          @Pattern(regexp = ULID) String correlationId,
                          @NotNull Instant occurredAt) { }

    /** 人工恢复只能选已有 RETURN/SALE 腿。 */
    public record Recover(@Pattern(regexp = ULID) String commandId,
                          @Pattern(regexp = "^(RETURN|SALE)$") String targetLeg,
                          @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                          @Pattern(regexp = ULID) String correlationId,
                          @NotNull Instant occurredAt) { }
}
