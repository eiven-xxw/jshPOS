package com.jingshanghui.pos.catalog.application.packagev1;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** 正式 v1 canonical 数据包编码、摘要和 Ed25519 公钥校验。 */
public final class CatalogPackageCodec {

    public static final String SCHEMA_VERSION = "1.0";

    private CatalogPackageCodec() {
    }

    public static boolean supportsSchema(String schemaVersion) {
        return SCHEMA_VERSION.equals(schemaVersion) || "0.9".equals(schemaVersion);
    }

    public static EncodedPackage encode(String tenantId, Long storeId, long packageVersion,
                                        long previousVersion, Instant generatedAt, List<Record> records) {
        requireIdentity(tenantId, storeId, packageVersion, previousVersion, generatedAt);
        List<Record> sorted = new ArrayList<>(records == null ? List.of() : records);
        sorted.sort(Comparator.comparing(Record::recordType).thenComparing(Record::recordKey));
        StringBuilder canonical = new StringBuilder();
        canonical.append("JSHCAT|").append(SCHEMA_VERSION).append('|').append(tenantId).append('|')
            .append(storeId).append('|').append(packageVersion).append('|').append(previousVersion).append('|')
            .append(generatedAt).append('\n');
        for (Record record : sorted) {
            canonical.append(escape(record.recordType())).append('|').append(escape(record.recordKey()))
                .append('|').append(escape(record.canonicalPayload())).append('\n');
        }
        byte[] payload = canonical.toString().getBytes(StandardCharsets.UTF_8);
        return new EncodedPackage(payload, sha256(payload), sorted.size());
    }

    public static boolean verify(EncodedPackage encoded, String expectedSha256, byte[] signature, PublicKey publicKey) {
        if (encoded == null || expectedSha256 == null || signature == null || publicKey == null) {
            return false;
        }
        String actualSha256 = sha256(encoded.payload());
        if (!MessageDigest.isEqual(actualSha256.getBytes(StandardCharsets.US_ASCII),
            encoded.sha256().getBytes(StandardCharsets.US_ASCII)) || !MessageDigest.isEqual(
            actualSha256.getBytes(StandardCharsets.US_ASCII), expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(encoded.payload());
            return verifier.verify(signature);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } catch (SignatureException | java.security.InvalidKeyException exception) {
            return false;
        }
    }

    private static void requireIdentity(String tenantId, Long storeId, long version, long previous, Instant at) {
        if (tenantId == null || tenantId.isBlank() || storeId == null || storeId <= 0 || version <= 0
            || previous < 0 || previous >= version || at == null) {
            throw new ServiceException("CAT-DPK-001: 数据包身份或版本无效", 400);
        }
    }

    private static String escape(String value) {
        if (value == null) {
            throw new ServiceException("CAT-DPK-002: canonical 字段不能为空", 400);
        }
        return value.replace("\\", "\\\\").replace("|", "\\p").replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Record(String recordType, String recordKey, String canonicalPayload) {
        public Record {
            if (recordType == null || recordType.isBlank() || recordKey == null || recordKey.isBlank()) {
                throw new ServiceException("CAT-DPK-003: 数据包记录身份不能为空", 400);
            }
        }
    }

    public record EncodedPackage(byte[] payload, String sha256, int recordCount) {
        public EncodedPackage {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }
}
