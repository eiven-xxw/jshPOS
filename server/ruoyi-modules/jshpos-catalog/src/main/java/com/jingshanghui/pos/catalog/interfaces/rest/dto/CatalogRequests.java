package com.jingshanghui.pos.catalog.interfaces.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.jingshanghui.pos.catalog.application.importing.CatalogImportRow;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CatalogRequests {

    private CatalogRequests() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateCategory(Long parentId, @NotBlank @Size(max = 64) String code,
                                 @NotBlank @Size(max = 200) String name, int sortNo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateDefinition(@NotBlank @Size(max = 64) String code,
                                   @NotBlank @Size(max = 200) String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateUnit(@NotBlank @Size(max = 64) String code,
                             @NotBlank @Size(max = 100) String name,
                             @Min(0) @Max(6) int decimalScale) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateProduct(
        @NotBlank @Size(max = 64) String spuCode,
        @NotBlank @Size(max = 64) String skuCode,
        @NotBlank @Size(max = 200) String name,
        @NotNull Long categoryId,
        Long brandId,
        @NotBlank String productType,
        Map<String, Object> attributes,
        @NotEmpty @Size(max = 50) List<@Valid UnitInput> units
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record UnitInput(
        @NotNull Long unitId,
        @NotNull @Min(1) Long ratioNumerator,
        @NotNull @Min(1) Long ratioDenominator,
        boolean primary,
        @Size(max = 100) List<@NotBlank @Size(max = 64) String> barcodes
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ChangeProductState(@NotBlank String state, @Min(0) int version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ImportPreflight(
        @NotBlank @Size(min = 8, max = 128) String idempotencyKey,
        @NotEmpty @Size(max = 100000) List<@Valid ImportRow> rows
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ImportRow(
        @Min(1) int rowNumber,
        @NotBlank String spuCode,
        @NotBlank String skuCode,
        @NotBlank String name,
        @NotBlank String categoryCode,
        @NotBlank String brandCode,
        @NotBlank String productType,
        @NotBlank String baseUnitCode,
        @NotBlank String quantity,
        @NotNull @Min(1) Long ratioNumerator,
        @NotNull @Min(1) Long ratioDenominator,
        @Size(max = 100) List<@NotBlank String> barcodes
    ) {
        public CatalogImportRow toCommand() {
            return new CatalogImportRow(rowNumber, spuCode, skuCode, name, categoryCode, brandCode,
                productType, baseUnitCode, quantity, ratioNumerator, ratioDenominator, barcodes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreatePriceBook(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 200) String name,
        @Min(1) int versionNo,
        @NotBlank String scopeType,
        Long storeId
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AddPriceItem(
        @NotNull Long skuId,
        @NotNull Long unitId,
        @NotNull @Min(0) Long amountMinor,
        @NotNull Instant effectiveFrom,
        Instant effectiveTo
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PublishPackage(@NotNull Long storeId, @Min(1) long packageVersion,
                                 @Min(0) long previousVersion) {
    }
}
