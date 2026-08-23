package com.jingshanghui.pos.saas.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;

/** Gate 8A REST 输入；tenant_id 不在任何客户端 DTO 中出现。 */
public final class SaasRequests {
    private SaasRequests() { }
    public record ApplicationCreate(@NotBlank @Size(max=64) String applicationCode,
        @NotBlank @Size(max=128) String companyName, @NotBlank @Size(max=64) String industry,
        @NotNull @Positive Long planId) { }
    public record Reason(@NotBlank @Size(max=256) String reason) { }
    public record Provision(@NotBlank @Size(max=64) String contactName,
        @NotBlank @Size(max=32) String contactPhone, @NotBlank @Size(max=64) String bootstrapUsername,
        @NotBlank @Size(min=12,max=128) String bootstrapPassword) { }
    public record PlanCreate(@NotBlank @Size(max=64) String planCode, @NotBlank @Size(max=64) String planName,
        @NotNull @Positive Long platformPackageId, @NotNull @Positive Long accountLimit) { }
    public record EntitlementItem(@NotBlank @Size(max=64) String featureCode, @NotNull Boolean enabled,
        @PositiveOrZero Long quotaLimit) { }
    public record VersionCreate(@NotNull @Positive Integer versionNo, @NotNull LocalDateTime effectiveAt,
        LocalDateTime expiresAt, @NotEmpty @Size(max=128) List<@Valid EntitlementItem> items) { }
    public record QuotaConsume(@NotNull @Min(-1000000) @Max(1000000) Long delta) { }
}
