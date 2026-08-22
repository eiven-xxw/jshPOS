package com.jingshanghui.pos.transfer.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 调拨 REST 输入；平台 BIGINT 使用十进制字符串避免 JavaScript 精度损失。 */
public final class TransferRequests {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String POSITIVE_ID = "^[1-9][0-9]{0,18}$";
    private TransferRequests() { }

    public record Create(@Pattern(regexp = ULID) String transferId,
                         @Pattern(regexp = POSITIVE_ID) String sourceStoreId,
                         @Pattern(regexp = ULID) String sourceWarehouseId,
                         @Pattern(regexp = POSITIVE_ID) String destinationStoreId,
                         @Pattern(regexp = ULID) String destinationWarehouseId,
                         @NotEmpty @Size(max = 500) List<@Valid CreateLine> lines,
                         @NotBlank @Size(max = 256) String reason,
                         @NotBlank @Size(max = 96) String correlationId) { }

    public record CreateLine(@Pattern(regexp = ULID) String transferLineId,
                             @Pattern(regexp = POSITIVE_ID) String skuId,
                             @Pattern(regexp = POSITIVE_ID) String unitId,
                             @NotNull @DecimalMin(value = "0", inclusive = false)
                             @Digits(integer = 13, fraction = 6) BigDecimal requestedQuantity) { }

    public record State(@Pattern(regexp = ULID) String commandId, @PositiveOrZero long expectedVersion,
                        @NotBlank @Size(max = 256) String reason,
                        @NotBlank @Size(max = 96) String correlationId) { }

    public record Dispatch(@Pattern(regexp = ULID) String dispatchId,
                           @Pattern(regexp = ULID) String eventId,
                           @PositiveOrZero long expectedVersion,
                           @NotBlank @Size(max = 96) String correlationId,
                           @Size(max = 1000) List<@Valid DispatchLotSplit> lotSplits) {
        public Dispatch(String dispatchId, String eventId, long expectedVersion, String correlationId) {
            this(dispatchId, eventId, expectedVersion, correlationId, List.of());
        }
    }

    public record DispatchLotSplit(@Pattern(regexp = ULID) String transferLineId,
                                   @Pattern(regexp = ULID) String lotId,
                                   @NotNull @DecimalMin(value = "0", inclusive = false)
                                   @Digits(integer = 13, fraction = 6) BigDecimal baseQuantity) { }

    public record Receive(@Pattern(regexp = ULID) String receiptId,
                          @Pattern(regexp = ULID) String eventId,
                          @PositiveOrZero long expectedVersion, boolean finalReceipt,
                          @NotEmpty @Size(max = 500) List<@Valid ReceiveLine> lines,
                          @NotBlank @Size(max = 96) String correlationId,
                          @Size(max = 1000) List<@Valid ReceiveLotSplit> lotSplits) {
        public Receive(String receiptId, String eventId, long expectedVersion, boolean finalReceipt,
                       List<ReceiveLine> lines, String correlationId) {
            this(receiptId, eventId, expectedVersion, finalReceipt, lines, correlationId, List.of());
        }
    }

    public record ReceiveLine(@Pattern(regexp = ULID) String receiptLineId,
                              @Pattern(regexp = ULID) String transferLineId,
                              @NotNull @DecimalMin(value = "0", inclusive = false)
                              @Digits(integer = 13, fraction = 6) BigDecimal receivedQuantity) { }

    public record ReceiveLotSplit(@Pattern(regexp = ULID) String receiptLineId,
                                  @Pattern(regexp = ULID) String sourceLotId,
                                  @NotNull @DecimalMin(value = "0", inclusive = false)
                                  @Digits(integer = 13, fraction = 6) BigDecimal baseQuantity) { }

    public record Difference(@Pattern(regexp = ULID) String commandId, @PositiveOrZero long expectedVersion,
                             @NotEmpty @Size(max = 500) List<@Valid DifferenceLine> lines,
                             @NotBlank @Size(max = 256) String reason,
                             @NotBlank @Size(max = 96) String correlationId) { }

    public record DifferenceLine(@Pattern(regexp = ULID) String transferLineId,
                                 @NotNull @DecimalMin(value = "0", inclusive = false)
                                 @Digits(integer = 13, fraction = 6) BigDecimal differenceQuantity,
                                 @Pattern(regexp = "^(SHORTAGE|DAMAGED|REJECTED|TRANSIT_LOSS)$")
                                 String differenceReason) { }
}
