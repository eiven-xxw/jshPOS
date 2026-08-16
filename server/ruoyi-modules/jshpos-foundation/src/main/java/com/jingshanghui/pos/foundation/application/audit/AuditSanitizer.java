package com.jingshanghui.pos.foundation.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 审计摘要在持久化前递归脱敏并规范化，完整业务对象不进入日志。
 */
@Component
public class AuditSanitizer {

    private static final int MAX_SUMMARY_BYTES = 8 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private static final String[] SENSITIVE = {
        "password", "token", "secret", "privatekey", "publickey", "card", "phone", "idno"
    };

    public SanitizedPayload sanitize(Object value) {
        Object normalized = normalize(value);
        String json = write(normalized);
        String hash = sha256(json);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SUMMARY_BYTES) {
            json = "{\"truncated\":true,\"sha256\":\"" + hash + "\"}";
        }
        return new SanitizedPayload(hash, json);
    }

    @SuppressWarnings("unchecked")
    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        Object source = value;
        if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) {
            source = JSON.convertValue(value, Object.class);
        }
        if (source instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> {
                String textKey = String.valueOf(key);
                result.put(textKey, isSensitive(textKey) ? "***" : normalize(item));
            });
            return new LinkedHashMap<>(result);
        }
        if (source instanceof Collection<?> collection) {
            Collection<Object> result = new ArrayList<>(collection.size());
            collection.forEach(item -> result.add(normalize(item)));
            return result;
        }
        return source;
    }

    private boolean isSensitive(String key) {
        String normalized = key.replace("_", "").toLowerCase(Locale.ROOT);
        for (String sensitive : SENSITIVE) {
            if (normalized.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public Map<String, Object> parseMap(String json) {
        try {
            return JSON.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored audit summary is not valid JSON", exception);
        }
    }

    private String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit payload is not JSON serializable", exception);
        }
    }

    public record SanitizedPayload(String sha256, String json) {
    }
}
