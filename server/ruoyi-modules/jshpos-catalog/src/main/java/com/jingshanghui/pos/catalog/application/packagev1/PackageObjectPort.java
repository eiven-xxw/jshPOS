package com.jingshanghui.pos.catalog.application.packagev1;

/** 对象存储端口；实现必须以原子 put 写入 payload 与签名。 */
public interface PackageObjectPort {

    void put(String trustedObjectKey, byte[] canonicalPayload, byte[] signature);
}
