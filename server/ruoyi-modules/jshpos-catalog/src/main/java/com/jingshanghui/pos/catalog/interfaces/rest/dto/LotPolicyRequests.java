package com.jingshanghui.pos.catalog.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/** 批次策略 REST 输入；不包含 tenant_id、industry 或服务端状态。 */
public final class LotPolicyRequests {
    private LotPolicyRequests() { }

    /** 发布不可变策略版本的协议输入。 */
    public record Publish(
        @NotBlank @Pattern(regexp = "^[0-9A-HJKMNP-TV-Z]{26}$") String policyVersionId,
        @NotNull @Positive Long storeId,
        @NotNull @Positive Long skuId,
        boolean enabled,
        @NotBlank String expiryBasis,
        @Min(1) @Max(36500) Integer shelfLifeDays,
        @Min(0) @Max(3650) int nearExpiryDays,
        @NotNull Instant effectiveFrom
    ) { }
}

