package com.jingshanghui.pos.procurement.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 补货 REST 输入 DTO；tenantId 不属于任何客户端请求字段。 */
public final class ReplenishmentRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private ReplenishmentRequests() {
    }

    public record PolicyCreate(@NotBlank @Pattern(regexp = ULID) String policyVersionId,
                               @NotBlank String storeId,
                               @NotBlank @Pattern(regexp = ULID) String warehouseId,
                               int versionNo,
                               @NotNull Instant effectiveFrom,
                               @NotEmpty @Size(max = 10000) List<@Valid PolicyItem> items,
                               @NotBlank @Size(max = 96) String idempotencyKey,
                               @NotBlank @Size(max = 96) String correlationId) {
    }

    public record PolicyItem(@NotBlank @Pattern(regexp = ULID) String policyItemId,
                             @NotBlank String skuId,
                             @NotBlank String purchaseUnitId,
                             @NotBlank @Pattern(regexp = ULID) String supplierId,
                             @NotNull BigDecimal minimumBaseQuantity,
                             @NotNull BigDecimal maximumBaseQuantity,
                             @NotNull BigDecimal minimumOrderQuantity,
                             @NotNull BigDecimal orderMultiple,
                             boolean includeConfirmedInTransit,
                             @NotBlank String unitPriceMinor,
                             int taxRateBps) {
    }

    public record StateCommand(long expectedVersion,
                               @NotBlank @Size(max = 96) String idempotencyKey,
                               @NotBlank @Size(max = 256) String reason,
                               @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Generate(@NotBlank @Pattern(regexp = ULID) String generationRunId,
                           @NotBlank @Pattern(regexp = ULID) String policyVersionId,
                           @NotNull Instant calculationAt,
                           @NotBlank @Size(max = 96) String idempotencyKey,
                           @NotBlank @Size(max = 96) String correlationId) {
    }

    public record DraftCreate(long expectedVersion,
                              @NotBlank @Pattern(regexp = ULID) String purchaseOrderId,
                              @NotNull LocalDate expectedDate,
                              @NotBlank @Size(max = 96) String idempotencyKey,
                              @NotBlank @Size(max = 96) String correlationId) {
    }
}
