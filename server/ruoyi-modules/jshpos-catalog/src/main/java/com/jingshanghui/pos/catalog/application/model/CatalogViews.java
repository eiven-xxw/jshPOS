package com.jingshanghui.pos.catalog.application.model;

import java.time.Instant;
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
