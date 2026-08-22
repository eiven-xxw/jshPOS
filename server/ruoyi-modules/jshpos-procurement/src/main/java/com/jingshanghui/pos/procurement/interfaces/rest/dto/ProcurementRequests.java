package com.jingshanghui.pos.procurement.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 采购 REST 输入；平台 BIGINT 使用十进制字符串避免前端精度损失。 */
public final class ProcurementRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String POSITIVE_ID = "^[1-9][0-9]{0,18}$";
    private static final String NON_NEGATIVE_LONG = "^(0|[1-9][0-9]{0,18})$";

    private ProcurementRequests() {
    }

    public record SupplierCreate(@Pattern(regexp = ULID) String supplierId,
                                 @NotBlank @Size(max = 64) String code,
                                 @NotBlank @Size(max = 160) String name,
                                 @NotBlank @Size(max = 96) String correlationId) {
    }

    public record SupplierState(@Pattern(regexp = "^(ACTIVE|SUSPENDED|BLOCKED)$") String state,
                                @NotBlank @Size(max = 256) String reason,
                                @NotBlank @Size(max = 96) String correlationId) {
    }

    public record OrderCreate(@Pattern(regexp = ULID) String orderId,
                              @Pattern(regexp = ULID) String supplierId,
                              @Pattern(regexp = POSITIVE_ID) String storeId,
                              @Pattern(regexp = ULID) String warehouseId,
                              @NotNull LocalDate expectedDate,
                              @Min(0) @Max(1000) int overReceiptToleranceBps,
                              @NotEmpty @Size(max = 500) List<@Valid OrderLine> lines,
                              @NotBlank @Size(max = 96) String correlationId) {
    }

    public record OrderLine(@Pattern(regexp = ULID) String orderLineId,
                            @Pattern(regexp = POSITIVE_ID) String skuId,
                            @Pattern(regexp = POSITIVE_ID) String unitId,
                            @NotNull @DecimalMin(value = "0", inclusive = false)
                            @Digits(integer = 13, fraction = 6) BigDecimal orderedQuantity,
                            @Pattern(regexp = NON_NEGATIVE_LONG) String unitPriceMinor,
                            @Min(0) @Max(10000) int taxRateBps) {
    }

    public record Correlated(@NotBlank @Size(max = 96) String correlationId) {
    }

    public record Reasoned(@NotBlank @Size(max = 256) String reason,
                           @NotBlank @Size(max = 96) String correlationId) {
    }

    public record ReceiptCreate(@Pattern(regexp = ULID) String receiptId,
                                @NotEmpty @Size(max = 500) List<@Valid ReceiptLine> lines,
                                @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Confirm(@Pattern(regexp = ULID) String eventId,
                          @NotBlank @Size(max = 96) String correlationId,
                          @Size(max = 1000) List<@Valid ReceiptLotSplit> lotSplits) {
        public Confirm(String eventId, String correlationId) {
            this(eventId, correlationId, List.of());
        }
    }

    public record ReceiptLotSplit(@Pattern(regexp = ULID) String receiptLineId,
                                  @NotNull @DecimalMin(value = "0", inclusive = false)
                                  @Digits(integer = 13, fraction = 6) BigDecimal baseQuantity,
                                  @Size(max = 96) String supplierLotCode,
                                  @Size(max = 96) String internalLotCode,
                                  LocalDate productionDate,
                                  @NotNull LocalDate receivedDate,
                                  LocalDate explicitExpiryDate) {
    }

    public record ReceiptLine(@Pattern(regexp = ULID) String receiptLineId,
                              @Pattern(regexp = ULID) String orderLineId,
                              @NotNull @DecimalMin(value = "0", inclusive = false)
                              @Digits(integer = 13, fraction = 6) BigDecimal receivedQuantity) {
    }

    public record ReturnCreate(@Pattern(regexp = ULID) String purchaseReturnId,
                               @NotEmpty @Size(max = 500) List<@Valid ReturnLine> lines,
                               @NotBlank @Size(max = 256) String reason,
                               @NotBlank @Size(max = 96) String correlationId) {
    }

    public record ReturnApprove(@Pattern(regexp = ULID) String eventId,
                                @NotBlank @Size(max = 96) String correlationId,
                                @Size(max = 1000) List<@Valid ReturnLotSplit> lotSplits) {
        public ReturnApprove(String eventId, String correlationId) {
            this(eventId, correlationId, List.of());
        }
    }

    public record ReturnLotSplit(@Pattern(regexp = ULID) String returnLineId,
                                 @Pattern(regexp = ULID) String lotId,
                                 @NotNull @DecimalMin(value = "0", inclusive = false)
                                 @Digits(integer = 13, fraction = 6) BigDecimal baseQuantity) { }

    public record ReturnLine(@Pattern(regexp = ULID) String returnLineId,
                             @Pattern(regexp = ULID) String receiptLineId,
                             @NotNull @DecimalMin(value = "0", inclusive = false)
                             @Digits(integer = 13, fraction = 6) BigDecimal returnQuantity) {
    }
}
