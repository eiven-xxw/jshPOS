package com.jingshanghui.pos.inventory.interfaces.rest.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/** 盘点 REST 输入；租户、门店和操作者不得由客户端声明。 */
public final class StocktakeRequests {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    private StocktakeRequests() {
    }

    public record Create(@Pattern(regexp = ULID) String stocktakeId,
                         @Pattern(regexp = ULID) String warehouseId,
                         @NotEmpty @Size(max = 500) List<@NotNull Long> skuIds,
                         boolean blindCount,
                         @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal recountThreshold,
                         @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Count(@Pattern(regexp = ULID) String countId,
                        @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal countedQuantity,
                        @NotBlank @Size(max = 64) String deviceId,
                        @Size(max = 256) String reason,
                        @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Correlated(@NotBlank @Size(max = 96) String correlationId) {
    }

    public record Review(@Pattern(regexp = "^(ACCEPT|RECOUNT)$") String decision,
                         @Size(max = 256) String reason,
                         @NotBlank @Size(max = 96) String correlationId) {
    }

    public record Approve(@Pattern(regexp = ULID) String eventId,
                          @NotBlank @Size(max = 96) String correlationId) {
    }
}
