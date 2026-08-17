package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 报表幂等与重建使用的确定性 SHA-256；输入必须由调用方按固定字段顺序编码。 */
public final class CanonicalReportHash {
    private CanonicalReportHash() {
    }

    public static String sha256(String canonical) {
        if (canonical == null) {
            throw new ServiceException("RPT-G5D-024: canonical 输入不能为空", 400);
        }
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    /** 对制品原始字节计算摘要，避免文本解码和换行转换改变证据。 */
    public static String sha256(byte[] content) {
        if (content == null) {
            throw new ServiceException("RPT-G5D-024: canonical 输入不能为空", 400);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
