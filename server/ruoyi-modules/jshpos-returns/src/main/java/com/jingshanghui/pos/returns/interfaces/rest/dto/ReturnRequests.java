package com.jingshanghui.pos.returns.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 退货退款 REST 输入；tenant_id 与操作者只允许来自服务端可信认证上下文。 */
public final class ReturnRequests {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String POSITIVE_ID = "^[1-9][0-9]{0,18}$";

    private ReturnRequests() { }

    /** 原单退货退款申请；数量以精确十进制传输，禁止浮点数。 */
    public record Create(@Pattern(regexp = ULID) String commandId,
                         @NotBlank @Size(max = 96) String idempotencyKey,
                         @Pattern(regexp = ULID) String returnId,
                         @Pattern(regexp = ULID) String orderId,
                         @Pattern(regexp = POSITIVE_ID) String storeId,
                         @Pattern(regexp = ULID) String terminalId,
                         @Pattern(regexp = ULID) String refundShiftId,
                         @Pattern(regexp = ULID) String warehouseId,
                         @NotNull LocalDate businessDate,
                         @Pattern(regexp = "^(CASH|PROVIDER_NEUTRAL)$") String settlementKind,
                         @Pattern(regexp = ULID) String paymentId,
                         @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                         @NotEmpty @Size(max = 500) List<@Valid Line> lines,
                         @Pattern(regexp = ULID) String correlationId,
                         @NotNull Instant occurredAt) { }

    /** 原成交行与本次退货数量。 */
    public record Line(@Pattern(regexp = ULID) String orderLineId,
                       @NotNull @DecimalMin(value = "0", inclusive = false)
                       @Digits(integer = 13, fraction = 6) BigDecimal quantity) { }

    /** 独立审批命令；审批人由可信上下文提供并与申请人执行职责分离。 */
    public record Approve(@Pattern(regexp = ULID) String commandId,
                          @Pattern(regexp = "^[A-Z0-9_]{2,32}$") String reasonCode,
                          @Pattern(regexp = ULID) String correlationId,
                          @NotNull Instant occurredAt) { }
}
