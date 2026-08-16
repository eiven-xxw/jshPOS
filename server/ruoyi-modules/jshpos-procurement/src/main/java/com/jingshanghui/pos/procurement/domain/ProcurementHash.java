package com.jingshanghui.pos.procurement.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 采购命令、审计和事件使用的确定性 SHA-256。 */
public final class ProcurementHash {

    private ProcurementHash() {
    }

    public static String canonical(Iterable<?> values) {
        StringBuilder result = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            result.append(text.length()).append(':').append(text).append(';');
        }
        return result.toString();
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
