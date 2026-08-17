package com.jingshanghui.pos.transfer.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 调拨命令和审计内容的确定性 SHA-256 工具。 */
public final class TransferHash {
    private TransferHash() { }

    public static String canonical(List<?> values) {
        return values.stream().map(value -> value == null ? "<null>" : String.valueOf(value))
            .map(value -> value.length() + ":" + value).reduce((a, b) -> a + "|" + b).orElse("");
    }

    public static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(64);
            for (byte item : bytes) output.append(String.format("%02x", item));
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new ServiceException("TRF-HASH-001: SHA-256 不可用", 500);
        }
    }
}
