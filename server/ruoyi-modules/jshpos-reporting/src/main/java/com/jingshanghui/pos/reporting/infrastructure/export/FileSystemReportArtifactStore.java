package com.jingshanghui.pos.reporting.infrastructure.export;

import com.jingshanghui.pos.reporting.application.port.ReportArtifactStore;
import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

/**
 * 受控根目录制品存储适配器。生产可替换为对象存储端口，但仍必须执行相同租户对象键校验。
 */
public final class FileSystemReportArtifactStore implements ReportArtifactStore {
    private static final Pattern OBJECT_KEY = Pattern.compile(
        "^reporting/[A-Za-z0-9][A-Za-z0-9_-]{0,19}/[0-9A-HJKMNP-TV-Z]{26}/[a-f0-9]{64}\\.csv$");
    private final Path root;

    public FileSystemReportArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String objectKey, byte[] content) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".reporting-", ".tmp");
            try {
                Files.write(temporary, content);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new ServiceException("RPT-EXP-001: 导出制品写入失败", 503);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try {
            return Files.readAllBytes(resolve(objectKey));
        } catch (IOException exception) {
            throw new ServiceException("RPT-EXP-002: 导出制品不存在或不可读", 404);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new ServiceException("RPT-EXP-003: 导出制品清理失败", 503);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !OBJECT_KEY.matcher(objectKey).matches() || objectKey.contains("..")
            || objectKey.contains("\\")) {
            throw new ServiceException("RPT-EXP-004: 对象键非法", 400);
        }
        Path resolved = root.resolve(objectKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ServiceException("RPT-EXP-005: 对象键越过租户存储根目录", 403);
        }
        return resolved;
    }
}
