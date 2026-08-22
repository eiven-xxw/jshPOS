package com.jingshanghui.pos.catalog.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

/** 价签 REST 输入；不接受 tenant_id，门店仍必须经过服务端可信数据范围校验。 */
public final class ShelfLabelRequests {

    private ShelfLabelRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateTemplate(
        @NotBlank @Size(max = 64) String templateCode,
        @NotBlank @Size(max = 200) String templateName,
        @Min(1) int versionNo,
        @NotBlank String scopeType,
        Long storeId,
        @NotBlank @Size(max = 2000) String bodyTemplate,
        @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
        @NotBlank @Size(max = 96) String correlationId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Versioned(@Min(0) int expectedVersion,
                            @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                            @NotBlank @Size(max = 96) String correlationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Preview(Long templateId,
                          @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                          @NotBlank @Size(max = 96) String correlationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Confirm(@Min(0) int expectedVersion,
                          @NotBlank @Size(max = 500) String reason,
                          @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                          @NotBlank @Size(max = 96) String correlationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RecordException(@Min(0) int expectedVersion,
                                  @NotBlank @Size(max = 500) String reason,
                                  @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                                  @NotBlank @Size(max = 96) String correlationId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Dispatch(@NotBlank @Pattern(regexp = "[a-f0-9]{64}") String previewSha256,
                           @Min(0) int expectedVersion,
                           @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
                           @NotBlank @Size(max = 96) String correlationId) {
    }
}
