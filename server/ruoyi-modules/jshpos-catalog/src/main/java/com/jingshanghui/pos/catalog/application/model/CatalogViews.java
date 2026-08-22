package com.jingshanghui.pos.catalog.application.model;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

public final class CatalogViews {

    private CatalogViews() {
    }

    public record DefinitionView(Long id, String code, String name, String status) {
    }

    public record ProductView(
        Long skuId, Long spuId, String spuCode, String skuCode, String name,
        Long categoryId, Long brandId, String productType, String status, Integer version
    ) {
    }

    public record ImportBatchView(
        Long importBatchId, String idempotencyKey, String payloadSha256,
        Integer rowCount, Integer errorCount, String state, Long previousBatchId
    ) {
    }

    public record PriceBookView(
        Long priceBookId, String bookCode, String bookName, Integer versionNo,
        String scopeType, Long storeId, String state, String contentSha256
    ) {
    }

    public record PriceCandidateView(
        Long priceBookId, Long priceItemId, Integer versionNo, String scopeType, Long scopeStoreId,
        Long amountMinor, String currency, Instant effectiveFrom, Instant effectiveTo, boolean published
    ) {
    }

    /** 版本化秤码/金额码模板；发布后的内容由 contentSha256 唯一标识。 */
    public record WeightedBarcodeTemplateView(
        Long templateId, String templateCode, Integer versionNo, String scopeType, Long storeId,
        String barcodeKind, String symbology, String prefixValue, Integer totalLength,
        Integer skuStartPos, Integer skuLength, Integer valueStartPos, Integer valueLength,
        Integer valueScale, Integer priorityNo, String state, Instant effectiveFrom, Instant effectiveTo,
        String contentSha256, Instant publishedAt, Integer version
    ) {
    }

    /** 模板解析预览；只用于发布前验证，POS 成交仍必须使用签名数据包内模板。 */
    public record WeightedBarcodePreview(
        String rawBarcode, Long skuId, String skuCode, Long unitId, BigDecimal quantity,
        Long amountMinor, Long unitPriceMinor, String currency, Long templateId, Integer templateVersion,
        String templateSha256, String parseSha256, boolean roundingApplied, Instant occurredAt
    ) {
    }

    /** 解析秤码时由 Catalog Owner 提供的启用计量 SKU 与基础单位快照。 */
    public record WeightedSkuView(Long skuId, String skuCode, Long unitId, Integer decimalScale,
                                  String productType, String status) {
    }

    public record PackageView(
        Long packageId, Long storeId, Long packageVersion, Long previousVersion,
        String schemaVersion, String payloadSha256, String signatureAlgorithm,
        String signingKeyId, String objectKey, Integer recordCount, Instant generatedAt
    ) {
    }

    /** POS 下载的 canonical 商品价格包，摘要与签名由响应头携带。 */
    public record PackageArtifact(byte[] payload, String payloadSha256,
                                  String signingKeyId, byte[] signature) {
        public PackageArtifact {
            payload = payload.clone();
            signature = signature.clone();
        }

        @Override public byte[] payload() { return payload.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }

    public record ImportPreflightView(ImportBatchView batch, List<ImportErrorView> errors) {
        public ImportPreflightView {
            errors = List.copyOf(errors);
        }
    }

    public record ImportErrorView(int rowNumber, String field, String message) {
    }
}
