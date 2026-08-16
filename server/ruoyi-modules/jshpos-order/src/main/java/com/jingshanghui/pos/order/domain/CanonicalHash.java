package com.jingshanghui.pos.order.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CanonicalHash {

    private CanonicalHash() {
    }

    /** Length-prefix framing shared with the Flutter POS; null is encoded as the four characters "null". */
    public static String lengthPrefixed(Iterable<?> values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            String text = String.valueOf(value);
            canonical.append(text.length()).append(':').append(text).append(';');
        }
        return canonical.toString();
    }

    public static String sha256(String canonicalValue) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonicalValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
