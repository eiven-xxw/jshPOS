package com.jingshanghui.pos.catalog.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** 批次效期策略、日期推导和状态分类的纯领域规则。 */
public final class LotExpiryRules {
    public static final String COMMUNITY_SUPERMARKET = "COMMUNITY_SUPERMARKET";
    public static final Set<String> EXPIRY_BASES = Set.of("PRODUCTION_DATE", "RECEIVED_DATE", "EXPLICIT_EXPIRY_DATE");
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");

    private LotExpiryRules() { }

    /** 校验并规范化不可变批次策略。 */
    public static PolicySpec requirePolicy(PolicySpec value) {
        if (value == null || !ULID.matcher(safe(value.policyVersionId())).matches()
            || value.storeId() == null || value.storeId() <= 0 || value.skuId() == null || value.skuId() <= 0
            || !EXPIRY_BASES.contains(value.expiryBasis()) || value.effectiveFrom() == null
            || value.nearExpiryDays() < 0 || value.nearExpiryDays() > 3650) {
            throw new ServiceException("CAT-LOT-001: 批次效期策略字段非法", 400);
        }
        boolean needsShelfLife = !"EXPLICIT_EXPIRY_DATE".equals(value.expiryBasis());
        if (needsShelfLife && (value.shelfLifeDays() == null || value.shelfLifeDays() < 1
            || value.shelfLifeDays() > 36500)
            || !needsShelfLife && value.shelfLifeDays() != null) {
            throw new ServiceException("CAT-LOT-002: 日期基准与保质期天数不匹配", 409);
        }
        return value;
    }

    /** 按冻结策略从可信日期推导唯一到期日；到期日当天仍可售。 */
    public static LocalDate resolveExpiry(PolicySpec policy, LocalDate productionDate,
                                          LocalDate receivedDate, LocalDate explicitExpiryDate) {
        requirePolicy(policy);
        LocalDate result = switch (policy.expiryBasis()) {
            case "PRODUCTION_DATE" -> plusDays(productionDate, policy.shelfLifeDays());
            case "RECEIVED_DATE" -> plusDays(receivedDate, policy.shelfLifeDays());
            case "EXPLICIT_EXPIRY_DATE" -> explicitExpiryDate;
            default -> null;
        };
        if (result == null || receivedDate == null || result.isBefore(receivedDate)) {
            throw new ServiceException("CAT-LOT-003: 到期日无法唯一确定或早于收货日", 409);
        }
        return result;
    }

    /** 基于门店业务日分类可售、临期和过期；零余额由 Inventory 覆盖为 DEPLETED。 */
    public static String classify(LocalDate businessDate, LocalDate expiryDate, int nearExpiryDays) {
        if (businessDate == null || expiryDate == null || nearExpiryDays < 0) {
            throw new ServiceException("CAT-LOT-004: 效期分类输入非法", 409);
        }
        if (businessDate.isAfter(expiryDate)) return "EXPIRED";
        return !businessDate.plusDays(nearExpiryDays).isBefore(expiryDate) ? "NEAR_EXPIRY" : "AVAILABLE";
    }

    /** 规范内容摘要用于同键异内容拒绝及跨端校验。 */
    public static String contentSha256(PolicySpec value) {
        PolicySpec policy = requirePolicy(value);
        return sha256(String.join("|", policy.policyVersionId(), policy.storeId().toString(),
            policy.skuId().toString(), Boolean.toString(policy.enabled()), policy.expiryBasis(),
            policy.shelfLifeDays() == null ? "" : policy.shelfLifeDays().toString(),
            Integer.toString(policy.nearExpiryDays()), policy.effectiveFrom().toString()));
    }

    private static LocalDate plusDays(LocalDate date, Integer days) {
        return date == null || days == null ? null : date.plusDays(days.longValue() - 1L);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip().toUpperCase();
    }

    /**
     * 商品批次策略的不可变领域值。
     *
     * @param policyVersionId 策略版本 ULID
     * @param storeId 适用门店
     * @param skuId 适用 SKU
     * @param enabled 是否启用批次路径
     * @param expiryBasis 到期日基准
     * @param shelfLifeDays 保质期自然日数；显式到期日模式必须为空
     * @param nearExpiryDays 临期自然日阈值
     * @param effectiveFrom 生效时刻 UTC
     */
    public record PolicySpec(String policyVersionId, Long storeId, Long skuId, boolean enabled,
                             String expiryBasis, Integer shelfLifeDays, int nearExpiryDays,
                             java.time.Instant effectiveFrom) {
    }
}

