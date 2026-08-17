package com.jingshanghui.pos.resilience.infrastructure.storage;

import com.jingshanghui.pos.resilience.application.port.BackupPorts.ObjectStore;
import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.regex.Pattern;

/** 受控根目录的只追加合成对象存储；不暴露删除接口，生产需替换为带保留锁的云适配器。 */
public final class FileSystemImmutableBackupObjectStore implements ObjectStore {
    private static final Pattern KEY = Pattern.compile("^backups/[a-f0-9]{64}/[0-9A-HJKMNP-TV-Z]{26}/[a-f0-9]{64}\\.aead$");
    private final Path root;

    public FileSystemImmutableBackupObjectStore(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public void putNew(String objectKey, byte[] ciphertext, Instant immutableUntil) {
        if (immutableUntil == null || !immutableUntil.isAfter(Instant.now())) fail("BAK-OBJ-001: 保留截止时间非法", 400);
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, ciphertext, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Files.writeString(target.resolveSibling(target.getFileName() + ".retention"), immutableUntil.toString(),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException exception) {
            fail("BAK-OBJ-002: 只追加对象键已存在", 409);
        } catch (IOException exception) {
            fail("BAK-OBJ-003: 备份对象写入失败", 503);
        }
    }

    @Override public byte[] get(String objectKey) {
        try { return Files.readAllBytes(resolve(objectKey)); }
        catch (IOException exception) { fail("BAK-OBJ-004: 备份对象缺失或不可读", 422); return new byte[0]; }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !KEY.matcher(objectKey).matches() || objectKey.contains("..") || objectKey.contains("\\")) {
            fail("BAK-OBJ-005: 对象键非法", 400);
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) fail("BAK-OBJ-006: 对象键越过受控根目录", 403);
        return resolved;
    }

    private static void fail(String message, int code) { throw new ServiceException(message, code); }
}
