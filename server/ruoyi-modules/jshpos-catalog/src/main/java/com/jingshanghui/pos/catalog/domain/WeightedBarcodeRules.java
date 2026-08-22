package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 秤码与金额码的纯领域规则。
 *
 * <p>原始条码始终按字符串处理；所有位置均为 1 基，EAN-13 最后一位为校验位。
 * 解析结果同时冻结模板、售价、数量和金额，成交后不得按新模板或新售价重算。</p>
 */
public final class WeightedBarcodeRules {

    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    private WeightedBarcodeRules() {
    }

    /** 校验模板形状，防止歧义、越界和精度降级进入发布路径。 */
    public static Template requireTemplate(Template template) {
        if (template == null || template.templateId() == null || template.templateId() <= 0
            || template.versionNo() <= 0 || template.templateCode() == null || template.templateCode().isBlank()) {
            throw bad("CAT-WBC-001: 模板身份或版本无效");
        }
        if (!"EAN13".equals(template.symbology()) || template.totalLength() != 13) {
            throw bad("CAT-WBC-002: 商业 V1 仅允许 EAN-13");
        }
        if (template.prefix() == null || !template.prefix().matches("[0-9]{2,5}")) {
            throw bad("CAT-WBC-003: 前缀必须是保留前导零的 2 至 5 位数字");
        }
        if (!"WEIGHT".equals(template.kind()) && !"AMOUNT".equals(template.kind())) {
            throw bad("CAT-WBC-004: 码类型必须为 WEIGHT 或 AMOUNT");
        }
        if (!("TENANT".equals(template.scopeType()) && template.storeId() == null)
            && !("STORE".equals(template.scopeType()) && template.storeId() != null && template.storeId() > 0)) {
            throw bad("CAT-WBC-017: 模板适用范围无效");
        }
        requireSegment(template.skuStart(), template.skuLength(), "SKU");
        requireSegment(template.valueStart(), template.valueLength(), "计量值");
        int skuEnd = template.skuStart() + template.skuLength() - 1;
        int valueEnd = template.valueStart() + template.valueLength() - 1;
        if (template.skuStart() <= template.prefix().length() || template.valueStart() <= template.prefix().length()
            || !(skuEnd < template.valueStart() || valueEnd < template.skuStart())) {
            throw bad("CAT-WBC-005: 前缀、SKU 和计量字段不得重叠");
        }
        if (template.valueScale() < 0 || template.valueScale() > 6 || template.effectiveFrom() == null
            || template.effectiveTo() != null && !template.effectiveTo().isAfter(template.effectiveFrom())) {
            throw bad("CAT-WBC-006: 精度或生效窗口无效");
        }
        if ("AMOUNT".equals(template.kind()) && template.valueScale() != 2) {
            throw bad("CAT-WBC-019: 金额码必须按分使用两位小数精度");
        }
        return template;
    }

