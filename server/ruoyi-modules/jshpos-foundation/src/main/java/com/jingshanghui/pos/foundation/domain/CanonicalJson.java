package com.jingshanghui.pos.foundation.domain;

import org.dromara.common.core.exception.ServiceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 配置版本使用排序键的确定性 JSON 和 SHA-256；不允许非 JSON 类型。
 */
public final class CanonicalJson {

    public static final int MAX_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private CanonicalJson() {
    }

    public static Result from(Map<String, Object> content) {
        return from(content, MAX_BYTES);
    }

    /**
     * 为受控大对象生成同一规范化 JSON；调用方必须声明并评审容量上限。
     *
     * @param content JSON对象
     * @param maxBytes UTF-8最大字节数，范围1..1MiB
     * @return 规范JSON与SHA-256
     */
    public static Result from(Map<String, Object> content, int maxBytes) {
        if (content == null) {
            throw new ServiceException("FND-CFG-001: 配置内容不能为空", 400);
        }
        if (maxBytes < 1 || maxBytes > 1024 * 1024) {
            throw new ServiceException("FND-CFG-005: 规范JSON容量上限非法", 400);
        }
        Object normalized = normalize(content);
        String json;
        try {
            json = JSON.writeValueAsString(normalized);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("FND-CFG-004: 配置无法序列化为 JSON", 400);
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new ServiceException("FND-CFG-002: 规范JSON内容超过容量上限", 400);
        }
        return new Result(json, sha256(json));
    }

    private static Object normalize(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return new LinkedHashMap<>(sorted);
        }
        if (value instanceof Collection<?> collection) {
            Collection<Object> normalized = new ArrayList<>(collection.size());
            collection.forEach(item -> normalized.add(normalize(item)));
            return normalized;
        }
        throw new ServiceException("FND-CFG-004: 配置包含不支持的 JSON 类型", 400);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Result(String json, String sha256) {
    }
}
