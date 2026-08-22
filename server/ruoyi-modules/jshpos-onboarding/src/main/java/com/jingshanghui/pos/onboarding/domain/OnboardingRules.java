package com.jingshanghui.pos.onboarding.domain;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import org.dromara.common.core.exception.ServiceException;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 门店开通标识、文本、幂等键、摘要和白名单不变量。 */
public final class OnboardingRules {
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]+$");
    public static final Set<String> INTERNAL_CHECKS = Set.of("STORE_ORG", "BUSINESS_TIME", "STAFF_SCOPE",
        "CONFIG_TEMPLATE", "CATALOG_PRICE", "DATA_PACKAGE", "INVENTORY_POLICY", "CASH_SHIFT_CLEAR",
        "DMT_RECONCILED", "BACKUP_RECOVERY");
    public static final Set<String> EXTERNAL_CHECKS = Set.of("PAYMENT_EXTERNAL", "HARDWARE_EXTERNAL",
        "PRINT_EXTERNAL", "DESIGN_PARTNER_EXTERNAL");

    private OnboardingRules() {
    }

    public static String ulid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) throw input(field + " 必须是 ULID");
        return value;
    }

    public static String hash(String value, String field) {
        if (value == null || !HASH.matcher(value).matches()) throw input(field + " 必须是 SHA-256");
        return value;
    }

    public static String key(String value) {
        String result = safe(value, 8, 64, "idempotencyKey");
        if (!SAFE.matcher(result).matches()) throw input("idempotencyKey 包含非法字符");
        return result;
    }

    public static String correlation(String value) {
        String result = safe(value, 1, 64, "correlationId");
        if (!SAFE.matcher(result).matches()) throw input("correlationId 包含非法字符");
        return result;
    }

    public static String reason(String value) {
        String result = safe(value, 2, 200, "reason");
        if (result.indexOf('\n') >= 0 || result.indexOf('\r') >= 0) throw input("reason 不允许换行");
        return result;
    }

    public static long positive(Long value, String field) {
        if (value == null || value <= 0) throw input(field + " 必须为正数");
        return value;
    }

    public static String requestHash(Map<String, Object> value) {
        return CanonicalJson.from(value).sha256();
    }

    public static void requireSameHash(String expected, String actual) {
        if (!hash(expected, "expectedHash").equals(hash(actual, "actualHash"))) {
            throw new ServiceException("ONB-IDEMPOTENCY-001: 同一幂等键请求内容不同", 409);
        }
    }

    private static String safe(String value, int min, int max, String field) {
        String result = value == null ? "" : value.strip();
        if (result.length() < min || result.length() > max) throw input(field + " 长度非法");
        return result;
    }

    private static ServiceException input(String message) {
        return new ServiceException("ONB-INPUT-001: " + message, 400);
    }
}
