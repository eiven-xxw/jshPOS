package com.jingshanghui.pos.migration.domain;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.migration.domain.MigrationStates.DataType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将安全解析后的单行冻结为 Owner 命令所需的规范 JSON 与稳定目标身份。 */
@Component
@RequiredArgsConstructor
public class MigrationRowNormalizer {
    private final UlidGenerator ids;

    public List<NormalizedRow> normalize(DataType type, List<String> headers, List<Map<String, String>> rows) {
        PreflightResult result = preflight(type, headers, rows);
        if (!result.errors().isEmpty()) {
            PreflightIssue first = result.errors().get(0);
            throw new ServiceException(first.errorCode() + ": " + first.maskedMessage(), 400);
        }
        return result.rows();
    }

    /**
     * 对整个文件执行行级预检，一次返回全部脱敏阻断错误。
     * 无效行不会占用 SKU、条码、供应商或会员唯一性集合。
     */
    public PreflightResult preflight(DataType type, List<String> headers, List<Map<String, String>> rows) {
        requireHeaders(type, headers);
        Set<String> businessKeys = new HashSet<>();
        Set<String> secondaryKeys = new HashSet<>();
        List<NormalizedRow> result = new ArrayList<>(rows.size());
        List<PreflightIssue> errors = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            try {
                Map<String, Object> value = switch (type) {
                    case CATALOG -> catalog(rows.get(index), businessKeys, secondaryKeys);
                    case SUPPLIER -> supplier(rows.get(index), businessKeys);
                    case OPENING_INVENTORY -> opening(rows.get(index), businessKeys);
                    case MEMBER -> member(rows.get(index), businessKeys);
                };
                String rowId = ids.next();
                value.put("rowId", rowId);
                CanonicalJson.Result canonical = CanonicalJson.from(value, 256 * 1024);
                result.add(new NormalizedRow(rowId, index + 2, canonical.json(), canonical.sha256()));
            } catch (ServiceException exception) {
                String message = exception.getMessage() == null ? "DMT-PREFLIGHT-999: 预检失败" : exception.getMessage();
                int delimiter = message.indexOf(':');
                String code = delimiter > 0 ? message.substring(0, delimiter) : "DMT-PREFLIGHT-999";
                String masked = delimiter > 0 ? message.substring(delimiter + 1).strip() : "预检失败，原始值已隐藏";
                errors.add(new PreflightIssue(index + 2, null, code, masked));
            }
        }
        return new PreflightResult(result, errors);
    }

    private Map<String, Object> catalog(Map<String, String> row, Set<String> skus, Set<String> barcodes) {
        String skuCode = text(row, "skuCode", 64);
        List<String> barcodeList = splitBarcodes(row.get("barcodes"));
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("spuCode", text(row, "spuCode", 64));
        value.put("skuCode", skuCode);
        value.put("name", text(row, "name", 160));
        value.put("categoryCode", text(row, "categoryCode", 64));
        value.put("categoryName", text(row, "categoryName", 160));
        value.put("brandCode", optional(row, "brandCode", 64));
        value.put("brandName", optional(row, "brandName", 160));
        String productType = text(row, "productType", 16);
        if (!Set.of("STANDARD", "COUNT", "WEIGHT").contains(productType)) conflict("DMT-PREFLIGHT-013: 商品类型无效");
        value.put("productType", productType);
        value.put("unitCode", text(row, "unitCode", 64));
        value.put("unitName", text(row, "unitName", 80));
        value.put("decimalScale", integer(row, "decimalScale", 0, 6));
        long numerator = positiveLong(row, "ratioNumerator");
        long denominator = positiveLong(row, "ratioDenominator");
        if (numerator != 1 || denominator != 1) {
            conflict("DMT-PREFLIGHT-016: 商品开业模板仅接受 1:1 基础单位，多单位必须使用 Catalog 正式导入端口");
        }
        value.put("ratioNumerator", numerator);
        value.put("ratioDenominator", denominator);
        value.put("barcodes", barcodeList);
        if ((value.get("brandCode") == null) != (value.get("brandName") == null)) {
            conflict("DMT-PREFLIGHT-014: 品牌编码与名称必须同时填写");
        }
        if (skus.contains(skuCode)) conflict("DMT-PREFLIGHT-011: 文件内 SKU 编码重复");
        if (new HashSet<>(barcodeList).size() != barcodeList.size()
            || barcodeList.stream().anyMatch(barcodes::contains)) {
            conflict("DMT-PREFLIGHT-012: 文件内条码重复");
        }
        skus.add(skuCode);
        barcodes.addAll(barcodeList);
        return value;
    }

    private Map<String, Object> supplier(Map<String, String> row, Set<String> codes) {
        String code = text(row, "supplierCode", 64);
        String name = text(row, "supplierName", 160);
        if (codes.contains(code)) conflict("DMT-PREFLIGHT-021: 文件内供应商编码重复");
        codes.add(code);
        return mutable(Map.of("supplierId", ids.next(), "supplierCode", code, "supplierName", name));
    }

    private Map<String, Object> opening(Map<String, String> row, Set<String> keys) {
        long storeId = positiveLong(row, "storeId");
        String warehouseId = MigrationRules.ulid(text(row, "warehouseId", 26), "warehouseId");
        String skuCode = text(row, "skuCode", 64);
        String key = storeId + ":" + warehouseId + ":" + skuCode;
        String date = text(row, "businessDate", 10);
        try { LocalDate.parse(date); }
        catch (DateTimeParseException exception) { conflict("DMT-PREFLIGHT-032: 业务日期无效"); }
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("eventId", ids.next());
        value.put("storeId", storeId);
        value.put("warehouseId", warehouseId);
        value.put("skuCode", skuCode);
        value.put("quantity", MigrationRules.quantity(row.get("quantity"), "quantity").toPlainString());
        value.put("unitCostMinor", MigrationRules.nonNegativeCost(row.get("unitCostMinor")).toPlainString());
        value.put("businessDate", date);
        value.put("currency", "CNY");
        if (keys.contains(key)) conflict("DMT-PREFLIGHT-031: 同仓同 SKU 期初库存重复");
        keys.add(key);
        return value;
    }

    private Map<String, Object> member(Map<String, String> row, Set<String> identities) {
        String type = text(row, "identityType", 16).toUpperCase();
        if (!Set.of("MOBILE", "EMAIL", "EXTERNAL").contains(type)) conflict("DMT-PREFLIGHT-041: 会员身份类型无效");
        String identity = text(row, "identityValue", 256);
        String key = type + ":" + identity.strip().toLowerCase();
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("commandId", ids.next());
        value.put("memberId", ids.next());
        value.put("identityId", ids.next());
        value.put("identityType", type);
        value.put("identityValue", identity);
        if (identities.contains(key)) conflict("DMT-PREFLIGHT-042: 文件内会员身份重复");
        identities.add(key);
        return value;
    }

    private void requireHeaders(DataType type, List<String> actual) {
        Set<String> expected = switch (type) {
            case CATALOG -> Set.of("spuCode", "skuCode", "name", "categoryCode", "categoryName",
                "brandCode", "brandName", "productType", "unitCode", "unitName", "decimalScale",
                "ratioNumerator", "ratioDenominator", "barcodes");
            case SUPPLIER -> Set.of("supplierCode", "supplierName");
            case OPENING_INVENTORY -> Set.of("storeId", "warehouseId", "skuCode", "quantity",
                "unitCostMinor", "businessDate");
            case MEMBER -> Set.of("identityType", "identityValue");
        };
        if (!new LinkedHashSet<>(actual).equals(expected)) {
            Set<String> missing = new HashSet<>(expected); missing.removeAll(actual);
            Set<String> unknown = new HashSet<>(actual); unknown.removeAll(expected);
            throw new ServiceException("DMT-MAPPING-003: 表头不匹配 missing=" + missing + ", unknown=" + unknown, 400);
        }
    }

    private String text(Map<String, String> row, String key, int max) {
        return MigrationRules.text(row.get(key), max, key);
    }
    private String optional(Map<String, String> row, String key, int max) {
        return MigrationRules.optionalText(row.get(key), max, key);
    }
    private int integer(Map<String, String> row, String key, int min, int max) {
        try {
            int value = Integer.parseInt(text(row, key, 10));
            if (value < min || value > max) conflict("DMT-PREFLIGHT-001: " + key + " 超出范围");
            return value;
        } catch (NumberFormatException exception) { conflict("DMT-PREFLIGHT-001: " + key + " 不是整数"); return 0; }
    }
    private long positiveLong(Map<String, String> row, String key) {
        try {
            long value = Long.parseLong(text(row, key, 20));
            if (value <= 0) conflict("DMT-PREFLIGHT-002: " + key + " 必须为正整数");
            return value;
        } catch (NumberFormatException exception) { conflict("DMT-PREFLIGHT-002: " + key + " 不是整数"); return 0; }
    }
    private List<String> splitBarcodes(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : raw.split("\\|", -1)) {
            String barcode = MigrationRules.text(value, 64, "barcode");
            if (!barcode.matches("^[0-9A-Za-z._-]+$")) conflict("DMT-PREFLIGHT-015: 条码格式无效");
            result.add(barcode);
        }
        return List.copyOf(result);
    }
    private LinkedHashMap<String, Object> mutable(Map<String, Object> input) { return new LinkedHashMap<>(input); }
    private void conflict(String message) { throw new ServiceException(message, 400); }

    /**
     * 规范化后的加密 staging 行。
     * @param rowId Migration Owner 生成的行 ULID
     * @param rowNumber 原文件行号
     * @param canonicalJson 冻结字段的规范 JSON
     * @param rowSha256 规范 JSON 的 SHA-256
     */
    public record NormalizedRow(String rowId, int rowNumber, String canonicalJson, String rowSha256) { }

    /**
     * 完整预检结果；错误不带原始值。
     * @param rows 全部通过预检的规范行
     * @param errors 全量字段级/行级脱敏错误
     */
    public record PreflightResult(List<NormalizedRow> rows, List<PreflightIssue> errors) {
        public PreflightResult {
            rows = rows == null ? List.of() : List.copyOf(rows);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /**
     * 行级脱敏错误。
     * @param rowNumber 原文件行号
     * @param fieldName 字段名
     * @param errorCode 稳定错误码
     * @param maskedMessage 脱敏错误说明
     */
    public record PreflightIssue(int rowNumber, String fieldName, String errorCode, String maskedMessage) { }
}
