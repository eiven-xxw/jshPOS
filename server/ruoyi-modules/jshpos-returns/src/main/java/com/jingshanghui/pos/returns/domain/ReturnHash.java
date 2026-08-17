package com.jingshanghui.pos.returns.domain;

import com.jingshanghui.pos.foundation.domain.CanonicalJson;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 退货跨 Owner 幂等内容摘要。 */
public final class ReturnHash {
    private ReturnHash() { }

    public static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static String canonical(List<?> values) {
        return values.stream().map(value -> {
            String text = value == null ? "<null>" : value.toString();
            return text.length() + ":" + text;
        }).collect(java.util.stream.Collectors.joining("|"));
    }

    public static CanonicalJson.Result payload(java.util.Map<String, Object> value) {
        return CanonicalJson.from(value);
    }
}
