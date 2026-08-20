package com.jingshanghui.pos.release.infrastructure.security;

import com.jingshanghui.pos.release.application.port.ReleasePorts.*;
import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import org.dromara.common.core.exception.ServiceException;

import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

/** 使用受控对象内容和公钥注册表执行Ed25519验签；私钥永不进入服务端。 */
public final class Ed25519ArtifactVerifier implements ArtifactVerifier {
    private static final int MAX_SYNTHETIC_BYTES = 64 * 1024 * 1024;
    private final ArtifactBinarySource source;
    private final PublicKeyRegistry keys;

    public Ed25519ArtifactVerifier(ArtifactBinarySource source, PublicKeyRegistry keys) {
        this.source = source; this.keys = keys;
    }

    @Override public ArtifactObservation verify(Release release) {
        try {
            byte[] bytes = source.read(release.objectKey());
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_SYNTHETIC_BYTES) {
                throw new ServiceException("UPG-ART-005: 发布物为空或超过软件校验容量", 422);
            }
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(keys.resolve(release.keyVersion()));
            signature.update(bytes);
            boolean valid = signature.verify(Base64.getDecoder().decode(release.signatureBase64()));
            return new ArtifactObservation(sha, valid, release.keyVersion(), bytes.length);
        } catch (ServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceException("UPG-ART-006: 发布物验签失败关闭", 422);
        }
    }
}
