package com.jingshanghui.pos.member.application.port;

/** 身份保护端口：精确检索使用 HMAC，静态存储使用版本化 AEAD。 */
public interface MemberIdentityProtector {
    record ProtectedIdentity(String lookupHmac, String cipherText, String maskedValue, int keyVersion) { }
    ProtectedIdentity protect(String identityType, String normalizedValue);
    String lookupHmac(String identityType, String normalizedValue);
}
