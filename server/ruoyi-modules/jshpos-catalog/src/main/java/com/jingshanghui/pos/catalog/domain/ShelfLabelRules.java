package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 货架价签模板安全、状态机和乱序收敛规则。 */
public final class ShelfLabelRules {

    public static final int MAX_TEMPLATE_LENGTH = 2_000;
    public static final int MAX_RENDERED_LENGTH = 8_000;
    public static final Set<String> ALLOWED_FIELDS = Set.of(
        "productName", "skuCode", "barcode", "unitName", "oldPrice", "newPrice",
        "storeName", "priceVersion", "effectiveAt", "taskStatus", "exceptionReason"
    );
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN = Pattern.compile(
        "(?is)<\\s*/?\\s*(script|iframe|object|embed|style)|javascript\\s*:|(?:^|[/\\\\])\\.\\.(?:[/\\\\]|$)|\\$\\{|\\{\\{[^{}]*\\{"
    );
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");

    private ShelfLabelRules() {
    }

    /** 校验并规范化纯文本模板，未知占位符和注入载荷一律失败关闭。 */
    public static String requireSafeTemplate(String value) {
        String normalized = value == null ? "" : value.replace("\r\n", "\n");
        if (CONTROL.matcher(normalized).find()) {
            throw new ServiceException("LBL-TPL-002: 价签模板包含禁止的脚本、路径、公式或控制字符", 400);
        }
        String template = normalized.trim();
        if (template.isEmpty() || template.length() > MAX_TEMPLATE_LENGTH) {
            throw new ServiceException("LBL-TPL-001: 价签模板为空或超过长度上限", 400);
        }
        if (FORBIDDEN.matcher(template).find() || startsWithFormula(template)) {
            throw new ServiceException("LBL-TPL-002: 价签模板包含禁止的脚本、路径、公式或控制字符", 400);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            if (!ALLOWED_FIELDS.contains(matcher.group(1))) {
                throw new ServiceException("LBL-TPL-003: 价签模板包含未批准字段", 400);
            }
        }
        String residue = PLACEHOLDER.matcher(template).replaceAll("");
        if (residue.contains("{{") || residue.contains("}}") || !found) {
            throw new ServiceException("LBL-TPL-004: 价签模板占位符不完整或缺失", 400);
        }
        return template;
    }

    /** 使用固定字段白名单渲染纯文本；值中的控制字符会被空格替换。 */
    public static String render(String template, Map<String, String> rawValues) {
        String safeTemplate = requireSafeTemplate(template);
        Map<String, String> values = new LinkedHashMap<>();
        ALLOWED_FIELDS.forEach(field -> values.put(field, sanitizeValue(rawValues.get(field))));
        Matcher matcher = PLACEHOLDER.matcher(safeTemplate);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(values.get(matcher.group(1))));
        }
        matcher.appendTail(result);
        if (result.length() > MAX_RENDERED_LENGTH) {
            throw new ServiceException("LBL-PRV-001: 价签预览超过长度上限", 400);
        }
        return result.toString();
    }

    /** 校验受控任务项状态迁移，不允许软件预览伪装为打印成功。 */
    public static void requireItemTransition(String from, String to) {
        boolean allowed = switch (from) {
            case "PENDING" -> Set.of("PREVIEW_READY", "EXCEPTION", "SUPERSEDED").contains(to);
            case "PREVIEW_READY" -> Set.of("REPLACED_CONFIRMED", "EXCEPTION", "SUPERSEDED").contains(to);
            case "EXCEPTION" -> Set.of("PREVIEW_READY", "SUPERSEDED").contains(to);
            default -> false;
        };
        if (!allowed) {
            throw new ServiceException("LBL-STATE-001: 非法价签任务项状态迁移", 409);
        }
    }

    /** 判断一个来源版本是否比现有未完成来源更新。 */
    public static boolean isNewer(Instant incomingEffectiveAt, int incomingScopePriority, int incomingVersion,
                                  long incomingBookId, Instant currentEffectiveAt, int currentScopePriority,
                                  int currentVersion, long currentBookId) {
        int byTime = incomingEffectiveAt.compareTo(currentEffectiveAt);
        if (byTime != 0) return byTime > 0;
        if (incomingScopePriority != currentScopePriority) return incomingScopePriority > currentScopePriority;
        if (incomingVersion != currentVersion) return incomingVersion > currentVersion;
        return incomingBookId > currentBookId;
    }

    /** 生成用于幂等和证据索引的 SHA-256 小写十六进制摘要。 */
    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sanitizeValue(String value) {
        String normalized = value == null ? "" : CONTROL.matcher(value).replaceAll(" ");
        return normalized.length() <= 512 ? normalized : normalized.substring(0, 512);
    }

    private static boolean startsWithFormula(String value) {
        for (String line : value.split("\\n", -1)) {
            String stripped = line.stripLeading();
            if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) return true;
        }
        return false;
    }
}
