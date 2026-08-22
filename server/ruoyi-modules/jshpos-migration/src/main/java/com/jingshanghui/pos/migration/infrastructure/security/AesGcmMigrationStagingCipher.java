package com.jingshanghui.pos.migration.infrastructure.security;

import com.jingshanghui.pos.migration.application.port.MigrationStagingCipher;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM 加密 staging；密钥只从仓库外 Secret 注入。
 *
 * <p>缺少、长度错误、版本未知或鉴别失败时关闭迁移入口，不提供明文降级。</p>
 */
@Component
public class AesGcmMigrationStagingCipher implements MigrationStagingCipher {
    private static final int NONCE_BYTES = 12;
    private final byte[] key;
    private final String keyVersion;
    private final SecureRandom random;

    public AesGcmMigrationStagingCipher(
        @Value("${jshpos.migration.staging-key-base64:}") String keyBase64,
        @Value("${jshpos.migration.staging-key-version:}") String keyVersion) {
        this(parseKey(keyBase64), keyVersion, new SecureRandom());
    }

    AesGcmMigrationStagingCipher(byte[] key, String keyVersion, SecureRandom random) {
        this.key = key == null ? new byte[0] : key.clone();
        this.keyVersion = keyVersion == null ? "" : keyVersion.strip();
        this.random = random;
    }

    @Override
    public SealedValue seal(String aad, String plaintext) {
        requireReady(aad);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.getEncoder().encodeToString(nonce) + "."
                + Base64.getEncoder().encodeToString(encrypted);
            return new SealedValue(packed, keyVersion, hmac(aad + "\n" + packed));
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("DMT-SECURITY-002: staging 加密失败", 503);
        }
    }

    @Override
    public String open(String aad, SealedValue sealed) {
        requireReady(aad);
        if (sealed == null || !keyVersion.equals(sealed.keyVersion())
            || !constantTime(hmac(aad + "\n" + sealed.cipherText()), sealed.contentHmac())) {
            throw new ServiceException("DMT-SECURITY-003: staging 密钥版本或摘要不匹配", 409);
        }
        try {
            String[] parts = sealed.cipherText().split("\\.", -1);
            if (parts.length != 2) throw new GeneralSecurityException("format");
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            if (nonce.length != NONCE_BYTES) throw new GeneralSecurityException("nonce");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new ServiceException("DMT-SECURITY-004: staging 密文损坏或遭替换", 409);
        }
    }

    private void requireReady(String aad) {
        if (key.length != 32 || keyVersion.isBlank() || aad == null || aad.isBlank()) {
            throw new ServiceException("DMT-SECURITY-001: staging Secret 未配置，迁移失败关闭", 503);
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }

    private boolean constantTime(String left, String right) {
        return right != null && java.security.MessageDigest.isEqual(
            left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] parseKey(String value) {
        if (value == null || value.isBlank()) return new byte[0];
        try { return Base64.getDecoder().decode(value); }
        catch (IllegalArgumentException exception) { return new byte[0]; }
    }
}
