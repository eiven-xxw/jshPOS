package com.jingshanghui.pos.inventory.interfaces.rest.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/** 批次投影管理 REST 输入；不接收 tenant_id、余额、效期状态或成本。 */
public final class LotInventoryRequests {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private LotInventoryRequests() { }

    public record Rebuild(@Pattern(regexp = ULID) String commandId,
                          @Positive Long storeId,
                          @Pattern(regexp = ULID) String warehouseId,
                          @Positive Long skuId,
                          @NotNull LocalDate businessDate) { }
}
