package com.jingshanghui.pos.inventory.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** REST 输入只携带命令身份和路由信息，不携带租户、SKU、数量或支付事实。 */
public final class InventoryRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private InventoryRequests() {
    }

    public record SourceCommand(@Pattern(regexp = ULID) String eventId,
                                @Pattern(regexp = ULID) String warehouseId,
                                @NotBlank @Size(max = 96) String correlationId) {
    }

    public record PublishPolicy(@Pattern(regexp = ULID) String policyVersionId,
                                @Pattern(regexp = "^[1-9][0-9]{0,18}$") String storeId,
                                @Pattern(regexp = ULID) String warehouseId,
                                @Pattern(regexp = "^(DENY|ALLOW_AND_ALERT)$") String negativeStockMode,
                                @NotNull Instant effectiveFrom,
                                @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Rebuild(@NotBlank @Size(max = 96) String correlationId) {
    }
}
