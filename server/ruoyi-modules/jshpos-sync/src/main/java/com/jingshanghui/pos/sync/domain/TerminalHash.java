package com.jingshanghui.pos.sync.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 终端命令和能力快照的确定性摘要。 */
public final class TerminalHash {
    private TerminalHash() { }

    public static String canonicalJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(normalize(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("TRM_INPUT_INVALID: JSON 无法规范化", exception);
        }
    }

    public static String digest(ObjectMapper mapper, Object value) {
        return SyncHash.evidence(canonicalJson(mapper, value));
    }

    private static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(item -> normalized.add(normalize(item)));
            return normalized;
        }
        return value;
    }
}
