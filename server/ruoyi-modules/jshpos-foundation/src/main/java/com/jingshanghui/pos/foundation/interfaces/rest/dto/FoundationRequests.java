package com.jingshanghui.pos.foundation.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Gate 0 请求 DTO。刻意不提供 tenantId 字段。
 */
public final class FoundationRequests {

    private FoundationRequests() {
    }

    public record CreateOrgUnit(
        Long parentId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String type
    ) {
    }

    public record UpdateOrgUnit(
        Long parentId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String type,
        @NotBlank String status,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record CreateStore(
        @NotNull Long orgUnitId,
        Long platformDeptId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull LocalTime businessDayStart
    ) {
    }

    public record UpdateStore(
        @NotNull Long orgUnitId,
        Long platformDeptId,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull LocalTime businessDayStart,
        @NotBlank String status,
        @NotNull @Min(0) Integer version
    ) {
    }

    public record StaffScopeInput(
        @NotBlank String scopeType,
        Long orgUnitId,
        Long storeId
    ) {
    }

    public record ReplaceStaffScopes(
        @NotNull @Size(max = 100) List<@Valid StaffScopeInput> scopes
    ) {
    }

    public record CreateConfigTemplate(
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank String industry
    ) {
    }

    public record CreateConfigVersion(
        @NotBlank @Pattern(regexp = "^[1-9][0-9]*\\.[0-9]+$") String schemaVersion,
        @NotNull @Size(max = 256) Map<String, Object> content
    ) {
    }

    public record ActivateConfig(
        @NotNull Long templateId,
        @NotNull Long configVersionId,
        @NotBlank String targetType,
        Long targetId
    ) {
    }
}
