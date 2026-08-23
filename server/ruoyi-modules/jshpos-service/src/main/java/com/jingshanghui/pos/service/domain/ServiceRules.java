package com.jingshanghui.pos.service.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 服务目录、租约、职责分离、附件和内部时间目标的不变量集合。
 */
public final class ServiceRules {
    public static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    public static final Duration DOWNLOAD_TTL = Duration.ofMinutes(5);
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    private static final Set<String> MEDIA = Set.of("application/pdf", "image/png", "image/jpeg", "text/plain", "text/csv");

    private ServiceRules() { }

    public static String code(String value, String field) {
        String normalized = required(value, field).toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) throw bad("SVC-VAL-001", field + " 格式非法");
        return normalized;
    }

    public static String idempotencyKey(String value) {
        String normalized = required(value, "idempotencyKey");
        if (!KEY.matcher(normalized).matches()) throw bad("SVC-IDEM-002", "幂等键格式非法");
        return normalized;
    }

    public static String text(String value, String field, int max) {
        String normalized = required(value, field).trim();
        if (normalized.length() > max || normalized.startsWith("=") || normalized.startsWith("+")
            || normalized.startsWith("-") || normalized.startsWith("@")) throw bad("SVC-VAL-002", field + " 内容非法");
        return normalized;
    }

    public static int targetMinutes(Integer value) {
        if (value == null || value < 1 || value > 525600) throw bad("SVC-SLA-001", "内部目标分钟数非法");
        return value;
    }

    public static int leaseMinutes(Integer value) {
        if (value == null || value < 1 || value > 1440) throw bad("SVC-LEASE-002", "租约分钟数非法");
        return value;
    }

    public static void requireActiveLease(Long assignee, LocalDateTime leaseUntil, Long actor, LocalDateTime now) {
        if (assignee == null || !assignee.equals(actor) || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new ServiceException("SVC-LEASE-001: 认领租约缺失、过期或不属于当前处理人", 409);
        }
    }

    public static void requireIndependentReviewer(Long resolver, Long reviewer) {
        if (resolver == null || reviewer == null || resolver.equals(reviewer)) {
            throw new ServiceException("SVC-SOD-001: 解决人与关闭复核人必须分离", 409);
        }
    }

    public static String safeFileName(String value) {
        String name = text(value, "fileName", 200);
        if (name.contains("..") || name.chars().anyMatch(ch -> ch < 32)) {
            throw bad("SVC-ATT-001", "附件文件名非法");
        }
        try {
            if (!Path.of(name).getFileName().toString().equals(name)) throw bad("SVC-ATT-001", "附件文件名非法");
        } catch (RuntimeException exception) {
            if (exception instanceof ServiceException serviceException) throw serviceException;
            throw bad("SVC-ATT-001", "附件文件名非法");
        }
        return name;
    }

    public static String mediaType(String value) {
        String normalized = required(value, "mediaType").toLowerCase(Locale.ROOT);
        if (!MEDIA.contains(normalized)) throw bad("SVC-ATT-001", "附件媒体类型不允许");
        return normalized;
    }

    public static long attachmentSize(long value) {
        if (value < 1 || value > MAX_ATTACHMENT_BYTES) throw bad("SVC-ATT-001", "附件大小超限");
        return value;
    }

    public static String sha256(String value) {
        if (value == null || !HASH.matcher(value).matches()) throw bad("SVC-ATT-001", "附件摘要非法");
        return value;
    }

    public static String objectKey(String tenantId, String ticketId, String attachmentId) {
        return "service/" + required(tenantId, "tenantId") + "/tickets/" + required(ticketId, "ticketId")
            + "/attachments/" + required(attachmentId, "attachmentId");
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) throw bad("SVC-VAL-003", field + " 不能为空");
        return value;
    }

    private static ServiceException bad(String code, String message) { return new ServiceException(code + ": " + message, 400); }
}
