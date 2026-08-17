package com.jingshanghui.pos.sync.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 终端秘密摘要端口；实现必须使用外部 pepper 且不可回退为普通 SHA-256。 */
@FunctionalInterface
public interface TerminalSecretProtector {
    String digest(String purpose, String secret);

    default boolean matches(String purpose, String secret, String expectedDigest) {
        if (expectedDigest == null) return false;
        return MessageDigest.isEqual(digest(purpose, secret).getBytes(StandardCharsets.US_ASCII),
            expectedDigest.getBytes(StandardCharsets.US_ASCII));
    }
}
