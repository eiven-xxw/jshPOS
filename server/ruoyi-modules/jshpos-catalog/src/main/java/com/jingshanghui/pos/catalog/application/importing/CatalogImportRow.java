package com.jingshanghui.pos.catalog.application.importing;

import java.util.List;

/** 经过 API 层解析后的定点字符串导入行；不接受 float/double。 */
public record CatalogImportRow(
    int rowNumber,
    String spuCode,
    String skuCode,
    String name,
    String categoryCode,
    String brandCode,
    String productType,
    String baseUnitCode,
    String quantity,
    Long ratioNumerator,
    Long ratioDenominator,
    List<String> barcodes
) {
    public CatalogImportRow {
        barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
    }
}
