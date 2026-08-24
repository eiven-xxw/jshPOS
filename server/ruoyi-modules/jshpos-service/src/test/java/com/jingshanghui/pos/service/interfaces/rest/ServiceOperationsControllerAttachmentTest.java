package com.jingshanghui.pos.service.interfaces.rest;

import com.jingshanghui.pos.service.application.service.ServiceApplicationService;
import com.jingshanghui.pos.service.domain.ServiceRules;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** T2-SEC-002 协议边界必须在打开附件流前执行 10 MiB 业务上限。 */
class ServiceOperationsControllerAttachmentTest {

    @Test
    void shouldRejectOversizedAttachmentBeforeOpeningInputStream() throws Exception {
        ServiceApplicationService service = mock(ServiceApplicationService.class);
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn(ServiceRules.MAX_ATTACHMENT_BYTES + 1);
        ServiceOperationsController controller = new ServiceOperationsController(service);

        ServiceException error = assertThrows(ServiceException.class, () -> controller.upload(
            "01K00000000000000000000000", file, "attachment-key-001", "trace-001"));

        assertTrue(error.getMessage().contains("附件大小超限"));
        verify(file, never()).getInputStream();
        verifyNoInteractions(service);
    }

    @Test
    void shouldPassStreamAndDeclaredSizeWithoutCallingGetBytes() throws Exception {
        ServiceApplicationService service = mock(ServiceApplicationService.class);
        MultipartFile file = mock(MultipartFile.class);
        InputStream content = new ByteArrayInputStreamWithoutBulkRead("synthetic".getBytes());
        when(file.getSize()).thenReturn(9L);
        when(file.getOriginalFilename()).thenReturn("evidence.txt");
        when(file.getContentType()).thenReturn("text/plain");
        when(file.getInputStream()).thenReturn(content);
        ServiceOperationsController controller = new ServiceOperationsController(service);

        controller.upload("01K00000000000000000000000", file, "attachment-key-001", "trace-001");

        verify(file, never()).getBytes();
        verify(service).uploadAttachment(eq("01K00000000000000000000000"), eq("evidence.txt"),
            eq("text/plain"), eq(9L), same(content), eq("attachment-key-001"), eq("trace-001"));
    }

    /** 测试输入流不提供整包读取捷径，确保 Controller 只转交流。 */
    private static final class ByteArrayInputStreamWithoutBulkRead extends java.io.ByteArrayInputStream {
        private ByteArrayInputStreamWithoutBulkRead(byte[] content) { super(content); }
    }
}
