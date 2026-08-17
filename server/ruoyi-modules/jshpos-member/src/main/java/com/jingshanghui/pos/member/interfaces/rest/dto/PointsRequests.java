package com.jingshanghui.pos.member.interfaces.rest.dto;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

/** 在线积分与等级 API 请求；金额使用字符串小数且不接收 tenant_id。 */
public final class PointsRequests {
    public record Freeze(@NotBlank @Pattern(regexp=MemberRequests.ULID) String commandId,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String ledgerId,
                         @NotNull @Positive Long storeId,
                         @NotBlank @Pattern(regexp="^[0-9]{1,13}(\\.[0-9]{1,6})?$") String amount,
                         @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$") String policyVersion,
                         @NotNull OffsetDateTime occurredAt,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String correlationId) { }
    public record Settle(@NotBlank @Pattern(regexp=MemberRequests.ULID) String commandId,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String ledgerId,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String freezeLedgerId,
                         @NotNull @Positive Long storeId,
                         @NotBlank @Pattern(regexp="^[0-9]{1,13}(\\.[0-9]{1,6})?$") String amount,
                         @NotBlank @Pattern(regexp="^(SPEND|UNFREEZE)$") String action,
                         @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$") String policyVersion,
                         @NotNull OffsetDateTime occurredAt,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String correlationId) { }
    public record Adjust(@NotBlank @Pattern(regexp=MemberRequests.ULID) String commandId,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String ledgerId,
                         @NotNull @Positive Long storeId,
                         @NotBlank @Pattern(regexp="^-?[0-9]{1,13}(\\.[0-9]{1,6})?$") String signedAmount,
                         @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$") String policyVersion,
                         @NotBlank @Size(max=256) String reason,
                         @NotNull @Positive Long approvalUserId,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String approvalRef,
                         @NotNull OffsetDateTime occurredAt,
                         @NotBlank @Pattern(regexp=MemberRequests.ULID) String correlationId) { }
    public record Level(@NotBlank @Pattern(regexp=MemberRequests.ULID) String commandId,
                        @NotBlank @Pattern(regexp=MemberRequests.ULID) String historyId,
                        @NotNull @Positive Long storeId,
                        @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_-]{0,31}$") String levelCode,
                        @NotBlank @Pattern(regexp="^[A-Za-z0-9._-]{1,64}$") String policyVersion,
                        @NotBlank @Pattern(regexp="^[A-Z][A-Z0-9_-]{0,31}$") String reasonCode,
                        @NotNull @Positive Long approvalUserId,
                        @NotBlank @Pattern(regexp=MemberRequests.ULID) String approvalRef,
                        @NotNull OffsetDateTime effectiveAt,
                        @NotBlank @Pattern(regexp=MemberRequests.ULID) String correlationId) { }
    private PointsRequests() { }
}
