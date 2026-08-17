package com.jingshanghui.pos.promotion.application.port;

/** 促销规则包外部签名和对象存储端口。 */
public final class PromotionPackagePorts {
    private PromotionPackagePorts() { }

    /** KMS/HSM Ed25519 签名端口。 */
    public interface SigningPort {
        /** 对 canonical 载荷签名。 */
        SigningResult sign(String tenantId, byte[] payload);
    }

    /** 租户命名空间对象存储端口。 */
    public interface ObjectPort {
        /** 原子写入载荷和分离签名。 */
        void put(String objectKey, byte[] payload, byte[] signature);
        /** 按服务端生成的可信对象键读取载荷与分离签名。 */
        StoredObject get(String objectKey);
    }

    /**
     * 签名结果。
     *
     * @param keyId KMS/HSM密钥版本
     * @param signature Ed25519签名
     */
    public record SigningResult(String keyId, byte[] signature) {
        public SigningResult { signature = signature.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }

    /**
     * 对象存储中的规则包原始内容。
     * @param payload canonical 规则包载荷 @param signature Ed25519签名
     */
    public record StoredObject(byte[] payload, byte[] signature) {
        public StoredObject { payload = payload.clone(); signature = signature.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }
}
