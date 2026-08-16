package com.jingshanghui.pos.payment.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Gate 3A REST DTO；不包含 tenant_id、Provider 密钥或敏感支付报文。 */
public final class PaymentRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private PaymentRequests() {
    }

    public record CreateIntent(@Pattern(regexp = ULID) String paymentId,
                               @Pattern(regexp = ULID) String orderId,
                               @Pattern(regexp = "^[1-9][0-9]{0,18}$") String storeId,
                               @Pattern(regexp = ULID) String terminalId,
                               @Min(1) long amountMinor,
                               @Pattern(regexp = "^[A-Z]{3}$") String currency,
                               @NotNull Instant occurredAt) {
    }

    public record CreateAttempt(@Pattern(regexp = ULID) String attemptId,
                                @Pattern(regexp = "^[A-Z0-9_-]{2,32}$") String providerCode,
                                @NotBlank @Size(max = 96) String providerRequestNo,
                                @NotNull Instant occurredAt) {
    }

    public record CreateRefund(@Pattern(regexp = ULID) String refundId,
                               @Pattern(regexp = ULID) String paymentId,
                               @Pattern(regexp = ULID) String orderId,
                               @Min(1) long amountMinor,
                               @Pattern(regexp = "^[A-Z]{3}$") String currency,
                               @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                               @NotEmpty @Size(max = 200) List<@Valid RefundLine> lines,
                               @NotNull Instant occurredAt) {
        public CreateRefund {
            lines = List.copyOf(lines);
        }
    }

    public record RefundLine(@Pattern(regexp = ULID) String orderLineId,
                             @Pattern(regexp = "^(0|[1-9][0-9]{0,12})(\\.[0-9]{1,6})?$") String quantity,
                             @Min(0) long amountMinor) {
    }

    public record ApproveRefund(@Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                                @NotNull Instant occurredAt) {
    }

    public record RunReconciliation(@Pattern(regexp = ULID) String runId,
                                    @Pattern(regexp = "^[A-Z0-9_-]{2,32}$") String providerCode,
                                    @NotNull LocalDate statementDate,
                                    @NotNull @Size(max = 10000) List<@Valid StatementEntry> entries,
                                    @NotNull Instant occurredAt) {
        public RunReconciliation {
            entries = List.copyOf(entries);
        }
    }

    public record StatementEntry(@Pattern(regexp = ULID) String entryId,
                                 @NotBlank @Size(max = 96) String providerTransactionNo,
                                 @Pattern(regexp = "^(PAYMENT|REFUND)$") String businessType,
                                 @Pattern(regexp = "^(SUCCEEDED|FAILED|UNKNOWN)$") String status,
                                 @Min(0) long amountMinor,
                                 @Pattern(regexp = "^[A-Z]{3}$") String currency,
                                 @NotNull Instant occurredAt,
                                 @Pattern(regexp = "^[a-f0-9]{64}$") String payloadHash) {
    }

    public record TransitionCase(@Pattern(regexp = "^(INVESTIGATING|WAITING_PROVIDER|RESOLVED|APPROVED|CLOSED)$") String targetStatus,
                                 @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                                 @NotBlank @Size(max = 512) String reasonText,
                                 @NotNull Instant occurredAt) {
    }
}
