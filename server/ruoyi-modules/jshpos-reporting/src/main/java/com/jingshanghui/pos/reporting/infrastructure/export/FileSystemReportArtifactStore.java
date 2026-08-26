package com.jingshanghui.pos.reporting.infrastructure.export;

import com.jingshanghui.pos.reporting.application.port.ReportArtifactStore;
import org.dromara.common.core.exception.ServiceException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 受控根目录制品存储适配器。生产可替换为对象存储端口，但仍必须执行相同租户对象键校验。
 */
public final class FileSystemReportArtifactStore implements ReportArtifactStore {
    private static final Pattern OBJECT_KEY = Pattern.compile(
        "^reporting/[A-Za-z0-9][A-Za-z0-9_-]{0,19}/[0-9A-HJKMNP-TV-Z]{26}/[a-f0-9]{64}\\.csv$");
    private static final Pattern NAMESPACE = Pattern.compile(
        "^reporting/[A-Za-z0-9][A-Za-z0-9_-]{0,19}/[0-9A-HJKMNP-TV-Z]{26}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
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
    public StoredArtifact writeResumable(String namespace, String requestSha256, ResumableWriter writer) {
        if (namespace == null || !NAMESPACE.matcher(namespace).matches() || requestSha256 == null
            || !SHA256.matcher(requestSha256).matches()) {
            throw new ServiceException("RPT-R2R2-009: 导出恢复身份或请求摘要非法", 400);
        }
        Path directory = resolveNamespace(namespace);
        Path partial = directory.resolve(".stream.part");
        Path checkpoint = directory.resolve(".stream.checkpoint");
        try {
            Files.createDirectories(directory);
            ResumeState state = restoreState(partial, checkpoint, requestSha256);
            try (OutputStream output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writer.write(output, state.resumeCursor(), cursor -> {
                    output.flush();
                    long byteOffset = Files.size(partial);
                    saveState(checkpoint, requestSha256, byteOffset, cursor);
                });
                output.flush();
            }
            String digest = sha256(partial);
            long sizeBytes = Files.size(partial);
            String objectKey = namespace + "/" + digest + ".csv";
            Path target = resolve(objectKey);
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(checkpoint);
            return new StoredArtifact(objectKey, digest, sizeBytes);
        } catch (ServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ServiceException("RPT-R2R2-011: 可恢复导出制品写入失败", 503);
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

    private Path resolveNamespace(String namespace) {
        Path resolved = root.resolve(namespace).normalize();
        if (!resolved.startsWith(root)) {
            throw new ServiceException("RPT-EXP-005: 对象键越过租户存储根目录", 403);
        }
        return resolved;
    }

    private ResumeState restoreState(Path partial, Path checkpoint, String requestSha256) throws IOException {
        if (!Files.exists(checkpoint)) {
            if (Files.exists(partial)) {
                try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                    channel.truncate(0);
                }
            }
            return new ResumeState(0, null);
        }
        List<String> lines = Files.readAllLines(checkpoint);
        if (lines.size() != 3 || !requestSha256.equals(lines.get(0))) {
            throw new ServiceException("RPT-R2R2-009: 同导出身份对应不同请求摘要", 409);
        }
        long byteOffset;
        try {
            byteOffset = Long.parseLong(lines.get(1));
        } catch (NumberFormatException exception) {
            throw new ServiceException("RPT-R2R2-012: 导出恢复检查点损坏", 409);
        }
        if (!Files.exists(partial) || byteOffset < 0 || byteOffset > Files.size(partial)) {
            throw new ServiceException("RPT-R2R2-012: 导出恢复字节偏移无效", 409);
        }
        try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
            channel.truncate(byteOffset);
        }
        String resumeCursor = lines.get(2).isBlank() ? null : lines.get(2);
        return new ResumeState(byteOffset, resumeCursor);
    }

    private void saveState(Path checkpoint, String requestSha256, long byteOffset, String resumeCursor)
            throws IOException {
        Path temporary = Files.createTempFile(checkpoint.getParent(), ".checkpoint-", ".tmp");
        try {
            Files.writeString(temporary, requestSha256 + System.lineSeparator() + byteOffset
                + System.lineSeparator() + (resumeCursor == null ? "" : resumeCursor),
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temporary, checkpoint, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }

    /** @param byteOffset 已确认的制品字节偏移 @param resumeCursor 已确认的下一批游标 */
    private record ResumeState(long byteOffset, String resumeCursor) {
    }
}
