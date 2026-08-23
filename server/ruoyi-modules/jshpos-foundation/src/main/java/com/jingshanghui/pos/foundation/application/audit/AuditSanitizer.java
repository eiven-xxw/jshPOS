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
    private static final String[] SENSITIVE = {
        "password", "token", "secret", "privatekey", "publickey", "card", "phone", "idno"
    };
    private final ObjectMapper json;

    public AuditSanitizer(ObjectMapper objectMapper) {
        // 复用应用统一注册的 Java Time 等模块，并复制后收紧审计摘要的确定性排序，
        // 避免修改全局 ObjectMapper 或因 LocalDate/LocalTime 导致业务事务回滚。
        this.json = objectMapper.copy()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

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
            source = json.convertValue(value, Object.class);
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
            return this.json.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored audit summary is not valid JSON", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("audit payload is not JSON serializable", exception);
        }
    }

    public record SanitizedPayload(String sha256, String json) {
    }
}
