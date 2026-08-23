package com.jingshanghui.pos.operations.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 日结 REST 输入；禁止接收 tenant、金额、差异或目标状态。 */
public final class DailyCloseRequests {
    private DailyCloseRequests() { }

    public record Create(@NotNull @Positive Long storeId, @NotNull LocalDate businessDate,
                         @Size(min = 26, max = 26) String correctionOfCloseId,
                         @Size(min = 8, max = 256) String correctionReason) { }
    public record Reason(@NotNull @Size(min = 8, max = 256) String reason) { }
}
