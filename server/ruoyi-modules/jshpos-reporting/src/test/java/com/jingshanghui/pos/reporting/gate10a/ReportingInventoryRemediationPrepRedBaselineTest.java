package com.jingshanghui.pos.reporting.gate10a;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-INVENTORY 精确整改准备阶段的可复现红基线。
 *
 * <p>本测试只证明当前正式契约仍是无界 v1 查询、库存导出仍逐门店读取，且库存批量端口、
 * keyset 游标与 v2 契约尚未实现。运行时整改获批前禁止把这些断言改成生产实现。</p>
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
    void f04CurrentInventoryExportStillReadsEachStoreSeparately() throws IOException {
        String source = read("java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java");
        assertThat(source).contains("stores.stream().flatMap(storeId -> persistence.queryInventoryCost(");
    }

    @Test
    void inventoryBatchPortAndSignedCursorAreNotYetAdmitted() throws IOException {
        String port = read("java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java");
        String service = read("java/com/jingshanghui/pos/reporting/application/service/ReportQueryService.java");
        assertThat(port).contains("List<SalesDailyView> readSales").doesNotContain("readInventoryCost");
        assertThat(service).contains("persistence.queryInventoryCost(").doesNotContain("inventoryCostPage(");
        assertThat(MAIN.resolve("java/com/jingshanghui/pos/reporting/infrastructure/security/"
            + "HmacInventoryCostPageCursorCodec.java")).doesNotExist();
    }

    @Test
    void v1ApiMustRemainFrozenAndV2InventoryContractIsAbsent() throws IOException {
        String controller = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingController.java");
        String v2 = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java");
        assertThat(controller).contains("@GetMapping(\"/reports/inventory-cost-daily\")",
            "R<List<InventoryCostDailyView>> inventory(");
        assertThat(v2).doesNotContain("inventory-cost-daily", "InventoryCostPageView");
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
