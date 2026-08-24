package com.jingshanghui.pos.service.infrastructure.storage;

import com.jingshanghui.pos.service.application.port.ServiceAttachmentStoragePort.StagedAttachment;
import com.jingshanghui.pos.service.domain.ServiceRules;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** T2-SEC-002 附件固定缓冲、摘要、并发暂存与全路径临时文件回收测试。 */
class RuoYiServiceAttachmentStorageAdapterTest {

    @TempDir Path temporaryDirectory;

    @Test
    void shouldDeclareExactlyOneSpringInjectionConstructor() {
        long injectableConstructors = Arrays.stream(RuoYiServiceAttachmentStorageAdapter.class.getDeclaredConstructors())
            .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
            .count();
        assertEquals(1, injectableConstructors);
    }

    @Test
    void shouldStageExactTenMiBWithSha256AndDeleteOnClose() throws Exception {
        byte[] content = new byte[(int) ServiceRules.MAX_ATTACHMENT_BYTES];
        content[0] = 7;
        content[content.length - 1] = 9;
        RuoYiServiceAttachmentStorageAdapter adapter = adapter();

        StagedAttachment staged = adapter.stage(new ByteArrayInputStream(content), content.length,
            ServiceRules.MAX_ATTACHMENT_BYTES);
        assertAll(
            () -> assertEquals(content.length, staged.sizeBytes()),
            () -> assertEquals(sha256(content), staged.sha256()),
            () -> assertArrayEquals(content, readAll(staged)),
            () -> assertEquals(1, fileCount())
        );

        staged.close();
        assertEquals(0, fileCount());
    }

    @Test
    void shouldRejectActualContentBeyondLimitAndDeletePartialFile() {
        byte[] content = new byte[(int) ServiceRules.MAX_ATTACHMENT_BYTES + 1];
        ServiceException error = assertThrows(ServiceException.class, () -> adapter().stage(
            new ByteArrayInputStream(content), ServiceRules.MAX_ATTACHMENT_BYTES, ServiceRules.MAX_ATTACHMENT_BYTES));
        assertAll(
            () -> assertTrue(error.getMessage().contains("实际内容超过")),
            () -> assertEquals(0, fileCount())
        );
    }

    @Test
    void shouldRejectDeclaredAndActualLengthMismatchAndDeleteFile() {
        byte[] content = "synthetic".getBytes();
        ServiceException error = assertThrows(ServiceException.class, () -> adapter().stage(
            new ByteArrayInputStream(content), content.length + 1L, ServiceRules.MAX_ATTACHMENT_BYTES));
        assertAll(
            () -> assertTrue(error.getMessage().contains("不一致")),
            () -> assertEquals(0, fileCount())
        );
    }

    @Test
    void shouldBoundEachConcurrentUploadBufferAndCleanAllTemporaryFiles() throws Exception {
        assertEquals(64 * 1024, RuoYiServiceAttachmentStorageAdapter.BUFFER_BYTES);
        RuoYiServiceAttachmentStorageAdapter adapter = adapter();
        byte[] content = new byte[512 * 1024];
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> uploads = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                uploads.add(() -> {
                    try (StagedAttachment staged = adapter.stage(new ByteArrayInputStream(content), content.length,
                        ServiceRules.MAX_ATTACHMENT_BYTES)) {
                        return staged.sizeBytes() == content.length && staged.sha256().equals(sha256(content));
                    }
                });
            }
            assertTrue(executor.invokeAll(uploads).stream().allMatch(future -> {
                try { return future.get(); }
                catch (Exception exception) { return false; }
            }));
        } finally { executor.shutdownNow(); }
        assertEquals(0, fileCount());
    }

    private RuoYiServiceAttachmentStorageAdapter adapter() {
        return new RuoYiServiceAttachmentStorageAdapter(temporaryDirectory);
    }

    private long fileCount() {
        try (var files = Files.list(temporaryDirectory)) { return files.count(); }
        catch (IOException exception) { throw new AssertionError(exception); }
    }

    private static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static byte[] readAll(StagedAttachment staged) throws IOException {
        try (var source = staged.openStream()) { return source.readAllBytes(); }
    }
}
