package com.jingshanghui.pos.service.infrastructure.storage;

import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.oss.factory.OssFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RuoYi S3 兼容对象存储适配器。对象键由 Service 应用层生成，失败时禁止回落到本地文件或数据库。
 */
@Component
public class RuoYiServiceAttachmentStorageAdapter implements ServiceAttachmentStoragePort {
    /** 每个并发上传最多占用 64 KiB 业务缓冲区。 */
    static final int BUFFER_BYTES = 64 * 1024;
    private final Path stagingDirectory;

    public RuoYiServiceAttachmentStorageAdapter(
        @Value("${jshpos.service.attachment-staging-directory:${java.io.tmpdir}/jshpos-service-attachments}")
        String stagingDirectory) {
        this(Path.of(stagingDirectory));
    }

    RuoYiServiceAttachmentStorageAdapter(Path stagingDirectory) {
        this.stagingDirectory = stagingDirectory.toAbsolutePath().normalize();
    }

    @Override
    public StagedAttachment stage(InputStream source, long declaredSize, long maximumSize) {
        if (source == null || declaredSize < 1 || maximumSize < 1 || declaredSize > maximumSize) {
            throw new ServiceException("SVC-ATT-001: 附件大小超限", 400);
        }
        Path temporary = null;
        try {
            Files.createDirectories(stagingDirectory);
            temporary = Files.createTempFile(stagingDirectory, "service-attachment-", ".upload");
            restrictPermissions(temporary);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long actual = copyBounded(source, temporary, maximumSize, digest);
            if (actual != declaredSize) {
                throw new ServiceException("SVC-ATT-001: 附件声明大小与实际内容不一致", 400);
            }
            return new FileStagedAttachment(temporary, actual, HexFormat.of().formatHex(digest.digest()));
        } catch (ServiceException exception) {
            deleteQuietly(temporary);
            throw exception;
        } catch (IOException | NoSuchAlgorithmException exception) {
            deleteQuietly(temporary);
            throw new ServiceException("SVC-ATT-003: 附件受限流式暂存失败", 503);
        }
    }

    @Override
    public void store(StoreObject object) {
        try (InputStream content = object.content().openStream()) {
            OssFactory.instance().upload(content, object.objectKey(), object.content().sizeBytes(), object.mediaType());
        } catch (IOException | RuntimeException exception) {
            throw new ServiceException("SVC-ATT-003: 受控对象存储写入失败", 503);
        }
    }

    @Override
    public String temporaryDownload(String objectKey, Duration ttl) {
        try { return OssFactory.instance().createPresignedGetUrl(objectKey, ttl); }
        catch (RuntimeException exception) { throw new ServiceException("SVC-ATT-003: 受控对象存储签名失败", 503); }
    }

    @Override
    public void delete(String objectKey) {
        try { OssFactory.instance().delete(objectKey); }
        catch (RuntimeException exception) { throw new ServiceException("SVC-ATT-003: 受控对象存储清理失败", 503); }
    }

    private static long copyBounded(InputStream source, Path target, long maximumSize, MessageDigest digest)
        throws IOException {
        long total = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                total += read;
                if (total > maximumSize) {
                    throw new ServiceException("SVC-ATT-001: 附件实际内容超过业务上限", 400);
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignored) {
            // Windows 不支持 POSIX 权限；随机文件名和受控目录 ACL 继续构成边界。
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException ignored) { /* 清理失败由目录巡检门禁发现，禁止覆盖主异常。 */ }
    }

    /** 临时路径只保留在基础设施适配器内，不得写入元数据、日志或响应。 */
    private static final class FileStagedAttachment implements StagedAttachment {
        private final Path file;
        private final long sizeBytes;
        private final String sha256;
        private final AtomicBoolean closed = new AtomicBoolean();

        private FileStagedAttachment(Path file, long sizeBytes, String sha256) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }

        @Override public long sizeBytes() { return sizeBytes; }
        @Override public String sha256() { return sha256; }

        @Override
        public InputStream openStream() throws IOException {
            if (closed.get()) throw new IOException("staged attachment already closed");
            return Files.newInputStream(file, StandardOpenOption.READ);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) deleteQuietly(file);
        }
    }
}
