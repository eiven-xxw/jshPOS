package com.jingshanghui.pos.payment.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 支付命令与观察值使用的确定性长度前缀哈希，避免字段连接歧义。 */
public final class PaymentHash {

    private PaymentHash() {
    }

    public static String canonical(Iterable<?> values) {
        StringBuilder value = new StringBuilder();
        for (Object item : values) {
            String text = String.valueOf(item);
            value.append(text.length()).append(':').append(text).append(';');
        }
        return value.toString();
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("PAY-HASH-001: SHA-256 unavailable", exception);
        }
    }
}
