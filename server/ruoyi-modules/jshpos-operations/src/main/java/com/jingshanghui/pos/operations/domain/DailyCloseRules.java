package com.jingshanghui.pos.operations.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/** 日结输入、金额、幂等和职责分离不变量。 */
public final class DailyCloseRules {
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final Pattern HASH = Pattern.compile("^[a-f0-9]{64}$");

    private DailyCloseRules() {
    }

    public static Long store(Long value) {
        if (value == null || value <= 0) throw new ServiceException("OPS-INPUT-001: 门店无效", 400);
        return value;
    }

    public static LocalDate date(LocalDate value) {
        if (value == null) throw new ServiceException("OPS-INPUT-002: 业务日不能为空", 400);
        return value;
    }

    public static String key(String value) {
        if (value == null || value.length() < 8 || !SAFE.matcher(value).matches()) {
            throw new ServiceException("OPS-IDEMPOTENCY-002: 幂等键无效", 400);
        }
        return value;
    }

    public static String correlation(String value) {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new ServiceException("OPS-TRACE-001: 关联标识无效", 400);
        }
        return value;
    }

    public static String reason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 8 || normalized.length() > 256) {
            throw new ServiceException("OPS-REASON-001: 原因长度必须为8至256字符", 400);
        }
        return normalized;
    }

    public static String hash(String value) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new ServiceException("OPS-HASH-001: 摘要无效", 409);
        }
        return value;
    }

    public static void requireSameHash(String stored, String request) {
        if (!Objects.equals(stored, request)) {
            throw new ServiceException("OPS-IDEMPOTENCY-001: 同幂等键异内容", 409);
        }
    }

    public static void makerChecker(Long creator, Long actor, String action) {
        if (creator != null && creator.equals(actor)) {
            throw new ServiceException("OPS-SEPARATION-001: 创建人不得" + action, 403);
        }
    }

    public static void money(long gross, long discount, long surcharge, long receivable) {
        if (gross < 0 || discount < 0 || surcharge < 0 || receivable < 0
            || gross - discount + surcharge != receivable) {
            throw new ServiceException("OPS-MONEY-001: 日结金额不守恒", 409);
        }
    }
}
