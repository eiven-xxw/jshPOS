package com.jingshanghui.pos.catalog.application.importing;

import com.jingshanghui.pos.catalog.domain.CatalogRules;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** 流式预检器：最多 100k 行，错误有界，完整校验后才允许提交。 */
@Component
public class CatalogImportPreflight {

    public static final int MAX_ROWS = 100_000;
    public static final int MAX_REPORTED_ERRORS = 10_000;

    public Result validate(Iterable<CatalogImportRow> rows) {
        Set<String> skuCodes = new HashSet<>();
        Set<String> barcodes = new HashSet<>();
        List<RowError> errors = new ArrayList<>();
        MessageDigest digest = sha256();
        int count = 0;
        for (CatalogImportRow row : rows) {
            count++;
            if (count > MAX_ROWS) {
                throw new ServiceException("CAT-IMP-001: 单批最多 100000 行", 413);
            }
            validateRow(row, skuCodes, barcodes, errors, digest);
        }
        if (count == 0) {
            addError(errors, 0, "EMPTY_BATCH", "导入批次不能为空");
        }
        return new Result(count, errors.size(), errors, HexFormat.of().formatHex(digest.digest()));
    }

    private void validateRow(CatalogImportRow row, Set<String> skuCodes, Set<String> barcodes,
                             List<RowError> errors, MessageDigest digest) {
        if (row == null) {
            addError(errors, 0, "NULL_ROW", "导入行不能为空");
            return;
        }
        List<String> canonical = new ArrayList<>();
        tryField(errors, row.rowNumber(), "SPU_CODE", () -> canonical.add(CatalogRules.requireCode(row.spuCode(), "CAT-PRD-009")));
        tryField(errors, row.rowNumber(), "SKU_CODE", () -> {
            String sku = CatalogRules.requireCode(row.skuCode(), "CAT-PRD-010");
            canonical.add(sku);
            if (!skuCodes.add(sku)) {
                throw new ServiceException("CAT-IMP-002: 批内 SKU 重复", 409);
            }
        });
        tryField(errors, row.rowNumber(), "NAME", () -> canonical.add(CatalogRules.requireName(row.name())));
        tryField(errors, row.rowNumber(), "CATEGORY", () -> canonical.add(CatalogRules.requireCode(row.categoryCode(), "CAT-PRD-011")));
        tryField(errors, row.rowNumber(), "BRAND", () -> canonical.add(CatalogRules.requireCode(row.brandCode(), "CAT-PRD-012")));
        tryField(errors, row.rowNumber(), "TYPE", () -> canonical.add(CatalogRules.requireProductType(row.productType())));
        tryField(errors, row.rowNumber(), "UNIT", () -> canonical.add(CatalogRules.requireCode(row.baseUnitCode(), "CAT-PRD-013")));
        tryField(errors, row.rowNumber(), "QUANTITY", () -> canonical.add(CatalogRules.requireQuantity(row.quantity()).toPlainString()));
        tryField(errors, row.rowNumber(), "RATIO", () -> {
            CatalogRules.UnitRatio ratio = CatalogRules.requireRatio(row.ratioNumerator(), row.ratioDenominator());
            canonical.add(ratio.numerator() + "/" + ratio.denominator());
        });
        for (String rawBarcode : row.barcodes()) {
            tryField(errors, row.rowNumber(), "BARCODE", () -> {
                String barcode = CatalogRules.requireBarcode(rawBarcode);
                canonical.add(barcode);
                if (!barcodes.add(barcode)) {
                    throw new ServiceException("CAT-IMP-003: 批内条码重复", 409);
                }
            });
        }
        digest.update((row.rowNumber() + "|" + String.join("|", canonical) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private void tryField(List<RowError> errors, int row, String field, Runnable validation) {
        try {
            validation.run();
        } catch (ServiceException exception) {
            addError(errors, row, field, exception.getMessage());
        }
    }

    private void addError(List<RowError> errors, int row, String field, String message) {
        if (errors.size() < MAX_REPORTED_ERRORS) {
            errors.add(new RowError(row, field, message));
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Result(int rowCount, int errorCount, List<RowError> errors, String payloadSha256) {
        public Result {
            errors = List.copyOf(errors);
        }

        public boolean accepted() {
            return errorCount == 0;
        }
    }

    public record RowError(int rowNumber, String field, String message) {
    }
}
