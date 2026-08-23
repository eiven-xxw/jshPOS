package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDate;
import java.util.Set;
import java.util.regex.Pattern;

/** 异常来源、租约、幂等、摘要与职责分离不变量。 */
public final class ExceptionRules {
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");
    private static final Set<String> OWNERS = Set.of("SYNC", "DATA_PACKAGE", "PAYMENT_REFUND", "INVENTORY",
        "COSTING", "REPORTING", "DAILY_CLOSE");
    private static final Set<String> SEVERITIES = Set.of("P0", "P1", "P2", "P3");

    private ExceptionRules() { }

    public static Long store(Long value) {
        if (value == null || value <= 0) throw new ServiceException("OPS-EXC-INPUT-001: 门店无效", 400);
        return value;
    }

    public static LocalDate date(LocalDate value) {
        if (value == null) throw new ServiceException("OPS-EXC-INPUT-002: 业务日不能为空", 400);
        return value;
    }

    public static String safe(String value, String code) {
        if (value == null || !SAFE.matcher(value).matches()) throw new ServiceException(code + ": 标识无效", 400);
        return value;
    }

    public static String hash(String value) {
        if (value == null || !HASH.matcher(value).matches()) throw new ServiceException("OPS-EXC-HASH-001: 摘要无效", 409);
        return value;
    }

    public static String owner(String value) {
        if (!OWNERS.contains(value)) throw new ServiceException("OPS-EXC-OWNER-001: 来源Owner不受支持", 409);
        return value;
    }

    public static String severity(String value) {
        if (!SEVERITIES.contains(value)) throw new ServiceException("OPS-EXC-SEVERITY-001: 严重级别无效", 409);
        return value;
    }

    public static int leaseMinutes(int value) {
        if (value < 5 || value > 120) throw new ServiceException("OPS-EXC-LEASE-001: 租约必须为5至120分钟", 400);
        return value;
    }

    public static int limit(int value) { return Math.max(1, Math.min(value, 100)); }

    public static String reason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 8 || normalized.length() > 256) {
            throw new ServiceException("OPS-EXC-REASON-001: 原因长度必须为8至256字符", 400);
        }
        return normalized;
    }

    public static void different(Long first, Long second, String action) {
        if (first != null && first.equals(second)) throw new ServiceException("OPS-EXC-SEPARATION-001: " + action + "必须职责分离", 403);
    }
}
