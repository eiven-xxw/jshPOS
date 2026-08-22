package com.jingshanghui.pos.migration.interfaces.rest;

import com.jingshanghui.pos.migration.application.service.BusinessMigrationService;
import com.jingshanghui.pos.migration.domain.MigrationRules;
import com.jingshanghui.pos.migration.interfaces.rest.dto.BusinessMigrationRequests;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 上传边界测试：超限文件必须在分配原始字节前拒绝。 */
class BusinessMigrationControllerTest {

    @Test
    void shouldRejectOversizedFileBeforeReadingBytes() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getSize()).thenReturn((long) MigrationRules.MAX_FILE_BYTES + 1);
        BusinessMigrationController controller = new BusinessMigrationController(mock(BusinessMigrationService.class));
        BusinessMigrationRequests.UploadMetadata metadata = new BusinessMigrationRequests.UploadMetadata(
            "CATALOG", "1.0", "UTF-8", "SYNTHETIC", "CUSTODY:SYNTHETIC",
            "0".repeat(64), "CORRELATION-001");

        assertThatThrownBy(() -> controller.upload("01J00000000000000000000000", metadata, file))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("DMT-FILE-001");
        verify(file, never()).getBytes();
    }
}
