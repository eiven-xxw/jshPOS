package com.jingshanghui.pos.integration.infrastructure.artifact;

import org.dromara.common.core.exception.ServiceException;

import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * 显式配置的 Ed25519 软件签名器。
 *
 * <p>私钥只从受控运行时配置读取，不写仓库、数据库、日志或制品。商业生产环境仍应
 * 替换为 KMS/HSM 适配器；启用本实现不会提升生产密钥证据等级。</p>
 */
public final class SoftwareEd25519PackageSigner {
    private final String keyId;
    private final PrivateKey privateKey;

    public SoftwareEd25519PackageSigner(String keyId, String pkcs8Base64) {
        if (keyId == null || keyId.isBlank() || keyId.length() > 128
            || pkcs8Base64 == null || pkcs8Base64.isBlank()) {
            throw new ServiceException("CORE-SIGN-001: 软件签名器配置不完整", 503);
        }
        this.keyId = keyId;
        try {
            byte[] keyBytes = Base64.getDecoder().decode(pkcs8Base64);
            this.privateKey = KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            java.util.Arrays.fill(keyBytes, (byte) 0);
        } catch (Exception exception) {
            throw new ServiceException("CORE-SIGN-002: Ed25519私钥格式无效", 503);
        }
    }

    /** 对 canonical payload 生成固定 64 字节 Ed25519 签名。 */
    public byte[] sign(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new ServiceException("CORE-SIGN-003: 待签名载荷为空", 503);
        }
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(payload);
            return signer.sign();
        } catch (Exception exception) {
            throw new ServiceException("CORE-SIGN-004: Ed25519签名失败", 503);
        }
    }

    public String keyId() {
        return keyId;
    }
}
