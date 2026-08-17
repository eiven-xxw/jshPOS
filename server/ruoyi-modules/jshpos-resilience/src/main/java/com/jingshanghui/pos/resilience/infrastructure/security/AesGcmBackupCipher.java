package com.jingshanghui.pos.resilience.infrastructure.security;

import org.dromara.common.core.exception.ServiceException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** AES-256-GCM 对象级加密；AAD 绑定备份、租户范围、路径、摘要和密钥版本。 */
public final class AesGcmBackupCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random;

    public AesGcmBackupCipher() { this(new SecureRandom()); }
    AesGcmBackupCipher(SecureRandom random) { this.random = random; }

    /** @param bytes nonce前缀与GCM密文 @param nonceBase64 清单中的nonce */
    public record Encrypted(byte[] bytes, String nonceBase64) {
        public Encrypted { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public Encrypted encrypt(byte[] plaintext, SecretKey key, String aad) {
        requireKey(key);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext);
            return new Encrypted(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array(),
                Base64.getEncoder().encodeToString(nonce));
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("BAK-CRYPT-001: 备份对象加密失败", 503);
        }
    }

    public byte[] decrypt(byte[] payload, SecretKey key, String aad, String expectedNonceBase64) {
        requireKey(key);
        if (payload == null || payload.length <= NONCE_BYTES + 16) fail("BAK-CRYPT-002: 密文长度非法", 422);
        byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        if (!Base64.getEncoder().encodeToString(nonce).equals(expectedNonceBase64)) fail("BAK-CRYPT-003: nonce与清单不一致", 422);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("BAK-CRYPT-004: 密钥、AAD或密文校验失败", 422);
        }
    }

    private static void requireKey(SecretKey key) {
        if (key == null || key.getEncoded() == null || key.getEncoded().length != 32 || !"AES".equals(key.getAlgorithm())) {
            fail("BAK-CRYPT-005: 必须提供独立AES-256密钥", 503);
        }
    }

    private static void fail(String message, int code) { throw new ServiceException(message, code); }
}
