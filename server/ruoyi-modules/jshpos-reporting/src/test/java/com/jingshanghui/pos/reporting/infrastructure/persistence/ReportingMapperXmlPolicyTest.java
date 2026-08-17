package com.jingshanghui.pos.reporting.infrastructure.persistence;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 静态阻断缺租户 SQL、注解 SQL、跨 Owner 访问和危险通用 CRUD。 */
class ReportingMapperXmlPolicyTest {
    @Test void mapperUsesExplicitTenantPredicatesAndOnlyReportingTables() throws Exception {
        try (var stream=getClass().getResourceAsStream("/mapper/reporting/ReportingPersistenceMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml=new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("for update").contains("insert ignore into rpt_source_event_inbox")
                .contains("on duplicate key update").contains("delete from rpt_sales_daily")
                .contains("delete from rpt_inventory_cost_daily")
                .doesNotContain(" from ord_").doesNotContain(" join ord_").doesNotContain("update ord_")
                .doesNotContain(" from pay_").doesNotContain(" join pay_").doesNotContain("update pay_")
                .doesNotContain(" from inv_stock_").doesNotContain("update inv_")
                .doesNotContain(" from mbr_").doesNotContain("update mbr_")
                .doesNotContain("delete from rpt_source_event_inbox")
                .doesNotContain("update rpt_source_event_inbox set content_sha256");
        }
    }

    @Test void mapperInterfaceContainsNoSqlAnnotationsAndNoBaseMapper() throws Exception {
        String source=Files.readString(Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/reporting/infrastructure/persistence/mapper/ReportingPersistenceMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert").doesNotContain("@Update")
            .doesNotContain("@Delete").doesNotContain("extends BaseMapper");
    }

    @Test void reconciliationMapperUsesTrustedTenantPredicatesAndOnlyReportingTables() throws Exception {
        try (var stream=getClass().getResourceAsStream("/mapper/reporting/PaymentReconciliationMapper.xml")) {
            assertThat(stream).isNotNull();
            String xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8).toLowerCase();
            assertThat(xml).doesNotContain("select *").contains("tenant_id=#{tenantid}")
                .contains("insert ignore into rpt_payment_fact_inbox")
                .contains("insert ignore into rpt_internal_bill_inbox")
                .contains("for update").contains("delete from rpt_payment_reconciliation")
                .doesNotContain("delete from rpt_payment_fact_inbox")
                .doesNotContain("delete from rpt_internal_bill_inbox")
                .doesNotContain("update rpt_payment_fact_inbox")
                .doesNotContain("update rpt_internal_bill_inbox")
                .doesNotContain("update rpt_reconciliation_audit")
                .doesNotContain(" from pay_").doesNotContain(" join pay_").doesNotContain("update pay_")
                .doesNotContain(" from ref_").doesNotContain(" join ref_").doesNotContain("update ref_")
                .doesNotContain("http://provider").doesNotContain("https://provider");
        }
        String source=Files.readString(Path.of(System.getProperty("user.dir"),
            "src/main/java/com/jingshanghui/pos/reporting/infrastructure/persistence/mapper/PaymentReconciliationMapper.java"));
        assertThat(source).doesNotContain("@Select").doesNotContain("@Insert").doesNotContain("@Update")
            .doesNotContain("@Delete").doesNotContain("extends BaseMapper");
    }

    @Test void mapperXmlIsWellFormedWithoutLoadingExternalDtd() throws Exception {
        for(String path:new String[]{"/mapper/reporting/ReportingPersistenceMapper.xml",
            "/mapper/reporting/PaymentReconciliationMapper.xml"}) {
            try (var stream=getClass().getResourceAsStream(path)) {
                assertThat(stream).isNotNull();
                DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                factory.newDocumentBuilder().parse(new ByteArrayInputStream(stream.readAllBytes()));
            }
        }
    }
}
