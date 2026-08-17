package com.jingshanghui.pos.member.infrastructure.crypto;

import com.jingshanghui.pos.member.application.port.MemberIdentityProtector;
import com.jingshanghui.pos.member.domain.MemberRules;
import org.dromara.common.core.exception.ServiceException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 使用 HMAC-SHA256 精确检索和 AES-GCM 版本化信封保护身份。 */
public final class AesGcmMemberIdentityProtector implements MemberIdentityProtector {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec lookupKey;
    private final SecretKeySpec encryptionKey;
    private final int keyVersion;
    private final SecureRandom random;

    public AesGcmMemberIdentityProtector(byte[] lookupKey, byte[] encryptionKey, int keyVersion) {
        this(lookupKey, encryptionKey, keyVersion, new SecureRandom());
    }

    AesGcmMemberIdentityProtector(byte[] lookupKey, byte[] encryptionKey, int keyVersion, SecureRandom random) {
        if (lookupKey == null || lookupKey.length < 32 || encryptionKey == null
            || !java.util.Set.of(16, 24, 32).contains(encryptionKey.length) || keyVersion <= 0) {
            throw new IllegalArgumentException("MEM-CRYPTO-001: 密钥材料或版本无效");
        }
        this.lookupKey = new SecretKeySpec(lookupKey.clone(), "HmacSHA256");
        this.encryptionKey = new SecretKeySpec(encryptionKey.clone(), "AES");
        this.keyVersion = keyVersion;
        this.random = random;
    }

    @Override
    public ProtectedIdentity protect(String identityType, String normalizedValue) {
        String normalized = MemberRules.normalizeIdentity(identityType, normalizedValue);
        try {
            byte[] iv = new byte[IV_LENGTH]; random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(identityType));
            byte[] ciphertext = cipher.doFinal(normalized.getBytes(StandardCharsets.UTF_8));
            ByteBuffer envelope = ByteBuffer.allocate(4 + IV_LENGTH + ciphertext.length)
                .putInt(keyVersion).put(iv).put(ciphertext);
            return new ProtectedIdentity(lookupHmac(identityType, normalized),
                Base64.getEncoder().encodeToString(envelope.array()), MemberRules.mask(identityType, normalized), keyVersion);
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("MEM-CRYPTO-002: 身份保护失败", 503);
        }
    }

    @Override
    public String lookupHmac(String identityType, String normalizedValue) {
        String normalized = MemberRules.normalizeIdentity(identityType, normalizedValue);
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(lookupKey);
            return HexFormat.of().formatHex(mac.doFinal((identityType + "\u0000" + normalized)
                .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new ServiceException("MEM-CRYPTO-003: 身份检索摘要失败", 503);
        }
    }

    private byte[] aad(String identityType) {
        return ("JSH-MEMBER:" + identityType + ":v" + keyVersion).getBytes(StandardCharsets.UTF_8);
    }
}
