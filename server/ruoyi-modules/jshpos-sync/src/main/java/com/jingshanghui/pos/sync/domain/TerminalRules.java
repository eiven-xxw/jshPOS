package com.jingshanghui.pos.sync.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;

/** Gate 6A 终端状态、版本与安全输入规则。 */
public final class TerminalRules {
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern VERSION = Pattern.compile("^[0-9]+(?:\\.[0-9]+){0,3}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_-]{1,63}$");
    private static final Pattern IDEMPOTENCY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{15,63}$");
    private static final Set<String> STATUS = Set.of("ACTIVE", "BLOCKED", "REVOKED", "RETIRED");
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private static final String SUPPORTED_PROTOCOL = "1.0";

    private TerminalRules() { }

    public static String requireSha256(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid(field + " 必须是小写 SHA-256");
        }
        return value;
    }

    public static String requireVersion(String value, String field) {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw invalid(field + " 版本格式无效");
        }
        return value;
    }

    /** 当前服务端只接受已封存的同步协议 1.0，客户端不得自行扩大兼容窗口。 */
    public static String requireSupportedProtocol(String value) {
        requireVersion(value, "protocolVersion");
        if (!SUPPORTED_PROTOCOL.equals(value)) {
            throw new ServiceException("TRM_PROTOCOL_UNSUPPORTED: 当前仅支持协议 1.0", 409);
        }
        return value;
    }

    public static String requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw invalid(field + " 代码格式无效");
        }
        return value;
    }

    public static String requireIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY.matcher(value).matches()) {
            throw invalid("idempotencyKey 格式无效");
        }
        return value;
    }

    public static String requireStatus(String value) {
        if (!STATUS.contains(value)) throw invalid("终端目标状态无效");
        return value;
    }

    public static void requireTransition(String from, String to) {
        boolean allowed = ("ACTIVE".equals(from) && Set.of("BLOCKED", "REVOKED").contains(to))
            || ("BLOCKED".equals(from) && Set.of("ACTIVE", "REVOKED").contains(to))
            || ("REVOKED".equals(from) && "RETIRED".equals(to));
        if (!allowed) throw new ServiceException("TRM_STATE_CONFLICT: 不允许的终端状态迁移", 409);
    }

    /** 按数字段比较版本，避免 1.10 被字符串比较判定小于 1.2。 */
    public static int compareVersion(String left, String right) {
        requireVersion(left, "left");
        requireVersion(right, "right");
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int index = 0; index < Math.max(a.length, b.length); index++) {
            long av = index < a.length ? Long.parseLong(a[index]) : 0;
            long bv = index < b.length ? Long.parseLong(b[index]) : 0;
            if (av != bv) return Long.compare(av, bv);
        }
        return 0;
    }

    public static long clockSkewSeconds(Instant clientTime, Instant serverTime) {
        if (clientTime == null) throw invalid("clientTime 不能为空");
        return Duration.between(serverTime, clientTime).getSeconds();
    }

    public static void requireClockSkew(long seconds) {
        if (Math.abs(seconds) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ServiceException("TRM_CLOCK_SKEW: 终端时钟偏移超过 300 秒", 409);
        }
    }

    public static String requireReason(String reason) {
        if (reason == null || reason.strip().length() < 4 || reason.strip().length() > 256) {
            throw invalid("原因长度必须为 4 至 256 字符");
        }
        return reason.strip();
    }

    private static ServiceException invalid(String message) {
        return new ServiceException("TRM_INPUT_INVALID: " + message, 400);
    }
}
