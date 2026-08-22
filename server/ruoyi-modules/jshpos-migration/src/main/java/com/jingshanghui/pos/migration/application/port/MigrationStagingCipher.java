package com.jingshanghui.pos.migration.application.port;

/** 加密隔离 staging 的端口；明文不得写日志、审计或普通制品。 */
public interface MigrationStagingCipher {
    SealedValue seal(String aad, String plaintext);
    String open(String aad, SealedValue sealed);

    /**
     * 加密文本、密钥版本和防替换 HMAC。
     * @param cipherText AES-256-GCM 密文及随机 nonce 的编码值
     * @param keyVersion 非秘密密钥版本标识
     * @param contentHmac 绑定 AAD 与密文的防替换摘要
     */
    record SealedValue(String cipherText, String keyVersion, String contentHmac) { }
}
