package com.jingshanghui.pos.catalog.application.packagev1;

/** 生产实现必须连接 KMS/HSM；仓库不提供私钥实现。 */
public interface PackageSigningPort {

    SigningResult sign(String tenantId, byte[] canonicalPayload);

    record SigningResult(String keyId, String algorithm, byte[] signature) {
        public SigningResult {
            signature = signature.clone();
            if (!"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException("Gate 1 package algorithm must be Ed25519");
            }
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
