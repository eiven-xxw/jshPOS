package com.jingshanghui.pos.foundation.domain;

import org.dromara.common.core.exception.ServiceException;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gate 0 稳定枚举与输入不变量。
 */
public final class FoundationRules {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");
    private static final Pattern SCHEMA_VERSION = Pattern.compile("^[1-9][0-9]*\\.[0-9]+$");
    public static final Set<String> ORG_TYPES = Set.of("HEADQUARTERS", "REGION", "COMPANY", "OTHER");
    public static final Set<String> ACTIVE_STATUS = Set.of("ACTIVE", "INACTIVE");
    public static final Set<String> STORE_STATUS = Set.of("PREPARING", "ACTIVE", "INACTIVE");
    public static final Set<String> INDUSTRIES = Set.of("CONVENIENCE", "SNACK_DISCOUNT", "COMMUNITY_SUPERMARKET");
    public static final Set<String> SCOPE_TYPES = Set.of("TENANT", "ORG_SUBTREE", "STORE");
    public static final Set<String> TARGET_TYPES = Set.of("TENANT", "STORE");

    private FoundationRules() {
    }

    public static String requireCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new ServiceException("FND-VAL-001: 编码格式无效", 400);
        }
        return normalized;
    }

    public static String requireName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new ServiceException("FND-VAL-002: 名称长度必须为 1-100", 400);
        }
        return normalized;
    }

    public static String requireEnum(String value, Set<String> allowed, String errorCode) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new ServiceException(errorCode + ": 枚举值无效", 400);
        }
        return normalized;
    }

    public static String requireSchemaVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SCHEMA_VERSION.matcher(normalized).matches()) {
            throw new ServiceException("FND-CFG-003: Schema 版本格式无效", 400);
        }
        return normalized;
    }
}
