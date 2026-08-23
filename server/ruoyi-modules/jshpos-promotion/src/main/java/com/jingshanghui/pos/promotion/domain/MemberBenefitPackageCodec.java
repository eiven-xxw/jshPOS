package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** T2-MEM-003 无 PII 会员权益/会员价离线包的确定性 canonical 编码器。 */
public final class MemberBenefitPackageCodec {
    public static final String SCHEMA_VERSION = "1.0";
    public static final String ENGINE_VERSION = "member-benefit-engine-1.0.0";
    private static final Pattern ULID = Pattern.compile("^[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern LEVEL = Pattern.compile("^[A-Z0-9_-]{1,32}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
    private static final Pattern TENANT = Pattern.compile("^[A-Za-z0-9_-]{1,20}$");
    private static final Set<String> COMBINATION_POLICIES = Set.of("BEST_PRICE", "NORMAL_ONLY", "MEMBER_ONLY");
    private MemberBenefitPackageCodec() { }

    public static EncodedPackage encode(String tenantId, Long storeId, long packageVersion,
                                        long previousVersion, Instant generatedAt, Instant expiresAt,
                                        List<BenefitRecord> benefits, List<MemberPriceRecord> prices) {
        if (tenantId == null || !TENANT.matcher(tenantId).matches() || storeId == null || storeId <= 0
            || packageVersion <= 0 || previousVersion < 0 || packageVersion != previousVersion + 1
            || generatedAt == null || expiresAt == null || !expiresAt.isAfter(generatedAt)) {
            throw new ServiceException("PRM-MBP-PKG-001: 数据包身份或版本无效", 400);
        }
        List<BenefitRecord> sortedBenefits = List.copyOf(benefits == null ? List.of() : benefits).stream()
            .sorted(Comparator.comparing(BenefitRecord::levelCode).thenComparing(BenefitRecord::versionId)).toList();
        List<MemberPriceRecord> sortedPrices = List.copyOf(prices == null ? List.of() : prices).stream()
            .sorted(Comparator.comparing(MemberPriceRecord::levelCode)
                .thenComparing(MemberPriceRecord::skuId).thenComparing(MemberPriceRecord::unitId)
                .thenComparing(value -> value.scopeStoreId() == null ? 1 : 0)
                .thenComparing(MemberPriceRecord::versionNo, Comparator.reverseOrder())
                .thenComparing(MemberPriceRecord::versionId)).toList();
        if (sortedBenefits.size() > 2_000 || sortedPrices.size() > 500_000) {
            throw new ServiceException("PRM-MBP-PKG-002: 数据包记录数超过上限", 400);
        }
        sortedBenefits.forEach(MemberBenefitPackageCodec::validateBenefit);
        sortedPrices.forEach(MemberBenefitPackageCodec::validatePrice);
        StringBuilder value = new StringBuilder("JSHMBP|").append(SCHEMA_VERSION).append('|')
            .append(ENGINE_VERSION).append('|').append(tenantId).append('|').append(storeId).append('|')
            .append(packageVersion).append('|').append(previousVersion).append('|').append(generatedAt)
            .append('|').append(expiresAt).append('\n');
        sortedBenefits.forEach(row -> value.append("B|").append(row.versionId()).append('|')
            .append(row.levelCode()).append('|').append(row.memberPriceEligible() ? 1 : 0).append('|')
            .append(row.stackingAllowed() ? 1 : 0).append('|').append(row.defaultCombinationPolicy())
            .append('|').append(row.policyAllowStacking() ? 1 : 0).append('|').append(row.revocationEpoch())
            .append('|').append(utc(row.effectiveAt())).append('|').append(utcOrDash(row.expiresAt()))
            .append('|').append(row.contentSha256()).append('\n'));
        sortedPrices.forEach(row -> value.append("P|").append(row.versionId()).append('|')
            .append(row.versionNo()).append('|').append(row.levelCode()).append('|').append(row.skuId())
            .append('|').append(row.unitId()).append('|').append(row.scopeStoreId() == null ? "*" : row.scopeStoreId())
            .append('|').append(row.amountMinor()).append('|').append(utc(row.effectiveAt())).append('|')
            .append(utcOrDash(row.expiresAt())).append('|').append(row.contentSha256()).append('\n'));
        byte[] payload = value.toString().getBytes(StandardCharsets.UTF_8);
        return new EncodedPackage(payload, PromotionPackageCodec.sha256(payload),
            sortedBenefits.size(), sortedPrices.size());
    }

    private static String utc(LocalDateTime value) {
        if (value == null) throw new ServiceException("PRM-MBP-PKG-003: 生效时间不能为空", 400);
        return value.toInstant(ZoneOffset.UTC).toString();
    }
    private static String utcOrDash(LocalDateTime value) { return value == null ? "-" : utc(value); }

    private static void validateBenefit(BenefitRecord row) {
        if (row == null || !ULID.matcher(value(row.versionId())).matches()
            || !LEVEL.matcher(value(row.levelCode())).matches()
            || !COMBINATION_POLICIES.contains(row.defaultCombinationPolicy())
            || row.revocationEpoch() < 0 || !SHA256.matcher(value(row.contentSha256())).matches()
            || row.effectiveAt() == null || (row.expiresAt() != null && !row.expiresAt().isAfter(row.effectiveAt()))) {
            throw new ServiceException("PRM-MBP-PKG-003: 权益记录无效", 400);
        }
    }

    private static void validatePrice(MemberPriceRecord row) {
        if (row == null || !ULID.matcher(value(row.versionId())).matches() || row.versionNo() <= 0
            || !LEVEL.matcher(value(row.levelCode())).matches() || row.skuId() == null || row.skuId() <= 0
            || row.unitId() == null || row.unitId() <= 0 || (row.scopeStoreId() != null && row.scopeStoreId() <= 0)
            || row.amountMinor() < 0 || !SHA256.matcher(value(row.contentSha256())).matches()
            || row.effectiveAt() == null || (row.expiresAt() != null && !row.expiresAt().isAfter(row.effectiveAt()))) {
            throw new ServiceException("PRM-MBP-PKG-003: 会员价记录无效", 400);
        }
    }

    private static String value(String value) { return value == null ? "" : value; }

    /** 无 PII 权益映射记录。 */
    public record BenefitRecord(String versionId, String levelCode, boolean memberPriceEligible,
                                boolean stackingAllowed, String defaultCombinationPolicy,
                                boolean policyAllowStacking, long revocationEpoch,
                                LocalDateTime effectiveAt, LocalDateTime expiresAt,
                                String contentSha256) { }
    /** 精确会员价记录。 */
    public record MemberPriceRecord(String versionId, int versionNo, String levelCode, Long skuId,
                                    Long unitId, Long scopeStoreId, long amountMinor,
                                    LocalDateTime effectiveAt, LocalDateTime expiresAt,
                                    String contentSha256) { }
    public record EncodedPackage(byte[] payload, String sha256, int benefitCount, int memberPriceCount) {
        public EncodedPackage { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
