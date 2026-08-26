package com.jingshanghui.pos.reporting.gate10a;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-INVENTORY 运行时整改的先红回归。
 *
 * <p>这些断言冻结本批获准的最小生产边界；实现前必须失败，实现后用于防止批量读取、
 * 签名游标、版本化分页或流式导出退回无界/逐门店路径。</p>
 */
class ReportingInventoryRuntimeRedBaselineTest {
    private static final Path MAIN = Path.of("src/main");

    @Test
    void inventoryBatchPortAndBoundedMapperMustExist() throws IOException {
        String port = read("java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java");
        String mapper = read("resources/mapper/reporting/ReportingPersistenceMapper.xml");
        assertThat(port).contains("readInventoryCost", "InventoryCostBatchRequest", "InventoryCostKey");
        assertThat(mapper).contains("<select id=\"queryInventoryCostPage\"", "LIMIT #{limit}",
            "afterBusinessDate", "store_id IN");
    }

    @Test
    void v2SignedCursorAndResumableExportMustExistWhileV1StaysFrozen() throws IOException {
        String controller = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java");
        String export = read("java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java");
        String v1 = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingController.java");
        assertThat(controller).contains("/reports/inventory-cost-daily", "InventoryCostPageView");
        assertThat(export).contains("writeInventoryArtifact", "InventoryCostPageCursorCodec")
            .doesNotContain("stores.stream().flatMap(storeId -> persistence.queryInventoryCost(");
        assertThat(v1).contains("@GetMapping(\"/reports/inventory-cost-daily\")",
            "R<List<InventoryCostDailyView>> inventory(");
        assertThat(MAIN.resolve("java/com/jingshanghui/pos/reporting/infrastructure/security/"
            + "HmacInventoryCostPageCursorCodec.java")).exists();
    }

    private String read(String relative) throws IOException {
        Path file = MAIN.resolve(relative);
        assertThat(file).as("冻结生产文件必须存在：%s", file).exists();
        return Files.readString(file);
    }
}
