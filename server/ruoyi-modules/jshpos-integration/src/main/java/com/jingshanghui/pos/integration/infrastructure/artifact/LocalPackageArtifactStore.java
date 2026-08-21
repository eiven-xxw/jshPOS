package com.jingshanghui.pos.integration.infrastructure.artifact;

import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Provider 无关的本地不可变包对象存储。
 *
 * <p>载荷和签名封装在一个对象中再原子切换；只接受服务端生成的可信相对对象键。
 * 该实现适用于内部环境或单机部署，不构成云对象存储或跨区域灾备证据。</p>
 */
public final class LocalPackageArtifactStore {
    private static final int SIGNATURE_BYTES = 64;
    private final Path root;

    public LocalPackageArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    /** 将 payload 与 Ed25519 签名作为单个不可变对象原子写入。 */
    public void put(String objectKey, byte[] payload, byte[] signature) {
        if (payload == null || payload.length == 0 || signature == null
            || signature.length != SIGNATURE_BYTES) {
            throw new ServiceException("CORE-ART-002: 包载荷或签名无效", 503);
        }
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                Stored current = get(objectKey);
                if (!java.util.Arrays.equals(current.payload(), payload)
                    || !java.util.Arrays.equals(current.signature(), signature)) {
                    throw new ServiceException("CORE-ART-003: 同一对象键对应不同内容", 409);
                }
                return;
            }
            ByteBuffer encoded = ByteBuffer.allocate(Integer.BYTES + payload.length + signature.length);
            encoded.putInt(payload.length).put(payload).put(signature);
            Path temporary = Files.createTempFile(target.getParent(), ".jshpkg-", ".tmp");
            try {
                Files.write(temporary, encoded.array());
                moveAtomically(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    /** 按可信键读取对象；不存在返回 {@code null}。 */
    public Stored get(String objectKey) {
        Path target = resolve(objectKey);
        if (!Files.exists(target)) {
            return null;
        }
        try {
            byte[] encoded = Files.readAllBytes(target);
            if (encoded.length <= Integer.BYTES + SIGNATURE_BYTES) {
                throw new ServiceException("CORE-ART-004: 包对象已损坏", 500);
            }
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            int payloadLength = buffer.getInt();
            if (payloadLength <= 0 || payloadLength != buffer.remaining() - SIGNATURE_BYTES) {
                throw new ServiceException("CORE-ART-004: 包对象长度无效", 500);
            }
            byte[] payload = new byte[payloadLength];
            byte[] signature = new byte[SIGNATURE_BYTES];
            buffer.get(payload).get(signature);
            return new Stored(payload, signature);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw unavailable(exception);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank() || objectKey.contains("\\")
            || objectKey.startsWith("/") || objectKey.contains("..")) {
            throw new ServiceException("CORE-ART-001: 对象键不在可信命名空间", 400);
        }
        Path target = root.resolve(objectKey + ".pkgobj").normalize();
        if (!target.startsWith(root)) {
            throw new ServiceException("CORE-ART-001: 对象键越过可信根目录", 400);
        }
        return target;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private ServiceException unavailable(Exception exception) {
        return new ServiceException("CORE-ART-005: 包对象存储不可用: "
            + exception.getClass().getSimpleName(), 503);
    }

    /** 防御性复制的包对象值。 */
    public record Stored(byte[] payload, byte[] signature) {
        public Stored {
            payload = payload.clone();
            signature = signature.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
        @Override public byte[] signature() { return signature.clone(); }
    }
}
