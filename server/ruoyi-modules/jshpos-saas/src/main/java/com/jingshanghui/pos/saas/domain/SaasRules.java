package com.jingshanghui.pos.saas.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** SaaS 开户与权益领域不变量。 */
public final class SaasRules {
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    public static final Set<String> RECOVERY_FEATURES = Set.of("REFUND", "PAYMENT_AND_REFUND_QUERY",
        "RECONCILIATION", "AUDIT", "BACKUP_RESTORE", "LEGAL_EXPORT", "DATA_MIGRATION",
        "DATA_DELETION_REQUEST");

    private SaasRules() { }

    public static String code(String value, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw error("SAA-VALID-001", field + " 格式非法");
        return normalized;
    }

    public static String key(String value) {
        String normalized = required(value, "idempotencyKey");
        if (!KEY.matcher(normalized).matches()) throw error("SAA-IDEMP-001", "幂等键格式非法");
        return normalized;
    }

    public static String hash(String value) {
        String normalized = required(value, "sha256").toLowerCase(Locale.ROOT);
        if (!HASH.matcher(normalized).matches()) throw error("SAA-HASH-001", "SHA-256 格式非法");
        return normalized;
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) throw error("SAA-VALID-002", field + " 不能为空");
        return value.strip();
    }

    public static void window(Instant effectiveAt, Instant expiresAt) {
        if (effectiveAt == null || (expiresAt != null && !expiresAt.isAfter(effectiveAt))) {
            throw error("SAA-ENT-001", "权益生效窗口非法");
        }
    }

    public static void separate(long submitter, long approver) {
        if (submitter == approver) throw error("SAA-APPROVAL-001", "提交人与审批人必须分离");
    }

    public static void sameHash(String expected, String actual) {
        if (!hash(expected).equals(hash(actual))) throw error("SAA-IDEMP-002", "同幂等键请求内容不一致");
    }

    public static void positive(long value, String field) {
        if (value <= 0) throw error("SAA-VALID-003", field + " 必须大于零");
    }

    public static boolean featureAllowed(String lifecycle, boolean enabled, String featureCode) {
        String code = code(featureCode, "featureCode");
        if ("ACTIVE".equals(lifecycle)) return enabled;
        return RECOVERY_FEATURES.contains(code);
    }

    private static ServiceException error(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
