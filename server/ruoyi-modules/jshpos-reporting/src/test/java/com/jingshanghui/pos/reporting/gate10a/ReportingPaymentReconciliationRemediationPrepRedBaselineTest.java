package com.jingshanghui.pos.reporting.gate10a;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RPT-PAY-REC 精确整改准备阶段的静态红基线。
 *
 * <p>本测试故意确认当前 v1 无界查询、逐门店导出和批量端口缺席仍可复现。测试通过仅表示风险
 * 已被稳定捕获，不表示运行时问题已经修复，也不授权修改正式 SQL、API、索引或迁移。</p>
 */
class ReportingPaymentReconciliationRemediationPrepRedBaselineTest {
    private static final Path MAIN = Path.of("src/main");

    @Test
    void f03CurrentPaymentReconciliationQueryIsUnbounded() throws IOException {
        String xml = read("resources/mapper/reporting/PaymentReconciliationMapper.xml");
        String statement = between(xml, "<select id=\"query\"", "</select>");
        assertThat(statement)
            .contains("tenant_id=#{tenantId}", "business_date BETWEEN #{fromDate} AND #{toDate}",
                "store_id=#{storeId}", "ORDER BY business_date,reconciliation_id")
            .doesNotContain("LIMIT #{limit}", "afterBusinessDate", "queryPage");
    }

    @Test
    void f04CurrentPaymentReconciliationExportStillReadsEachStoreSeparately() throws IOException {
        String source = read("java/com/jingshanghui/pos/reporting/application/service/ReportExportService.java");
        assertThat(source).contains("encodeLegacy", "stores.stream().flatMap(storeId ->",
            "paymentReconciliationPersistence.query(tenantId, row.fromDate(), row.toDate(), storeId")
            .doesNotContain("writePaymentReconciliationArtifact", "readPaymentReconciliation");
    }

    @Test
    void f08AndF09PaymentReconciliationBatchPortsAndSignedCursorAreNotAdmitted() throws IOException {
        String port = read("java/com/jingshanghui/pos/reporting/application/port/ReportingBatchReadPort.java");
        assertThat(port).contains("支付对账不得借此提前整改")
            .doesNotContain("readPaymentReconciliation", "PaymentReconciliationBatchRequest");
        assertThat(MAIN.resolve("java/com/jingshanghui/pos/reporting/infrastructure/security/"
            + "HmacPaymentReconciliationPageCursorCodec.java")).doesNotExist();
        assertThat(MAIN.resolve("java/com/jingshanghui/pos/payment/application/port/"
            + "ProviderNeutralPaymentFactBatchReadPort.java")).doesNotExist();
    }

    @Test
    void v1ApiRemainsFrozenAndNoV2RuntimeWasAdded() throws IOException {
        String controller = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingController.java");
        String v2 = read("java/com/jingshanghui/pos/reporting/interfaces/rest/ReportingV2Controller.java");
        assertThat(controller).contains("@GetMapping(\"/reports/payment-reconciliation\")",
            "R<List<PaymentReconciliationViews.ReconciliationView>> paymentReconciliation(");
        assertThat(v2).doesNotContain("payment-reconciliation", "PaymentReconciliationPageView");
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
