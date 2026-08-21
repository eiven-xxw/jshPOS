package com.jingshanghui.pos.order.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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

public final class OrderRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String PLATFORM_ID = "^[1-9][0-9]{0,18}$";
    private static final String QUANTITY = "^(0|[1-9][0-9]{0,12})(\\.[0-9]{1,6})?$";

    private OrderRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record OpenShift(
        @NotBlank @Pattern(regexp = PLATFORM_ID) String storeId,
        @NotBlank @Pattern(regexp = ULID) String terminalId,
        @NotBlank @Size(max = 64) String cashierId,
        @NotNull LocalDate businessDate,
        @NotBlank @Size(max = 64) String storeTimezone,
        @Min(0) @Max(9_007_199_254_740_991L) long openingCashMinor,
        @Min(1) long configVersion,
        @NotNull Instant occurredAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ApproveDifference(@Min(0) @Max(9_007_199_254_740_991L) long actualCashMinor,
                                    @Min(1) long expectedVersion,
                                    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String reasonCode,
                                    @NotBlank @Size(max = 256) String reasonText,
                                    @NotNull Instant occurredAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CloseShift(
        @Min(0) @Max(9_007_199_254_740_991L) long actualCashMinor,
        @Min(1) long expectedVersion,
        @Pattern(regexp = ULID) String approvalId,
        @NotNull Instant occurredAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CashMovement(
        @NotBlank @Pattern(regexp = ULID) String movementId,
        @NotBlank @Pattern(regexp = "^(CASH_IN|CASH_OUT|SAFE_DROP)$") String movementType,
        @Min(1) @Max(9_007_199_254_740_991L) long amountMinor,
        @Min(1) long expectedVersion,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String reasonCode,
        @NotBlank @Size(max = 256) String reasonText,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{16,128}$") String authorizationRef,
        @NotNull Instant occurredAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DrawerEvent(
        @NotBlank @Pattern(regexp = ULID) String drawerEventId,
        @Min(1) long expectedVersion,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String reasonCode,
        @NotBlank @Size(max = 256) String reasonText,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{16,128}$") String authorizationRef,
        @NotNull Instant occurredAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CashOrder(
        @NotBlank @Pattern(regexp = ULID) String orderId,
        @NotBlank @Size(max = 40) String localOrderNo,
        @NotBlank @Pattern(regexp = PLATFORM_ID) String storeId,
        @NotBlank @Pattern(regexp = ULID) String terminalId,
        @NotBlank @Pattern(regexp = ULID) String shiftId,
        @NotBlank @Size(max = 64) String cashierId,
        @NotNull LocalDate businessDate,
        @NotBlank @Size(max = 64) String storeTimezone,
        @Min(1) long catalogVersion,
        @Min(1) long priceVersion,
        @NotBlank @Pattern(regexp = "^[A-Z0-9._-]{1,32}$") String industryTemplateVersion,
        @Min(0) @Max(9_007_199_254_740_991L) long grossAmountMinor,
        @Min(0) @Max(9_007_199_254_740_991L) long receivableAmountMinor,
        @Min(0) @Max(9_007_199_254_740_991L) long tenderedAmountMinor,
        @NotEmpty @Size(max = 500) List<@Valid CashOrderLine> lines,
        @NotNull Instant occurredAt
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CashOrderLine(
        @NotBlank @Pattern(regexp = ULID) String lineId,
        @Min(1) @Max(500) int lineNo,
        @NotBlank @Pattern(regexp = PLATFORM_ID) String skuId,
        @NotBlank @Size(max = 64) String skuCode,
        @Size(max = 64) String barcode,
        @NotBlank @Size(max = 200) String productName,
        @NotBlank @Pattern(regexp = PLATFORM_ID) String unitId,
        @NotBlank @Pattern(regexp = "^[A-Z0-9_-]{1,32}$") String unitCode,
        @NotBlank @Pattern(regexp = QUANTITY) String quantity,
        @Min(0) @Max(9_007_199_254_740_991L) long unitPriceMinor,
        @Min(0) @Max(9_007_199_254_740_991L) long grossAmountMinor,
        @Min(0) @Max(9_007_199_254_740_991L) long payableAmountMinor,
        @NotBlank @Pattern(regexp = "^(TENANT_BASE|STORE_OVERRIDE)$") String priceSource
    ) {
    }
}
