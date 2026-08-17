package com.jingshanghui.pos.reporting.application.port;

/** 单次下载令牌端口；持久化层只保存不可逆摘要。 */
public interface ReportDownloadTokenProtector {
    TokenIssue issue();
    String hash(String plaintextToken);

    /**
     * 新签发的令牌及摘要。
     * @param plaintext 只返回一次的明文
     * @param sha256 不可逆 HMAC 摘要
     */
    record TokenIssue(String plaintext, String sha256) {
    }
}
