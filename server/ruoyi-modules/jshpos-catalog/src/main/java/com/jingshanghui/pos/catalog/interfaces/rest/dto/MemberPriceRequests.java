package com.jingshanghui.pos.catalog.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;

/** 会员价 API DTO；不允许客户端提交 tenant_id。 */
public final class MemberPriceRequests {
    public static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256="^[a-f0-9]{64}$";
    public record Item(@NotBlank @Pattern(regexp=ULID) String itemId,
                       @NotBlank @Pattern(regexp="^[A-Z0-9_-]{1,32}$") String levelCode,
                       @NotNull @Positive Long skuId,@NotNull @Positive Long unitId,
                       @NotNull @PositiveOrZero Long amountMinor) { }
    public record Create(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=ULID) String versionId,
                         @NotBlank @Pattern(regexp="^[A-Z0-9_-]{1,64}$") String bookCode,
                         @Positive int versionNo,@Positive Long storeId,
                         @NotEmpty @Size(max=100000) List<@Valid Item> items,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    public record Action(@NotBlank @Pattern(regexp=ULID) String commandId,
                         @NotBlank @Pattern(regexp=SHA256) String contentSha256,
                         Instant effectiveAt,Instant expiresAt,
                         @NotBlank @Pattern(regexp=ULID) String correlationId) { }
    private MemberPriceRequests(){ }
}
