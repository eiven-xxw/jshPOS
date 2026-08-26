package com.jingshanghui.pos.reporting.gate10a;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-INVENTORY 精确整改准备阶段红基线的关闭回归。
 *
 * <p>保留 v1 风险证据，同时证明获批运行时已新增独立 v2、批量端口和可恢复导出；
 * 不得通过改写 v1 契约伪造兼容。</p>
 */
class ReportingInventoryRemediationPrepRedBaselineTest {
    private static final Path MAIN = Path.of("src/main");

    @Test
    void f02CurrentInventoryInteractiveQueryIsUnboundedAndHasNoKeysetStatement() throws IOException {
        String xml = read("resources/mapper/reporting/ReportingPersistenceMapper.xml");
        String statement = between(xml, "<select id=\"queryInventoryCost\"", "</select>");
        assertThat(statement)
            .contains("tenant_id=#{tenantId}", "projection_version=#{projectionVersion}",
                "ORDER BY business_date,store_id,warehouse_id,sku_id,currency")
            .doesNotContain("LIMIT #{limit}", "afterBusinessDate", "queryInventoryCostPage");
    }

    @Test
    void f04InventoryExportNoLongerReadsEachStoreSeparately() throws IOException {
        String source = read("java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java");
        assertThat(source).contains("writeInventoryArtifact", "batchReadPort.readInventoryCost")
            .doesNotContain("stores.stream().flatMap(storeId -> persistence.queryInventoryCost(");
    }

    @Test
    void inventoryBatchPortAndSignedCursorAreAdmitted() throws IOException {
        String port = read("java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java");
        String service = read("java/com/jingshanghui/pos/reporting/application/service/ReportQueryService.java");
        assertThat(port).contains("List<SalesDailyView> readSales", "readInventoryCost");
        assertThat(service).contains("persistence.queryInventoryCost(", "inventoryCostPage(");
        assertThat(MAIN.resolve("java/com/jingshanghui/pos/reporting/infrastructure/security/"
            + "HmacInventoryCostPageCursorCodec.java")).exists();
    }

    @Test
    void v1ApiRemainsFrozenAndV2InventoryContractIsPresent() throws IOException {
        String controller = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingController.java");
        String v2 = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java");
        assertThat(controller).contains("@GetMapping(\"/reports/inventory-cost-daily\")",
            "R<List<InventoryCostDailyView>> inventory(");
        assertThat(v2).contains("inventory-cost-daily", "InventoryCostPageView");
    }

    private String read(String relative) throws IOException {
        Path file = MAIN.resolve(relative);
        assertThat(file).as("冻结生产文件必须存在：%s", file).exists();
        return Files.readString(file);
    }

    private String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from);
        assertThat(from).as(start).isNotNegative();
        assertThat(to).as(end).isGreaterThan(from);
        return source.substring(from, to + end.length());
    }
}