    /** 计算 EAN-13 校验位；输入必须为前 12 位数字。 */
    public static int checkDigit(String firstTwelveDigits) {
        if (firstTwelveDigits == null || !firstTwelveDigits.matches("[0-9]{12}")) {
            throw bad("CAT-WBC-007: EAN-13 校验输入必须为 12 位数字");
        }
        int sum = 0;
        for (int index = 0; index < firstTwelveDigits.length(); index++) {
            int digit = firstTwelveDigits.charAt(index) - '0';
            sum += (index % 2 == 0) ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    /**
     * 使用已发布模板和已冻结单位售价解析扫码输入。
     *
     * @param unitPriceMinor 基础计量单位的最小货币单位售价
     * @param unitDecimalScale 商品基础单位允许的小数位数
     */
    public static ParsedMeasurement parse(Template rawTemplate, String rawBarcode, long unitPriceMinor,
                                           int unitDecimalScale, Instant occurredAt) {
        Template template = requireTemplate(rawTemplate);
        if (rawBarcode == null || !rawBarcode.matches("[0-9]{13}")) {
            throw bad("CAT-WBC-008: 原始条码必须是 13 位数字字符串");
        }
        if (!rawBarcode.startsWith(template.prefix())) {
            throw bad("CAT-WBC-009: 条码与模板前缀不匹配");
        }
        if (checkDigit(rawBarcode.substring(0, 12)) != rawBarcode.charAt(12) - '0') {
            throw bad("CAT-WBC-010: EAN-13 校验位错误");
        }
        if (unitPriceMinor <= 0 || unitPriceMinor > MAX_SAFE_JSON_INTEGER
            || unitDecimalScale < 0 || unitDecimalScale > 6 || occurredAt == null) {
            throw bad("CAT-WBC-011: 冻结售价、单位精度或解析时间无效");
        }
        if (!template.activeAt(occurredAt)) {
            throw bad("CAT-WBC-018: 模板在解析时间尚未生效或已经过期");
        }
        String skuCode = segment(rawBarcode, template.skuStart(), template.skuLength());
        String encodedValue = segment(rawBarcode, template.valueStart(), template.valueLength());
        BigDecimal encoded = new BigDecimal(encodedValue);
        BigDecimal quantity;
        long amountMinor;
        boolean roundingApplied;
        if ("WEIGHT".equals(template.kind())) {
            BigDecimal rawQuantity = encoded.movePointLeft(template.valueScale());
            try {
                quantity = rawQuantity.setScale(unitDecimalScale, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException exception) {
                throw bad("CAT-WBC-012: 重量精度超过商品单位允许范围");
            }
            BigDecimal exactAmount = quantity.multiply(BigDecimal.valueOf(unitPriceMinor));
            BigDecimal roundedAmount = exactAmount.setScale(0, RoundingMode.HALF_EVEN);
            amountMinor = requireMinorAmount(roundedAmount);
            roundingApplied = exactAmount.compareTo(roundedAmount) != 0;
        } else {
            BigDecimal exactMinor = encoded.movePointLeft(template.valueScale()).movePointRight(2);
            BigDecimal roundedMinor = exactMinor.setScale(0, RoundingMode.HALF_EVEN);
            amountMinor = requireMinorAmount(roundedMinor);
            roundingApplied = exactMinor.compareTo(roundedMinor) != 0;
            quantity = BigDecimal.valueOf(amountMinor)
                .divide(BigDecimal.valueOf(unitPriceMinor), unitDecimalScale, RoundingMode.HALF_EVEN);
            roundingApplied = roundingApplied
                || quantity.multiply(BigDecimal.valueOf(unitPriceMinor)).compareTo(BigDecimal.valueOf(amountMinor)) != 0;
        }
        if (quantity.signum() <= 0 || amountMinor <= 0) {
            throw bad("CAT-WBC-013: 计量数量和金额必须为正数");
        }
        quantity = quantity.stripTrailingZeros();
        String canonical = rawBarcode + '|' + template.templateId() + '|' + template.versionNo() + '|'
            + requireHash(template.contentSha256()) + '|' + skuCode + '|' + quantity.toPlainString() + '|'
            + amountMinor + '|' + unitPriceMinor + "|CNY|" + occurredAt;
        return new ParsedMeasurement(rawBarcode, skuCode, encodedValue, quantity, amountMinor, unitPriceMinor,
            "CNY", template.templateId(), template.versionNo(), template.contentSha256(), sha256(canonical),
            roundingApplied, occurredAt);
    }

    /** 发布摘要采用固定字段顺序，避免 JSON 序列化差异改变模板身份。 */
    public static String contentSha256(Template template) {
        Template value = requireTemplate(template);
        return sha256(value.templateCode() + '|' + value.versionNo() + '|' + value.scopeType() + '|'
            + (value.storeId() == null ? "" : value.storeId()) + '|' + value.kind() + '|' + value.symbology()
            + '|' + value.prefix() + '|' + value.totalLength() + '|' + value.skuStart() + '|'
            + value.skuLength() + '|' + value.valueStart() + '|' + value.valueLength() + '|'
            + value.valueScale() + '|' + value.priority() + '|' + value.effectiveFrom() + '|'
            + (value.effectiveTo() == null ? "" : value.effectiveTo()));
    }

    private static void requireSegment(int start, int length, String name) {
        if (start < 1 || length < 1 || length > 8 || start + length - 1 > 12) {
            throw bad("CAT-WBC-014: " + name + " 字段位置越界");
        }
    }

    private static String segment(String barcode, int start, int length) {
        return barcode.substring(start - 1, start - 1 + length);
    }

    private static long requireMinorAmount(BigDecimal value) {
        try {
            long result = value.longValueExact();
            if (result > MAX_SAFE_JSON_INTEGER) {
                throw bad("CAT-WBC-015: 金额溢出");
            }
            return result;
        } catch (ArithmeticException exception) {
            throw bad("CAT-WBC-015: 金额溢出");
        }
    }

    private static String requireHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) {
            throw bad("CAT-WBC-016: 已发布模板摘要无效");
        }
        return hash;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ServiceException bad(String message) {
        return new ServiceException(message, 400);
    }

    /** 已发布模板的确定性领域视图。 */
    public record Template(Long templateId, String templateCode, int versionNo, String scopeType, Long storeId,
                           String kind, String symbology, String prefix, int totalLength, int skuStart,
                           int skuLength, int valueStart, int valueLength, int valueScale, int priority,
                           Instant effectiveFrom, Instant effectiveTo, String contentSha256) {
        public boolean activeAt(Instant at) {
            return at != null && !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
        }
    }

    /** 成交必须原样冻结的计量解析快照。 */
    public record ParsedMeasurement(String rawBarcode, String skuCode, String encodedValue,
                                    BigDecimal quantity, long amountMinor, long unitPriceMinor, String currency,
                                    Long templateId, int templateVersion, String templateSha256,
                                    String parseSha256, boolean roundingApplied, Instant occurredAt) {
    }
}
