package com.jingshanghui.pos.subscription.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 订阅 REST 输入；目标租户只存在于平台受控路径，不作为客户端授权依据。 */
public final class SubscriptionRequests {
    private SubscriptionRequests() { }
    public record Create(@NotBlank @Size(max=128) String contractRef,
        @NotBlank @Size(max=128) String externalOrderRef, @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt, @NotNull LocalDateTime graceEndsAt,
        @NotBlank @Size(max=64) String businessTimeZone,
        @NotBlank @Size(max=64) String degradationPolicyVersion) { }
    public record Reason(@NotBlank @Size(max=256) String reason) { }
    public record NewTerm(@NotBlank @Size(max=128) String contractRef,
        @NotBlank @Size(max=128) String externalOrderRef, @NotNull LocalDateTime startsAt,
        @NotNull LocalDateTime endsAt, @NotNull LocalDateTime graceEndsAt,
        @NotBlank @Size(max=64) String businessTimeZone) { }
}
