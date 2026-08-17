package com.jingshanghui.pos.member.interfaces.rest.dto;

import jakarta.validation.constraints.*;

/** 会员 API 请求 DTO；刻意不包含 tenant_id。 */
public final class MemberRequests {
    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256 = "^[a-f0-9]{64}$";
    public record Create(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=ULID) String memberId,
                         @NotBlank @Pattern(regexp=ULID) String identityId,
                         @NotBlank @Pattern(regexp="^(MOBILE|MEMBER_CODE|CARD|EXTERNAL_OPEN_ID)$") String identityType,
                         @NotBlank @Size(max=128) String identityValue,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Resolve(@NotBlank @Pattern(regexp="^(MOBILE|MEMBER_CODE|CARD|EXTERNAL_OPEN_ID)$") String identityType,
                          @NotBlank @Size(max=128) String identityValue) { }
    public record BindIdentity(@NotBlank @Pattern(regexp=ULID) String commandId,
                               @NotBlank @Pattern(regexp=ULID) String identityId,
                               @NotBlank @Pattern(regexp="^(MOBILE|MEMBER_CODE|CARD|EXTERNAL_OPEN_ID)$") String identityType,
                               @NotBlank @Size(max=128) String identityValue,
                               @NotBlank @Size(max=256) String reason,
                               @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Revoke(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Size(max=256) String reason,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Consent(@NotBlank @Pattern(regexp=ULID) String commandId,
                          @NotBlank @Pattern(regexp=ULID) String consentId,
                          @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_]{0,63}$") String purposeCode,
                          @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$") String policyVersion,
                          @NotBlank @Pattern(regexp="^(GRANTED|REVOKED)$") String state,
                          @NotBlank @Pattern(regexp=SHA256) String evidenceSha256,
                          @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Privacy(@NotBlank @Pattern(regexp=ULID) String commandId,
                          @NotBlank @Pattern(regexp=ULID) String requestId,
                          @NotBlank @Pattern(regexp="^(ACCESS|EXPORT|CORRECT|DELETE)$") String requestType,
                          @NotBlank @Size(max=256) String reason,
                          @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record PrivacyState(@NotBlank @Pattern(regexp=ULID) String commandId,
                               @NotBlank @Pattern(regexp="^(IDENTITY_VERIFIED|IN_PROGRESS|FULFILLED|PARTIALLY_FULFILLED|REJECTED)$") String toState,
                               @Min(0) int expectedVersion,
                               @NotBlank @Size(max=256) String reason,
                               @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Link(@NotBlank @Pattern(regexp=ULID) String commandId,
                       @NotBlank @Pattern(regexp=ULID) String sourceMemberId,
                       @NotBlank @Pattern(regexp=ULID) String targetMemberId,
                       @NotBlank @Pattern(regexp=ULID) String linkId,
                       @NotBlank @Size(max=256) String reason,
                       @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    private MemberRequests() { }
}
