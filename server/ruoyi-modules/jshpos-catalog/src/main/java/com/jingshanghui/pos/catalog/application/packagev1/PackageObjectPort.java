package com.jingshanghui.pos.catalog.application.packagev1;

/** 对象存储端口；实现必须以原子 put 写入 payload 与签名，并按可信对象键读取。 */
public interface PackageObjectPort {

    void put(String trustedObjectKey, byte[] canonicalPayload, byte[] signature);

    /** 读取不可变包对象；不存在时返回 {@code null}，不得按客户端路径读取。 */
    StoredObject get(String trustedObjectKey);

    /** 对象存储返回的原始载荷和分离签名。 */
    record StoredObject(byte[] payload, byte[] signature) {
        public StoredObject {
            payload = payload.clone();
            signature = signature.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }
    }
}
