package com.jingshanghui.pos.costing.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 成本维度、来源事实和事件使用的确定性 SHA-256 工具。 */
public final class CostingHash {

    private CostingHash() {
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
            throw new IllegalStateException(exception);
        }
    }

    /** Gate 4C 只允许仓级 CNY 成本范围。 */
    public static String dimension(String tenantId, String warehouseId, Long skuId) {
        return sha256(canonical(List.of(tenantId, "WAREHOUSE", warehouseId, skuId, "CNY")));
    }
}
