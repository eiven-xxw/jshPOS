package com.jingshanghui.pos.resilience.infrastructure.security;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/** AES-GCM 正常、错密钥、AAD、nonce、损坏与密钥长度回归。 */
class AesGcmBackupCipherTest {
    private final AesGcmBackupCipher cipher = new AesGcmBackupCipher();
    private final SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");

    @Test void roundTripsWithBoundAad() {
        byte[] plain = "synthetic-authoritative-fact".getBytes(StandardCharsets.UTF_8);
        var encrypted = cipher.encrypt(plain, key, "backup|tenant|object|sha|v1");
        assertThat(encrypted.bytes()).isNotEqualTo(plain).hasSizeGreaterThan(plain.length);
        assertThat(cipher.decrypt(encrypted.bytes(), key, "backup|tenant|object|sha|v1", encrypted.nonceBase64()))
            .isEqualTo(plain);
    }

    @Test void failsClosedForWrongKeyAadNonceCorruptionAndLength() {
        var encrypted = cipher.encrypt(new byte[]{1,2,3}, key, "aad");
        byte[] otherKey = new byte[32]; otherKey[0] = 1;
        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), new SecretKeySpec(otherKey, "AES"), "aad", encrypted.nonceBase64())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), key, "bad", encrypted.nonceBase64())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cipher.decrypt(encrypted.bytes(), key, "aad", "AAAAAAAAAAAAAAAA")).isInstanceOf(ServiceException.class).hasMessageContaining("nonce");
        byte[] corrupt = encrypted.bytes(); corrupt[corrupt.length-1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(corrupt, key, "aad", encrypted.nonceBase64())).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cipher.decrypt(new byte[4], key, "aad", encrypted.nonceBase64())).isInstanceOf(ServiceException.class).hasMessageContaining("长度");
        assertThatThrownBy(() -> cipher.encrypt(new byte[]{1}, new SecretKeySpec(new byte[16], "AES"), "aad")).isInstanceOf(ServiceException.class).hasMessageContaining("AES-256");
        assertThatThrownBy(() -> cipher.encrypt(new byte[]{1}, null, "aad")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cipher.encrypt(new byte[]{1}, new SecretKeySpec(new byte[32], "ChaCha20"), "aad")).isInstanceOf(ServiceException.class);
        SecretKey noEncoding = new SecretKey() {
            @Override public String getAlgorithm() { return "AES"; }
            @Override public String getFormat() { return "RAW"; }
            @Override public byte[] getEncoded() { return null; }
        };
        assertThatThrownBy(() -> cipher.encrypt(new byte[]{1}, noEncoding, "aad")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> cipher.decrypt(null, key, "aad", encrypted.nonceBase64())).isInstanceOf(ServiceException.class);
    }
}
