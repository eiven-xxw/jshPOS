package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.reporting.application.port.ReportDownloadTokenProtector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 使用外部 HMAC 密钥保护一次性下载令牌；仓库和数据库均不保存明文令牌。 */
public final class HmacReportDownloadTokenProtector implements ReportDownloadTokenProtector {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public HmacReportDownloadTokenProtector(byte[] key) {
        if (key == null || key.length < 32) {
            throw new IllegalArgumentException("RPT-SEC-001: 下载令牌 HMAC 密钥至少 256 bit");
        }
        this.key = key.clone();
    }

    @Override
    public TokenIssue issue() {
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        return new TokenIssue(plaintext, hash(plaintext));
    }

    @Override
    public String hash(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.length() < 32 || plaintextToken.length() > 128) {
            throw new IllegalArgumentException("RPT-SEC-002: 下载令牌格式无效");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(plaintextToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("RPT-SEC-003: HMAC 不可用", exception);
        }
    }
}
