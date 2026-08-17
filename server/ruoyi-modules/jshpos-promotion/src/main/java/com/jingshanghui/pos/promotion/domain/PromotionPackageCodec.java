package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 促销离线规则包 canonical 编码、摘要和 Ed25519 验签。 */
public final class PromotionPackageCodec {
    /** 规则包 Schema 版本。 */
    public static final String SCHEMA_VERSION = "1.0";

    private PromotionPackageCodec() {
    }

    /** 构建门店绑定的不可变规则包。 */
    public static EncodedPackage encode(String tenantId, Long storeId, long version, long previousVersion,
                                        Instant generatedAt, Instant expiresAt, List<Record> records) {
        if (tenantId == null || tenantId.isBlank() || storeId == null || storeId <= 0 || version <= 0
            || previousVersion < 0 || previousVersion >= version || generatedAt == null || expiresAt == null
            || !expiresAt.isAfter(generatedAt)) {
            throw new ServiceException("PRM-PKG-001: 规则包身份、版本或有效期无效", 400);
        }
        List<Record> sorted = (records == null ? List.<Record>of() : records).stream()
            .sorted(Comparator.comparing(Record::ruleVersionId)).toList();
        if (sorted.size() > 5_000) {
            throw new ServiceException("PRM-PKG-004: 规则包记录数超过上限", 400);
        }
        for (int index = 1; index < sorted.size(); index++) {
            if (sorted.get(index - 1).ruleVersionId().equals(sorted.get(index).ruleVersionId())) {
                throw new ServiceException("PRM-PKG-005: 规则包包含重复规则版本", 409);
            }
        }
        StringBuilder canonical = new StringBuilder("JSHPRM|").append(SCHEMA_VERSION).append('|')
            .append(PromotionEngine.ENGINE_VERSION).append('|').append(tenantId).append('|').append(storeId)
            .append('|').append(version).append('|').append(previousVersion).append('|').append(generatedAt)
            .append('|').append(expiresAt).append('\n');
        sorted.forEach(record -> canonical.append(escape(record.ruleVersionId())).append('|')
            .append(escape(record.canonicalRule())).append('\n'));
        byte[] payload = canonical.toString().getBytes(StandardCharsets.UTF_8);
        return new EncodedPackage(payload, sha256(payload), sorted.size());
    }

    /** 同时验证载荷摘要和 Ed25519 签名。 */
    public static boolean verify(EncodedPackage encoded, String expectedSha256, byte[] signature, PublicKey key) {
        if (encoded == null || expectedSha256 == null || signature == null || key == null) return false;
        String actual = sha256(encoded.payload());
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
            expectedSha256.getBytes(StandardCharsets.US_ASCII))) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(encoded.payload());
            return verifier.verify(signature);
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static String escape(String value) {
        if (value == null) throw new ServiceException("PRM-PKG-002: canonical 字段不能为空", 400);
        return value.replace("\\", "\\\\").replace("|", "\\p").replace("\r", "\\r").replace("\n", "\\n");
    }

    /** 计算规则包载荷的标准小写 SHA-256。 */
    public static String sha256(byte[] payload) {
        if (payload == null) throw new ServiceException("PRM-PKG-006: 规则包载荷不能为空", 400);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * 规则包记录。
     *
     * @param ruleVersionId 规则版本 ULID
     * @param canonicalRule canonical 规则内容
     */
    public record Record(String ruleVersionId, String canonicalRule) {
        public Record {
            if (ruleVersionId == null || !ruleVersionId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")
                || canonicalRule == null || canonicalRule.isBlank()) {
                throw new ServiceException("PRM-PKG-003: 规则包记录无效", 400);
            }
        }
    }

    /**
     * 已编码规则包。
     *
     * @param payload canonical 载荷
     * @param sha256 摘要
     * @param recordCount 规则数
     */
    public record EncodedPackage(byte[] payload, String sha256, int recordCount) {
        public EncodedPackage { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
}
