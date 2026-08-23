package com.jingshanghui.pos.member.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/** T2-MEM-003 API DTO；不接收 tenant_id 或会员身份明文。 */
public final class BenefitRequests {
    public static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256="^[a-f0-9]{64}$";
    public record Level(@NotBlank @Pattern(regexp="^[A-Z0-9_-]{1,32}$") String levelCode,
                        boolean memberPriceEligible, boolean stackingAllowed) { }
    public record Create(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=ULID) String policyId,
                         @NotBlank @Pattern(regexp=ULID) String versionId,
                         @NotBlank @Pattern(regexp="^[A-Z0-9_-]{1,64}$") String policyCode,
                         @NotBlank @Size(max=100) String displayName,
                         @NotEmpty @Size(max=100) List<@Valid Level> levelRules,
                         @NotEmpty @Size(max=1000) List<@Positive Long> storeIds,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Action(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=SHA256) String contentSha256,
                         Instant effectiveAt, Instant expiresAt,
                         @Size(max=64) String reasonCode, @Size(max=500) String reason,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Issue(@NotBlank @Pattern(regexp=ULID) String commandId,
                        @NotBlank @Pattern(regexp=ULID) String snapshotId,
                        @NotBlank @Pattern(regexp=ULID) String memberId,
                        @NotNull @Positive Long storeId,
                        @NotNull Instant quoteAt,
                        @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    private BenefitRequests() { }
}
