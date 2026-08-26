package com.jingshanghui.pos.reporting.gate10a;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate 10A-R2-R2-R2 R0 的六个可执行失败 seed。
 *
 * <p>这些测试先在旧实现上稳定失败，R1 只能通过补齐既定的 Reporting/RPT-SALES 能力使其转绿；
 * 禁止通过删除断言、放宽条件或修改其他报表查询来制造绿色。</p>
 */
class ReportingR2R2R2RedBaselineTest {
    private static final Path MAIN = Path.of("src/main");

    @Test
    void f01SalesInteractiveQueryMustExposeBoundedKeysetPage() throws IOException {
        String xml = read("resources/mapper/reporting/ReportingPersistenceMapper.xml");
        assertThat(xml)
            .contains("<select id=\"querySalesPage\"")
            .contains("LIMIT #{limit}");
    }

    @Test
    void f04SalesExportMustUseOwnerBatchReadInsteadOfPerStoreQueries() throws IOException {
        String source = read("java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java");
        assertThat(source).contains("ReportingBatchReadPort");
        assertThat(source).doesNotContain("stores.stream().flatMap(storeId -> persistence.querySales");
    }

    @Test
    void f07PagedQueryMustBindTrustedTenantAndAuthorizedStoreScope() throws IOException {
        String service = read("java/com/jingshanghui/pos/reporting/application/service/ReportQueryService.java");
        String controller = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java");
        assertThat(service).contains("tenantContext.requireTenantId()", "authorizationService.requireStoreAccess",
            "salesPage(");
        assertThat(controller).contains("@RequestMapping(\"/api/v2\")", "@GetMapping(\"/reports/sales-daily\")")
            .doesNotContain("@RequestParam String tenantId");
    }

    @Test
    void f08KeysetCursorMustBeSignedAndBoundToFrozenFilters() throws IOException {
        String codec = read("java/com/jingshanghui/pos/reporting/infrastructure/security/HmacSalesPageCursorCodec.java");
        assertThat(codec).contains("HmacSHA256", "filterSha256", "projectionVersion", "RPT-R2R2-008");
    }

    @Test
    void f09ResumableReadMustRejectSameIdentityWithDifferentRequestHash() throws IOException {
        String store = read("java/com/jingshanghui/pos/reporting/infrastructure/export/FileSystemReportArtifactStore.java");
        assertThat(store).contains("requestSha256", "RPT-R2R2-009");
    }

    @Test
    void f12StreamingExportMustPersistCursorAndByteOffsetForSafeResume() throws IOException {
        String port = read("java/com/jingshanghui/pos/reporting/application/port/ReportArtifactStore.java");
        String store = read("java/com/jingshanghui/pos/reporting/infrastructure/export/FileSystemReportArtifactStore.java");
        assertThat(port).contains("writeResumable", "saveCheckpoint");
        assertThat(store).contains("byteOffset", "resumeCursor", "StandardOpenOption.APPEND");
    }

    private String read(String relative) throws IOException {
        Path file = MAIN.resolve(relative);
        assertThat(file).as("生产文件必须存在：%s", file).exists();
        return Files.readString(file);
    }
}
