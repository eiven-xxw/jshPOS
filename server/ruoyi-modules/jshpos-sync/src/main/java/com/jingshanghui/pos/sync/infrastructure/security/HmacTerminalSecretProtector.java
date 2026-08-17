package com.jingshanghui.pos.sync.infrastructure.security;

import com.jingshanghui.pos.sync.domain.TerminalSecretProtector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/** 使用外部 pepper 的 HMAC-SHA-256 终端秘密保护实现。 */
public final class HmacTerminalSecretProtector implements TerminalSecretProtector {
    private final byte[] pepper;

    public HmacTerminalSecretProtector(String pepper) {
        if (pepper == null || pepper.length() < 32) {
            throw new IllegalArgumentException("terminal activation pepper must contain at least 32 characters");
        }
        this.pepper = pepper.getBytes(StandardCharsets.UTF_8).clone();
    }

    @Override
    public String digest(String purpose, String secret) {
        if (purpose == null || purpose.isBlank() || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("terminal secret purpose and value are required");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((purpose + "\u0000" + secret)
                .getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable", exception);
        }
    }
}
