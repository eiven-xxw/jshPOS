package com.jingshanghui.pos.procurement.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 补货规则、输入检查点和命令的稳定 SHA-256 工具。 */
public final class ReplenishmentHash {

    private ReplenishmentHash() {
    }

    public static String canonical(List<?> values) {
        return values.stream().map(value -> value == null ? "<null>" : String.valueOf(value))
            .map(value -> value.length() + ":" + value).reduce((left, right) -> left + "|" + right).orElse("");
    }

    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new ServiceException("RPL-HASH-001: 运行环境缺少 SHA-256", 500);
        }
    }
}
