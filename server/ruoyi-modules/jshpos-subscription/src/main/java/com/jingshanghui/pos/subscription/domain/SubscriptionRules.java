package com.jingshanghui.pos.subscription.domain;

import org.dromara.common.core.exception.ServiceException;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 订阅期限、外部引用、幂等和降级策略的不变量。 */
public final class SubscriptionRules {
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,64}$");
    private static final Pattern REF = Pattern.compile("^[A-Za-z0-9._:/-]{1,128}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    public static final String RECOVERY_POLICY_V1 = "RECOVERY-V1";
    public static final Set<String> ACCESS_MODES = Set.of("NORMAL", "GRACE", "RECOVERY_ONLY", "TERMINATED_RECOVERY");

    private SubscriptionRules() { }

    public static void term(Instant startsAt, Instant endsAt, Instant graceEndsAt) {
        if (startsAt == null || endsAt == null || graceEndsAt == null || !endsAt.isAfter(startsAt)
            || graceEndsAt.isBefore(endsAt)) throw error("SUB-TERM-001", "订阅期限或宽限窗口非法");
    }

    /** 首次激活或恢复时，服务端权威时间必须位于新期限内，防止提前授权或激活过期合同。 */
    public static void effectiveAt(Instant startsAt, Instant endsAt, Instant serverNow) {
        if (startsAt == null || endsAt == null || serverNow == null
            || serverNow.isBefore(startsAt) || !serverNow.isBefore(endsAt)) {
            throw error("SUB-TERM-003", "订阅期限在服务端当前时间尚未生效或已经结束");
        }
    }

    /** 正常续期只能延长到期时间且不得在当前期限之后留下空档。 */
    public static void renewalWindow(Instant currentEndsAt, Instant newStartsAt, Instant newEndsAt) {
        if (currentEndsAt == null || newStartsAt == null || newEndsAt == null
            || newStartsAt.isAfter(currentEndsAt) || !newEndsAt.isAfter(currentEndsAt)) {
            throw error("SUB-TERM-004", "续期期限必须连续并延长原到期时间");
        }
    }

    public static String zone(String value) {
        String zone = required(value, "businessTimeZone");
        try { ZoneId.of(zone); return zone; }
        catch (Exception ex) { throw error("SUB-TERM-002", "业务时区不是有效 IANA 标识"); }
    }

    public static String reference(String value, String field) {
        String ref = required(value, field);
        if (!REF.matcher(ref).matches()) throw error("SUB-REF-001", field + " 格式非法");
        return ref;
    }

    public static String key(String value) {
        String key = required(value, "idempotencyKey");
        if (!KEY.matcher(key).matches()) throw error("SUB-IDEMP-001", "幂等键格式非法");
        return key;
    }

    public static String hash(String value) {
        String hash = required(value, "sha256").toLowerCase(Locale.ROOT);
        if (!HASH.matcher(hash).matches()) throw error("SUB-HASH-001", "SHA-256 格式非法");
        return hash;
    }

    /** 商业 V1 只接受已经冻结并由 SaaS Owner 实际执行的恢复白名单版本。 */
    public static String degradationPolicy(String value) {
        String policy = required(value, "degradationPolicyVersion").toUpperCase(Locale.ROOT);
        if (!RECOVERY_POLICY_V1.equals(policy)) {
            throw error("SUB-ACCESS-002", "不支持的受控降级策略版本");
        }
        return policy;
    }

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) throw error("SUB-VALID-001", field + " 不能为空");
        return value.strip();
    }

    public static void sameHash(String expected, String actual) {
        if (!hash(expected).equals(hash(actual))) throw error("SUB-IDEMP-002", "同幂等键内容不一致");
    }

    public static String accessModeFor(String state) {
        return switch (SubscriptionStates.parse(state)) {
            case ACTIVE -> "NORMAL";
            case GRACE_PERIOD -> "GRACE";
            case SUSPENDED, EXPIRED -> "RECOVERY_ONLY";
            case TERMINATED -> "TERMINATED_RECOVERY";
            default -> throw error("SUB-ACCESS-001", "当前状态不能形成访问模式");
        };
    }

    private static ServiceException error(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
