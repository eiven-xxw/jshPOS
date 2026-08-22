package com.jingshanghui.pos.migration.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 文件、标识、文本、数量与安全输入的确定性规则。 */
public final class MigrationRules {
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_ROWS = 100_000;
    public static final int MAX_COLUMNS = 200;
    public static final int MAX_CELL_CHARS = 4_096;
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SHA = "^[a-f0-9]{64}$";

    private MigrationRules() {
    }

    public static String ulid(String value, String field) {
        if (value == null || !value.matches(ULID)) throw fail("DMT-ID-001: " + field + " 必须为规范 ULID");
        return value;
    }

    public static String sha256(String value, String field) {
        if (value == null || !value.matches(SHA)) throw fail("DMT-HASH-001: " + field + " 必须为小写 SHA-256");
        return value;
    }

    public static String text(String value, int max, String field) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > max || result.indexOf('\0') >= 0) {
            throw fail("DMT-INPUT-001: " + field + " 为空或超限");
        }
        return result;
    }

    public static String optionalText(String value, int max, String field) {
        if (value == null || value.isBlank()) return null;
        return text(value, max, field);
    }

    public static BigDecimal quantity(String value, String field) {
        try {
            BigDecimal result = new BigDecimal(text(value, 32, field));
            if (result.signum() < 0 || result.scale() > 6 || result.precision() - result.scale() > 13) {
                throw fail("DMT-QTY-001: " + field + " 超出 DECIMAL(19,6) 或为负");
            }
            return result.setScale(6, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException exception) {
            throw fail("DMT-QTY-001: " + field + " 不是精确数量");
        }
    }

    public static BigDecimal nonNegativeCost(String value) {
        try {
            BigDecimal result = new BigDecimal(text(value, 40, "unitCostMinor"));
            if (result.signum() < 0 || result.scale() > 6 || result.precision() - result.scale() > 19) {
                throw fail("DMT-COST-001: 期初单位成本超出 DECIMAL(25,6) 或为负");
            }
            return result.setScale(6, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException exception) {
            throw fail("DMT-COST-001: 期初单位成本不是精确数值");
        }
    }

    public static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static void rejectFormula(String value, String field) {
        String stripped = value == null ? "" : value.stripLeading();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) {
            throw fail("DMT-FILE-009: " + field + " 包含公式或公式注入前缀");
        }
    }

    private static ServiceException fail(String message) {
        return new ServiceException(message, 400);
    }
}
