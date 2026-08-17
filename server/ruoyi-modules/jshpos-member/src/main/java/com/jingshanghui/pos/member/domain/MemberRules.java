package com.jingshanghui.pos.member.domain;

import org.dromara.common.core.exception.ServiceException;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;

/** 会员输入、身份类型和脱敏值的领域规则。 */
public final class MemberRules {
    public static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern MOBILE = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern EXTERNAL = Pattern.compile("^[A-Za-z0-9._:@/+\\-=]{1,128}$");
    private static final Set<String> TYPES = Set.of("MOBILE", "MEMBER_CODE", "CARD", "EXTERNAL_OPEN_ID");

    private MemberRules() { }

    /** 统一身份输入，手机号只能采用 E.164 形式，禁止把明文作为数据库主键。 */
    public static String normalizeIdentity(String type, String rawValue) {
        if (!TYPES.contains(type) || rawValue == null) {
            throw new ServiceException("MEM-IDENTITY-001: 身份类型或值无效", 400);
        }
        String normalized = Normalizer.normalize(rawValue.trim(), Normalizer.Form.NFKC);
        Pattern rule = "MOBILE".equals(type) ? MOBILE : EXTERNAL;
        if (!rule.matcher(normalized).matches()) {
            throw new ServiceException("MEM-IDENTITY-001: 身份类型或值无效", 400);
        }
        return normalized;
    }

    /** 生成仅用于界面展示的掩码，不返回完整身份。 */
    public static String mask(String type, String normalized) {
        if ("MOBILE".equals(type) && normalized.length() >= 8) {
            return normalized.substring(0, 3) + "****" + normalized.substring(normalized.length() - 4);
        }
        int length = normalized.length();
        if (length <= 4) return "****";
        return normalized.substring(0, 2) + "****" + normalized.substring(length - 2);
    }

    public static void requireUlid(String value, String field) {
        if (value == null || !ULID.matcher(value).matches()) {
            throw new ServiceException("MEM-INPUT-001: " + field + "ULID无效", 400);
        }
    }

    public static String requireReason(String value) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new ServiceException("MEM-INPUT-002: 原因无效", 400);
        }
        return value.trim();
    }
}
