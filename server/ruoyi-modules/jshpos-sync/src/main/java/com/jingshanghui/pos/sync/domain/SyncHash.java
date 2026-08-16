package com.jingshanghui.pos.sync.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public final class SyncHash {

    private SyncHash() {
    }

    public static String payload(ObjectMapper objectMapper, Map<String, Object> payload) {
        try {
            return sha256(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("SYNC_PAYLOAD_INVALID: payload cannot be serialized", exception);
        }
    }

    public static String page(List<String> changeIds, List<String> payloadHashes) {
        if (changeIds.size() != payloadHashes.size()) {
            throw new IllegalArgumentException("SYNC_PAGE_INVALID: IDs and hashes have different sizes");
        }
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < changeIds.size(); index++) {
            canonical.append(changeIds.get(index)).append(':').append(payloadHashes.get(index)).append('\n');
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String evidence(String... values) {
        return sha256(String.join("|", values).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
